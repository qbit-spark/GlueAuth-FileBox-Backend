package org.qbitspark.glueauthfileboxbackend.files_mng_service.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.AccountRepo;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.AccountEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.entity.FileEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.entity.FolderEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.enums.UploadStage;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.enums.VirusScanStatus;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.*;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.repo.FileRepository;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.repo.FolderRepository;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.service.FileService;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.RandomExceptions;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.VirusScanException;
import org.qbitspark.glueauthfileboxbackend.minio_service.service.MinioService;
import org.qbitspark.glueauthfileboxbackend.virus_scanner_service.config.VirusScanConfig;
import org.qbitspark.glueauthfileboxbackend.virus_scanner_service.payload.VirusScanResult;
import org.qbitspark.glueauthfileboxbackend.virus_scanner_service.service.VirusScanService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileServiceImpl implements FileService {

    private final AccountRepo accountRepo;
    private final FileRepository fileRepository;
    private final FolderRepository folderRepository;
    private final MinioService minioService;
    private final VirusScanService virusScanService;
    private final VirusScanConfig virusScanConfig;
    private final Map<String, UploadStatus> uploadStatuses = new ConcurrentHashMap<>();

    private final Map<String, BatchUploadStatus> batchUploadStatuses = new ConcurrentHashMap<>();
    private final Semaphore uploadSemaphore = new Semaphore(5); // Limit concurrent uploads globally

    @Override
    @Transactional
    public FileUploadResponse uploadFile(UUID folderId, MultipartFile file) throws ItemNotFoundException {

        AccountEntity authenticatedUser = getAuthenticatedAccount();
        UUID userId = authenticatedUser.getId();

        // Basic validation
        validateFile(file);

        // Security check - scan for viruses first
        VirusScanStatus scanStatus = performVirusScan(file);

        // Prepare file metadata
        FileMetadata metadata = prepareFileMetadata(userId, folderId, file);

        // Upload and save
        return processFileUpload(metadata, file, scanStatus);
    }

    @Async("fileUploadExecutor")
    @Transactional
    public CompletableFuture<FileUploadResponse> uploadFileAsync(UUID folderId, FileData fileData, String uploadId, UUID userId, String folderPath) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                return processAsyncUpload(folderId, fileData, uploadId, userId, folderPath);
            } catch (Exception e) {
                handleUploadFailure(uploadId, fileData.getOriginalFileName(), e);
                throw new RuntimeException("Async upload failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    @Async("fileUploadExecutor")
    public CompletableFuture<String> uploadFilesBatch(UUID folderId, List<MultipartFile> files, BatchUploadOptions options, UUID userId, String folderPath) {
        String batchId = UUID.randomUUID().toString();
        log.info("Starting batch upload {} with {} files", batchId, files.size());

        return CompletableFuture.supplyAsync(() -> {
            try {
                return processBatchUpload(batchId, folderId, files, options, userId, folderPath);
            } catch (Exception e) {
                log.error("Batch upload {} failed: {}", batchId, e.getMessage());
                markBatchFailed(batchId, e.getMessage());
                throw new RuntimeException("Batch upload failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public BatchUploadStatus getBatchUploadStatus(String batchId) {
        return batchUploadStatuses.get(batchId);
    }

    @Override
    public void cleanupBatchUploadStatus(String batchId) {
        batchUploadStatuses.remove(batchId);

        // Also cleanup individual file statuses
        BatchUploadStatus batchStatus = batchUploadStatuses.get(batchId);
        if (batchStatus != null) {
            batchStatus.getFiles().keySet().forEach(uploadStatuses::remove);
        }
    }

    private String processBatchUpload(String batchId, UUID folderId, List<MultipartFile> files, BatchUploadOptions options, UUID userId, String folderPath) throws ItemNotFoundException {


        // Initialize batch status
        BatchUploadStatus batchStatus = initializeBatchStatus(batchId, files.size());

        // Convert files to FileData and create individual upload tasks
        List<CompletableFuture<Void>> uploadTasks = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String uploadId = batchId + "-file-" + i;

            try {
                FileData fileData = FileData.builder()
                        .content(file.getBytes())
                        .originalFileName(file.getOriginalFilename())
                        .contentType(file.getContentType())
                        .size(file.getSize())
                        .build();

                // Add to batch tracking
                UploadStatus uploadStatus = UploadStatus.builder()
                        .uploadId(uploadId)
                        .fileName(file.getOriginalFilename())
                        .startTime(LocalDateTime.now())
                        .stage("QUEUED")
                        .progress(0)
                        .message("Queued for upload")
                        .build();

                batchStatus.getFiles().put(uploadId, uploadStatus);

                // Create async upload task with semaphore control
                CompletableFuture<Void> uploadTask = createBatchUploadTask(
                        batchId, uploadId, folderId, fileData, userId, folderPath, options);

                uploadTasks.add(uploadTask);

            } catch (Exception e) {
                log.error("Failed to prepare file {} for batch {}: {}", file.getOriginalFilename(), batchId, e.getMessage());
                markFileInBatchFailed(batchId, uploadId, e.getMessage());


                if (options.isStopOnFirstError()) {
                    markBatchFailed(batchId, "Stopped due to file preparation error: " + e.getMessage());
                    return batchId;
                }
            }
        }

        updateBatchStatus(batchId, "PROCESSING", "Starting file uploads...");

        // Execute uploads with controlled concurrency
        CompletableFuture<Void> allUploads;

        if (options.getMaxConcurrentUploads() > 0) {
            // Process in chunks to limit concurrency
            allUploads = processInChunks(uploadTasks, options.getMaxConcurrentUploads());
        } else {
            // Process all at once
            allUploads = CompletableFuture.allOf(uploadTasks.toArray(new CompletableFuture[0]));
        }

        // Wait for completion
        try {
            allUploads.get();
            finalizeBatchUpload(batchId);
        } catch (Exception e) {
            log.error("Batch upload {} encountered errors: {}", batchId, e.getMessage());
            markBatchFailed(batchId, "Some uploads failed: " + e.getMessage());
        }

        return batchId;
    }

    private CompletableFuture<Void> createBatchUploadTask(String batchId, String uploadId, UUID folderId,
                                                          FileData fileData, UUID userId, String folderPath,
                                                          BatchUploadOptions options) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Acquire semaphore for rate limiting
                uploadSemaphore.acquire();

                try {
                    // Use existing async upload logic but with batch tracking
                    FileUploadResponse result = processAsyncUploadForBatch(
                            folderId, fileData, uploadId, userId, folderPath, batchId);

                    markFileInBatchCompleted(batchId, uploadId, result);

                } finally {
                    uploadSemaphore.release();
                }

            } catch (Exception e) {
                log.error("File upload failed in batch {}, uploadId {}: {}", batchId, uploadId, e.getMessage());
                markFileInBatchFailed(batchId, uploadId, e.getMessage());

                // If stop on first error is enabled, fail the entire batch
                if (options.isStopOnFirstError()) {
                    markBatchFailed(batchId, "Stopped due to file upload error: " + e.getMessage());
                }
            }
        });
    }

    private CompletableFuture<Void> processInChunks(List<CompletableFuture<Void>> tasks, int chunkSize) {
        CompletableFuture<Void> result = CompletableFuture.completedFuture(null);

        for (int i = 0; i < tasks.size(); i += chunkSize) {
            List<CompletableFuture<Void>> chunk = tasks.subList(i, Math.min(i + chunkSize, tasks.size()));
            CompletableFuture<Void> chunkCompletion = CompletableFuture.allOf(chunk.toArray(new CompletableFuture[0]));
            result = result.thenCompose(v -> chunkCompletion);
        }

        return result;
    }

    private FileUploadResponse processAsyncUploadForBatch(UUID folderId, FileData fileData, String uploadId,
                                                          UUID userId, String folderPath, String batchId) throws RandomExceptions {
        try {
            updateFileInBatch(batchId, uploadId, "VALIDATING", 10, "Starting file validation...");

            // Use existing validation logic
            validateFileData(fileData);
            updateFileInBatch(batchId, uploadId, "VALIDATING", 20, "File validation completed ✓");

            // Virus scanning with batch-specific progress
            VirusScanStatus scanStatus = performVirusScanWithBatchProgress(fileData, uploadId, batchId);
            updateFileInBatch(batchId, uploadId, "VIRUS_SCANNING", 50, "Virus scan completed: " + scanStatus + " ✓");

            // Continue with existing upload logic...
            updateFileInBatch(batchId, uploadId, "PREPARING", 55, "Preparing upload...");

            FolderEntity folder = createFolderReference(folderId);
            String finalFileName = handleDuplicateFileName(userId, fileData.getOriginalFileName(), folderId);

            updateFileInBatch(batchId, uploadId, "PREPARING", 60, "File prepared: " + finalFileName);

            String minioKey = uploadToStorage(userId, folderPath, finalFileName, fileData, uploadId, batchId);
            updateFileInBatch(batchId, uploadId, "UPLOADING", 90, "Storage upload completed ✓");

            FileEntity savedFile = saveToDatabase(fileData, userId, folder, finalFileName, scanStatus, minioKey, uploadId);
            updateFileInBatch(batchId, uploadId, "SAVING", 95, "File record saved ✓");

            FileUploadResponse response = buildUploadResponse(savedFile, folder, folderPath, false,
                    fileData.getOriginalFileName(), finalFileName);

            updateFileInBatch(batchId, uploadId, "COMPLETED", 100, "Upload completed successfully! ✅");

            return response;

        } catch (Exception e) {
            updateFileInBatch(batchId, uploadId, "FAILED", 0, "Upload failed: " + e.getMessage());
            throw new RandomExceptions("Upload failed: " + e.getMessage());
        }
    }

    // Batch status management methods
    private BatchUploadStatus initializeBatchStatus(String batchId, int totalFiles) {
        BatchUploadStatus status = BatchUploadStatus.builder()
                .batchId(batchId)
                .totalFiles(totalFiles)
                .completedFiles(0)
                .failedFiles(0)
                .overallProgress(0.0)
                .status("QUEUED")
                .startTime(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .files(new ConcurrentHashMap<>())
                .completedUploads(new ArrayList<>())
                .message("Batch upload initialized")
                .build();

        batchUploadStatuses.put(batchId, status);
        return status;
    }

    private void updateFileInBatch(String batchId, String uploadId, String stage, int progress, String message) {
        BatchUploadStatus batchStatus = batchUploadStatuses.get(batchId);
        if (batchStatus == null) return;

        UploadStatus fileStatus = batchStatus.getFiles().get(uploadId);
        if (fileStatus != null) {
            fileStatus.setStage(stage);
            fileStatus.setProgress(progress);
            fileStatus.setMessage(message);
            fileStatus.setLastUpdated(LocalDateTime.now());

            batchStatus.updateOverallProgress();
        }
    }

    private void markFileInBatchCompleted(String batchId, String uploadId, FileUploadResponse result) {
        BatchUploadStatus batchStatus = batchUploadStatuses.get(batchId);
        if (batchStatus == null) return;

        UploadStatus fileStatus = batchStatus.getFiles().get(uploadId);
        if (fileStatus != null) {
            fileStatus.setCompleted(true);
            fileStatus.setResult(result);
            fileStatus.setStage("COMPLETED");
            fileStatus.setProgress(100);
            fileStatus.setMessage("Upload completed successfully");
            fileStatus.setLastUpdated(LocalDateTime.now());

            batchStatus.setCompletedFiles(batchStatus.getCompletedFiles() + 1);
            batchStatus.getCompletedUploads().add(result);
            batchStatus.updateOverallProgress();
        }
    }

    private void markFileInBatchFailed(String batchId, String uploadId, String errorMessage) {
        BatchUploadStatus batchStatus = batchUploadStatuses.get(batchId);
        if (batchStatus == null) return;

        UploadStatus fileStatus = batchStatus.getFiles().get(uploadId);
        if (fileStatus != null) {
            fileStatus.setFailed(true);
            fileStatus.setErrorMessage(errorMessage);
            fileStatus.setStage("FAILED");
            fileStatus.setMessage("Upload failed: " + errorMessage);
            fileStatus.setLastUpdated(LocalDateTime.now());

            batchStatus.setFailedFiles(batchStatus.getFailedFiles() + 1);
            batchStatus.updateOverallProgress();
        }
    }

    private void finalizeBatchUpload(String batchId) {
        BatchUploadStatus batchStatus = batchUploadStatuses.get(batchId);
        if (batchStatus == null) return;

        batchStatus.updateOverallProgress();

        if (batchStatus.getFailedFiles() == 0) {
            batchStatus.setStatus("COMPLETED");
            batchStatus.setMessage("All files uploaded successfully");
        } else if (batchStatus.getCompletedFiles() > 0) {
            batchStatus.setStatus("PARTIAL");
            batchStatus.setMessage(String.format("Batch completed with %d successes and %d failures",
                    batchStatus.getCompletedFiles(), batchStatus.getFailedFiles()));
        } else {
            batchStatus.setStatus("FAILED");
            batchStatus.setMessage("All files failed to upload");
        }

        log.info("Batch upload {} finalized: {} completed, {} failed",
                batchId, batchStatus.getCompletedFiles(), batchStatus.getFailedFiles());
    }

    private void markBatchFailed(String batchId, String errorMessage) {
        BatchUploadStatus batchStatus = batchUploadStatuses.get(batchId);
        if (batchStatus != null) {
            batchStatus.setStatus("FAILED");
            batchStatus.setMessage(errorMessage);
            batchStatus.setLastUpdated(LocalDateTime.now());
        }
    }

    private void updateBatchStatus(String batchId, String status, String message) {
        BatchUploadStatus batchStatus = batchUploadStatuses.get(batchId);
        if (batchStatus != null) {
            batchStatus.setStatus(status);
            batchStatus.setMessage(message);
            batchStatus.setLastUpdated(LocalDateTime.now());
        }
    }

    // Helper methods for batch-specific operations
    private VirusScanStatus performVirusScanWithBatchProgress(FileData fileData, String uploadId, String batchId) {
        if (!virusScanConfig.isEnabled()) {
            updateFileInBatch(batchId, uploadId, "VIRUS_SCANNING", 45, "Virus scanning disabled");
            return VirusScanStatus.SKIPPED;
        }

        try {
            updateFileInBatch(batchId, uploadId, "VIRUS_SCANNING", 25, "Initializing virus scanner...");
            updateFileInBatch(batchId, uploadId, "VIRUS_SCANNING", 35, "Scanning file content...");

            VirusScanResult result = virusScanService.scanFileContent(fileData.getContent(), fileData.getOriginalFileName());

            if (result.getStatus() == VirusScanStatus.INFECTED) {
                throw new SecurityException("File rejected: " + result.getMessage());
            }

            updateFileInBatch(batchId, uploadId, "VIRUS_SCANNING", 45, "Analyzing scan results...");
            return result.getStatus();

        } catch (VirusScanException e) {
            if (e.getMessage().contains("TIMED OUT") || e.getMessage().contains("timeout")) {
                updateFileInBatch(batchId, uploadId, "VIRUS_SCANNING", 45, "Virus scan timed out - proceeding");
                return VirusScanStatus.SKIPPED;
            }

            if (virusScanConfig.isFailOnUnavailable()) {
                throw new SecurityException("Virus scan error: " + e.getMessage());
            } else {
                updateFileInBatch(batchId, uploadId, "VIRUS_SCANNING", 45, "Virus scan failed - proceeding");
                return VirusScanStatus.SKIPPED;
            }
        }
    }

    private String uploadToStorage(UUID userId, String folderPath, String finalFileName,
                                   FileData fileData, String uploadId, String batchId) {
        updateFileInBatch(batchId, uploadId, "UPLOADING", 65, "Connecting to storage...");
        updateFileInBatch(batchId, uploadId, "UPLOADING", 70, "Uploading to storage...");

        String minioKey = minioService.uploadFile(userId, folderPath, finalFileName,
                fileData.getInputStream(), fileData.getSize(), fileData.getContentType());

        if (minioKey == null || minioKey.trim().isEmpty()) {
            throw new RuntimeException("MinIO upload failed - returned null/empty key");
        }

        return minioKey;
    }


    private FileUploadResponse processAsyncUpload(UUID folderId, FileData fileData, String uploadId, UUID userId, String folderPath) {

        updateUploadStatus(uploadId, "VALIDATING", 10, "Starting file validation...", fileData.getOriginalFileName());
        log.info("Starting async upload for file: {} ({} bytes)", fileData.getOriginalFileName(), fileData.getSize());

        // Step 1: File Validation (10-20%)
        validateFileData(fileData);
        updateUploadStatus(uploadId, "VALIDATING", 20, "File validation completed ✓", fileData.getOriginalFileName());

        // Step 2: Virus Scanning (20-50%)
        VirusScanStatus scanStatus = performVirusScanWithProgress(fileData, uploadId);
        updateUploadStatus(uploadId, "VIRUS_SCANNING", 50, "Virus scan completed: " + scanStatus + " ✓", fileData.getOriginalFileName());

        // Step 3: File Name Processing (50-60%)
        updateUploadStatus(uploadId, "PREPARING", 55, "Preparing upload...", fileData.getOriginalFileName());

        FolderEntity folder = createFolderReference(folderId);
        String finalFileName = handleDuplicateFileName(userId, fileData.getOriginalFileName(), folderId);
        boolean wasRenamed = !finalFileName.equals(fileData.getOriginalFileName());

        updateUploadStatus(uploadId, "PREPARING", 60, "File prepared: " + finalFileName, fileData.getOriginalFileName());

        // Step 4: MinIO Upload (60-90%)
        String minioKey = uploadToStorage(userId, folderPath, finalFileName, fileData, uploadId);
        updateUploadStatus(uploadId, "UPLOADING", 90, "Storage upload completed ✓", fileData.getOriginalFileName());

        // Step 5: Database Save (90-95%)
        FileEntity savedFile = saveToDatabase(fileData, userId, folder, finalFileName, scanStatus, minioKey, uploadId);
        updateUploadStatus(uploadId, "SAVING", 95, "File record saved ✓", fileData.getOriginalFileName());

        // Step 6: Build Response (95-100%)
        FileUploadResponse response = buildUploadResponse(savedFile, folder, folderPath, wasRenamed, fileData.getOriginalFileName(), finalFileName);

        markUploadCompleted(uploadId, response, "Upload completed successfully! ✅");
        log.info("Async upload completed successfully for: {}", fileData.getOriginalFileName());

        return response;
    }


    // Helper Methods

    private void validateFileData(FileData fileData) {
        if (fileData.isEmpty()) {
            throw new RuntimeException("File is empty");
        }

        if (fileData.getOriginalFileName() == null || fileData.getOriginalFileName().trim().isEmpty()) {
            throw new RuntimeException("File name is required");
        }

        // Size limit check (100MB)
        long maxSize = 100 * 1024 * 1024;
        if (fileData.getSize() > maxSize) {
            throw new RuntimeException("File size exceeds maximum limit of 100MB");
        }

        // Content type validation
        String contentType = fileData.getContentType();
        if (contentType != null && contentType.toLowerCase().contains("executable")) {
            throw new RuntimeException("Executable files are not allowed");
        }
    }


    private VirusScanStatus performVirusScanWithProgress(FileData fileData, String uploadId) {
        if (!virusScanConfig.isEnabled()) {
            updateUploadStatus(uploadId, "VIRUS_SCANNING", 45, "Virus scanning disabled", fileData.getOriginalFileName());
            return VirusScanStatus.SKIPPED;
        }

        try {
            updateUploadStatus(uploadId, "VIRUS_SCANNING", 25, "Initializing virus scanner...", fileData.getOriginalFileName());
            updateUploadStatus(uploadId, "VIRUS_SCANNING", 35, "Scanning file content...", fileData.getOriginalFileName());

            // Use existing scanFileContent method
            VirusScanResult result = virusScanService.scanFileContent(fileData.getContent(), fileData.getOriginalFileName());

            if (result.getStatus() == VirusScanStatus.INFECTED) {
                throw new SecurityException("File rejected: " + result.getMessage());
            }

            updateUploadStatus(uploadId, "VIRUS_SCANNING", 45, "Analyzing scan results...", fileData.getOriginalFileName());
            return result.getStatus();

        } catch (VirusScanException e) {
            if (e.getMessage().contains("TIMED OUT") || e.getMessage().contains("timeout")) {
                updateUploadStatus(uploadId, "VIRUS_SCANNING", 45, "Virus scan timed out - proceeding", fileData.getOriginalFileName());
                return VirusScanStatus.SKIPPED;
            }

            if (virusScanConfig.isFailOnUnavailable()) {
                throw new SecurityException("Virus scan error: " + e.getMessage());
            } else {
                updateUploadStatus(uploadId, "VIRUS_SCANNING", 45, "Virus scan failed - proceeding", fileData.getOriginalFileName());
                return VirusScanStatus.SKIPPED;
            }
        }
    }

    private FolderEntity createFolderReference(UUID folderId) {
        if (folderId == null) {
            return null;
        }

        FolderEntity folder = new FolderEntity();
        folder.setFolderId(folderId);
        return folder;
    }

    private String uploadToStorage(UUID userId, String folderPath, String finalFileName, FileData fileData, String uploadId) {
        updateUploadStatus(uploadId, "UPLOADING", 65, "Connecting to storage...", fileData.getOriginalFileName());
        updateUploadStatus(uploadId, "UPLOADING", 70, "Uploading to storage...", fileData.getOriginalFileName());

        String minioKey = minioService.uploadFile(userId, folderPath, finalFileName,
                fileData.getInputStream(), fileData.getSize(), fileData.getContentType());

        if (minioKey == null || minioKey.trim().isEmpty()) {
            throw new RuntimeException("MinIO upload failed - returned null/empty key");
        }

        return minioKey;
    }

    private FileEntity saveToDatabase(FileData fileData, UUID userId, FolderEntity folder, String finalFileName,
                                      VirusScanStatus scanStatus, String minioKey, String uploadId) {
        updateUploadStatus(uploadId, "SAVING", 92, "Saving file record...", fileData.getOriginalFileName());

        FileEntity fileEntity = new FileEntity();
        fileEntity.setFileName(finalFileName);
        fileEntity.setUserId(userId);
        fileEntity.setFolder(folder);
        fileEntity.setFileSize(fileData.getSize());
        fileEntity.setMimeType(fileData.getContentType());
        fileEntity.setScanStatus(scanStatus);
        fileEntity.setMinioKey(minioKey);

        return fileRepository.save(fileEntity);
    }

    private FileUploadResponse buildUploadResponse(FileEntity savedFile, FolderEntity folder, String folderPath,
                                                   boolean wasRenamed, String originalFileName, String finalFileName) {
        return FileUploadResponse.builder()
                .fileId(savedFile.getFileId())
                .fileName(savedFile.getFileName())
                .folderId(folder != null ? folder.getFolderId() : null)
                .folderPath(folderPath)
                .fileSize(savedFile.getFileSize())
                .mimeType(savedFile.getMimeType())
                .scanStatus(savedFile.getScanStatus())
                .uploadedAt(savedFile.getCreatedAt())
                .build();
    }

    private void handleUploadFailure(String uploadId, String fileName, Exception e) {
        log.error("Async upload failed for file: {} - {}", fileName, e.getMessage(), e);
        markUploadFailed(uploadId, e.getMessage());
    }


    @Override
    public UploadStatus getUploadStatus(String uploadId) {
        return uploadStatuses.get(uploadId);
    }

    @Override
    public void cleanupUploadStatus(String uploadId) {
        uploadStatuses.remove(uploadId);
    }

    private VirusScanStatus performVirusScan(MultipartFile file) {
        if (!virusScanConfig.isEnabled()) {
            log.info("Virus scanning disabled for file: {}", file.getOriginalFilename());
            return VirusScanStatus.SKIPPED;
        }

        try {
            log.info("Scanning file: {} ({} bytes)", file.getOriginalFilename(), file.getSize());

            VirusScanResult result = virusScanService.scanFile(file);

            if (result.getStatus() == VirusScanStatus.INFECTED) {
                log.warn("VIRUS DETECTED: {} - {}", file.getOriginalFilename(), result.getVirusName());
                throw new SecurityException("File rejected: " + result.getMessage());
            }

            if (result.getStatus() == VirusScanStatus.SKIPPED) {
                log.info("Virus scan skipped: {} - {}", file.getOriginalFilename(), result.getMessage());
            } else {
                log.info("File clean: {} ({}ms)", file.getOriginalFilename(), result.getScanDurationMs());
            }

            return result.getStatus();

        } catch (VirusScanException e) {
            // Handle timeout gracefully
            if (e.getMessage().contains("TIMED OUT") ||
                    e.getMessage().contains("timeout") ||
                    e.getMessage().contains("COMMAND READ TIMED OUT")) {

                log.warn("Virus scan timed out for complex file: {} - allowing upload with SKIPPED status",
                        file.getOriginalFilename());
                return VirusScanStatus.SKIPPED;
            }

            log.error("Scan failed for {}: {}", file.getOriginalFilename(), e.getMessage());

            // Check if we should fail or allow the upload
            if (virusScanConfig.isFailOnUnavailable()) {
                throw new SecurityException("Virus scan error: " + e.getMessage());
            } else {
                log.warn("Allowing upload despite scan failure due to configuration");
                return VirusScanStatus.SKIPPED;
            }
        }
    }

    // Extract file metadata preparation
    private FileMetadata prepareFileMetadata(UUID userId, UUID folderId, MultipartFile file) throws ItemNotFoundException {
        FolderEntity folder = null;
        String folderPath = "";

        if (folderId != null) {
            folder = folderRepository.findById(folderId)
                    .orElseThrow(() -> new ItemNotFoundException("Folder not found"));

            if (!folder.getUserId().equals(userId)) {
                throw new SecurityException("Access denied: Folder not owned by user");
            }

            folderPath = folder.getFullPath();
        }

        String originalFileName = file.getOriginalFilename();
        String finalFileName = handleDuplicateFileName(userId, originalFileName, folderId);
        boolean wasRenamed = !finalFileName.equals(originalFileName);

        return FileMetadata.builder()
                .userId(userId)
                .folder(folder)
                .folderPath(folderPath)
                .originalFileName(originalFileName)
                .finalFileName(finalFileName)
                .wasRenamed(wasRenamed)
                .build();
    }

    // Extract upload and save logic
    private FileUploadResponse processFileUpload(FileMetadata metadata, MultipartFile file, VirusScanStatus scanStatus) {
        try {
            // Upload to MinIO
            String minioKey = uploadToMinio(metadata, file);

            // Save to database
            FileEntity savedFile = saveToDatabase(metadata, file, scanStatus, minioKey);

            // Build response
            return buildResponse(metadata, savedFile);

        } catch (Exception e) {
            log.error("Upload failed for {}: {}", metadata.getFinalFileName(), e.getMessage());
            throw new RuntimeException("File upload failed: " + e.getMessage());
        }
    }

    private String uploadToMinio(FileMetadata metadata, MultipartFile file) {
        log.info("Uploading to MinIO: userId={}, path='{}', file='{}'",
                metadata.getUserId(), metadata.getFolderPath(), metadata.getFinalFileName());

        String minioKey = minioService.uploadFile(
                metadata.getUserId(),
                metadata.getFolderPath(),
                metadata.getFinalFileName(),
                file);

        if (minioKey == null || minioKey.trim().isEmpty()) {
            throw new RuntimeException("MinIO upload failed - empty key returned");
        }

        log.info("MinIO upload successful: {}", minioKey);
        return minioKey;
    }


    private FileEntity saveToDatabase(FileMetadata metadata, MultipartFile file, VirusScanStatus scanStatus, String minioKey) {
        FileEntity fileEntity = new FileEntity();
        fileEntity.setFileName(metadata.getFinalFileName());
        fileEntity.setUserId(metadata.getUserId());
        fileEntity.setFolder(metadata.getFolder());
        fileEntity.setFileSize(file.getSize());
        fileEntity.setMimeType(file.getContentType());
        fileEntity.setScanStatus(scanStatus);
        fileEntity.setMinioKey(minioKey);

        FileEntity savedFile = fileRepository.save(fileEntity);
        log.info("Database save successful: {} (status: {})", savedFile.getFileId(), scanStatus);

        return savedFile;
    }

    private FileUploadResponse buildResponse(FileMetadata metadata, FileEntity savedFile) {
        String message = metadata.isWasRenamed()
                ? String.format("File uploaded and renamed from '%s' to '%s' to avoid duplicates.",
                metadata.getOriginalFileName(), metadata.getFinalFileName())
                : "File uploaded successfully!";

        return FileUploadResponse.builder()
                .fileId(savedFile.getFileId())
                .fileName(savedFile.getFileName())
                .folderId(metadata.getFolder() != null ? metadata.getFolder().getFolderId() : null)
                .folderPath(metadata.getFolderPath())
                .fileSize(savedFile.getFileSize())
                .mimeType(savedFile.getMimeType())
                .scanStatus(savedFile.getScanStatus())
                .uploadedAt(savedFile.getCreatedAt())
                .build();
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new RuntimeException("File is empty");
        }

        if (file.getOriginalFilename() == null || file.getOriginalFilename().trim().isEmpty()) {
            throw new RuntimeException("File name is required");
        }

        // Add size limit (e.g., 100MB)
        long maxSize = 100 * 1024 * 1024; // 100MB
        if (file.getSize() > maxSize) {
            throw new RuntimeException("File size exceeds maximum limit of 100MB");
        }

        // Add file type validation if needed
        String contentType = file.getContentType();
        if (contentType != null && contentType.toLowerCase().contains("executable")) {
            throw new RuntimeException("Executable files are not allowed");
        }
    }

    private String handleDuplicateFileName(UUID userId, String originalFileName, UUID folderId) {
        String baseName = getFileBaseName(originalFileName);
        String extension = getFileExtension(originalFileName);

        String currentFileName = originalFileName;
        int counter = 1;

        // Keep checking until we find an available name
        while (fileExistsInLocation(userId, currentFileName, folderId)) {
            currentFileName = baseName + " (" + counter + ")" + extension;
            counter++;

            // Safety check to prevent infinite loop
            if (counter > 1000) {
                throw new RuntimeException("Too many duplicate files with similar names");
            }
        }

        if (!currentFileName.equals(originalFileName)) {
            log.info("Renamed duplicate file from '{}' to '{}'", originalFileName, currentFileName);
        }

        return currentFileName;
    }

    private String getFileBaseName(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return fileName; // No extension
        }
        return fileName.substring(0, lastDotIndex);
    }

    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return ""; // No extension
        }
        return fileName.substring(lastDotIndex); // Include the dot
    }

    private boolean fileExistsInLocation(UUID userId, String fileName, UUID folderId) {
        if (folderId == null) {
            return fileRepository.findByUserIdAndFileNameAndFolderIsNull(userId, fileName).isPresent();
        } else {
            return fileRepository.findByUserIdAndFileNameAndFolder_FolderId(userId, fileName, folderId).isPresent();
        }
    }


    private AccountEntity getAuthenticatedAccount() throws ItemNotFoundException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return extractAccount(authentication);
    }

    private AccountEntity extractAccount(Authentication authentication) throws ItemNotFoundException {
        if (authentication != null && authentication.isAuthenticated()) {
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String userName = userDetails.getUsername();
            return accountRepo.findByUserName(userName)
                    .orElseThrow(() -> new ItemNotFoundException("User given username does not exist"));
        }
        throw new ItemNotFoundException("User is not authenticated");
    }

    // Status tracking methods
    private void updateUploadStatus(String uploadId, String stage, int progress, String message, String fileName) {
        UploadStatus status = uploadStatuses.computeIfAbsent(uploadId, id ->
                UploadStatus.builder()
                        .uploadId(id)
                        .fileName(fileName)
                        .startTime(LocalDateTime.now())
                        .build()
        );

        status.setStage(stage);
        status.setProgress(progress);
        status.setMessage(message);
        status.setLastUpdated(LocalDateTime.now());

        log.info("Upload {} - {}: {} ({}%)", uploadId, stage, message, progress);
    }

    private void markUploadCompleted(String uploadId, FileUploadResponse response, String message) {
        UploadStatus status = uploadStatuses.get(uploadId);
        if (status != null) {
            status.setStage("COMPLETED");
            status.setProgress(100);
            status.setMessage(message);
            status.setCompleted(true);
            status.setResult(response);
            status.setLastUpdated(LocalDateTime.now());
        }
    }

    private void markUploadFailed(String uploadId, String errorMessage) {
        UploadStatus status = uploadStatuses.get(uploadId);
        if (status != null) {
            status.setStage("FAILED");
            status.setFailed(true);
            status.setErrorMessage(errorMessage);
            status.setMessage("Upload failed: " + errorMessage);
            status.setLastUpdated(LocalDateTime.now());
        }
    }
}