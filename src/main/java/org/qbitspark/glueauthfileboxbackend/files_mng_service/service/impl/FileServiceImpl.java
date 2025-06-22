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
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

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
    public FileUploadResponse uploadFileSync(UUID folderId, MultipartFile file) throws ItemNotFoundException {

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

    @Override
    @Transactional
    public BatchUploadSyncResponse uploadFilesBatchSync(UUID folderId, List<MultipartFile> files, BatchUploadOptions options) throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();
        UUID userId = user.getId();

        log.info("Starting synchronous batch upload for user: {} - Files: {}", user.getUserName(), files.size());

        // Step 1: Validate inputs
        validateSyncBatchInputs(files);

        // Step 2: Validate and get folder info
        FolderEntity targetFolder = validateSyncBatchFolder(folderId, userId);
        String folderPath = targetFolder != null ? targetFolder.getFullPath() : "";

        // Step 3: Initialize result collections
        List<FileUploadResponse> uploadedFiles = new ArrayList<>();
        List<BatchUploadSyncResponse.FailedUpload> failures = new ArrayList<>();

        // Step 4: Process each file
        processSyncBatchFiles(folderId, files, options, userId, folderPath, targetFolder, uploadedFiles, failures);

        // Step 5: Build and return response
        return buildSyncBatchResponse(files.size(), uploadedFiles, failures, user.getUserName());
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

    @Override
    public FileInfoResponse getFileInfo(UUID fileId) throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();

        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new ItemNotFoundException("File not found"));

        if (!file.getUserId().equals(user.getId())) {
            throw new RuntimeException("Access denied: You don't own this file");
        }

        return FileInfoResponse.builder()
                .id(file.getFileId())
                .name(file.getFileName())
                .size(file.getFileSize())
                .sizeFormatted(formatFileSize(file.getFileSize()))
                .mimeType(file.getMimeType())
                .extension(getFileExtension(file.getFileName()))
                .category(getFileCategory(file.getMimeType()))
                .scanStatus(file.getScanStatus().toString())
                .folderPath(getFolderPath(file.getFolder()))
                .canPreview(canPreviewFile(file.getMimeType(), file.getScanStatus()))
                .canDownload(canDownloadFile(file.getScanStatus()))
                .uploadedAt(file.getCreatedAt())
                .updatedAt(file.getUpdatedAt())
                .build();
    }


    @Override
    public UploadStatus getUploadStatus(String uploadId) {
        return uploadStatuses.get(uploadId);
    }

    @Override
    public void cleanupUploadStatus(String uploadId) {
        uploadStatuses.remove(uploadId);
    }

    @Override
    public ResponseEntity<InputStreamResource> downloadFile(UUID fileId) throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();

        // Get file and validate ownership
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new ItemNotFoundException("File not found"));

        if (!file.getUserId().equals(user.getId())) {
            throw new RuntimeException("Access denied: You don't own this file");
        }

        // Check virus scan status
        if (file.getScanStatus() == VirusScanStatus.INFECTED) {
            throw new RuntimeException("File blocked: Contains virus");
        }

        // Get file stream from MinIO
        InputStream fileStream = minioService.downloadFile(user.getId(), file.getMinioKey());

        // Set response headers (FIXED)
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + file.getFileName() + "\"");
        headers.setContentType(MediaType.parseMediaType(
                file.getMimeType() != null ? file.getMimeType() : "application/octet-stream"));
        headers.setContentLength(file.getFileSize());

        log.info("File download started: {} by user: {}", file.getFileName(), user.getUserName());

        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(fileStream));
    }


    // Add to FileServiceImpl.java
    @Override
    public ResponseEntity<InputStreamResource> previewFile(UUID fileId) throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();

        // Get file and validate ownership
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new ItemNotFoundException("File not found"));

        if (!file.getUserId().equals(user.getId())) {
            throw new RuntimeException("Access denied: You don't own this file");
        }

        // Check virus scan status
        if (file.getScanStatus() == VirusScanStatus.INFECTED) {
            throw new RuntimeException("File blocked: Contains virus");
        }

        // Check if a file can be previewed
        if (!canPreviewFile(file.getMimeType(), file.getScanStatus())) {
            throw new RuntimeException("Preview not available for this file type");
        }

        // Get file stream from MinIO
        InputStream fileStream = minioService.downloadFile(user.getId(), file.getMinioKey());

        // Set preview headers (inline, not download)
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getFileName() + "\"");
        headers.setContentType(MediaType.parseMediaType(file.getMimeType()));
        headers.setContentLength(file.getFileSize());

        log.info("File preview: {} by user: {}", file.getFileName(), user.getUserName());

        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(fileStream));
    }

    @Override
    @Transactional
    public void deleteFile(UUID fileId) throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();

        // Get a file and validate ownership
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new ItemNotFoundException("File not found"));

        if (!file.getUserId().equals(user.getId())) {
            throw new RuntimeException("Access denied: You don't own this file");
        }

        if (file.getIsDeleted()) {
            throw new RuntimeException("File already deleted");
        }

        // Create a unique trash key to prevent conflicts
        String originalKey = file.getMinioKey();
        String trashKey = "TRASH_" + UUID.randomUUID() + "_" + originalKey;

        // Rename in MinIO
        minioService.renameFile(user.getId(), originalKey, trashKey);

        // Update database
        file.setMinioKey(trashKey);
        file.setIsDeleted(true);
        file.setDeletedAt(LocalDateTime.now());
        fileRepository.save(file);

        log.info("File moved to trash: {} ({})", file.getFileName(), user.getUserName());
    }

    @Override
    @Transactional
    public void restoreFile(UUID fileId) throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();

        // Get deleted file and validate ownership
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new ItemNotFoundException("File not found"));

        if (!file.getUserId().equals(user.getId())) {
            throw new RuntimeException("Access denied: You don't own this file");
        }

        if (!file.getIsDeleted()) {
            throw new RuntimeException("File is not deleted");
        }

        // Extract original key from trash key
        String trashKey = file.getMinioKey();
        String originalKey = extractOriginalKeyFromTrash(trashKey);

        // Handle potential name conflicts in restore location
        String finalFileName = handleRestoreNameConflict(user.getId(), file, originalKey);

        // Rename in MinIO back to original location
        minioService.renameFile(user.getId(), trashKey, originalKey);

        // Update database
        file.setMinioKey(originalKey);
        file.setIsDeleted(false);
        file.setDeletedAt(null);

        // Update filename if there was a conflict
        if (!finalFileName.equals(file.getFileName())) {
            file.setFileName(finalFileName);
            log.info("File renamed during restore: {} -> {}", file.getFileName(), finalFileName);
        }

        fileRepository.save(file);

        log.info("File restored from trash: {} ({})", file.getFileName(), user.getUserName());
    }

    @Override
    @Transactional
    public BulkRestoreResponse bulkRestoreFiles(BulkRestoreRequest request) throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();
        UUID userId = user.getId();

        log.info("Starting bulk file restore for user: {} - Files: {}",
                user.getUserName(), request.getFileIds().size());

        List<UUID> restoredFileIds = new ArrayList<>();
        List<BulkRestoreResponse.FailedRestore> failures = new ArrayList<>();

        // Pre-validate all file permissions for restore
        List<UUID> validFileIds = validateFilePermissionsForRestore(request.getFileIds(), userId, failures);

        log.info("Permission validation completed - Valid files: {}, Invalid files: {}",
                validFileIds.size(), failures.size());

        // Process file restores for validated files only
        for (UUID fileId : validFileIds) {
            try {
                restoreFileInternal(fileId, userId);
                restoredFileIds.add(fileId);
                log.debug("Successfully restored file: {}", fileId);

            } catch (Exception e) {
                String fileName = getFileNameSafely(fileId);
                failures.add(BulkRestoreResponse.FailedRestore.builder()
                        .fileId(fileId)
                        .fileName(fileName)
                        .reason("Restore failed: " + e.getMessage())
                        .build());

                log.warn("Failed to restore file {}: {}", fileId, e.getMessage());
            }
        }

        int totalRequested = request.getFileIds().size();
        int successfulRestores = restoredFileIds.size();
        int failedRestores = failures.size();

        String summary = buildRestoreSummary(successfulRestores, failedRestores, totalRequested);

        log.info("Bulk restore completed for user: {} - Success: {}, Failed: {}",
                user.getUserName(), successfulRestores, failedRestores);

        return BulkRestoreResponse.builder()
                .totalFilesRequested(totalRequested)
                .successfulRestores(successfulRestores)
                .failedRestores(failedRestores)
                .restoredFileIds(restoredFileIds)
                .failures(failures)
                .summary(summary)
                .build();
    }


    @Override
    public SearchResponse searchItems(String query, Pageable pageable) throws ItemNotFoundException {

        if (query == null || query.trim().isEmpty()) {
            throw new RuntimeException("Search query cannot be empty");
        }

        AccountEntity user = getAuthenticatedAccount();
        UUID userId = user.getId();
        String searchTerm = query.trim();

        log.info("Searching for '{}' for user: {}", searchTerm, user.getUserName());

        // Search folders - use the simple method without isDeleted
        List<FolderEntity> matchingFolders = folderRepository
                .findByUserIdAndFolderNameContainingIgnoreCase(userId, searchTerm);

        // Search files - use the simple method without isDeleted
        List<FileEntity> matchingFiles = fileRepository
                .findByUserIdAndFileNameContainingIgnoreCase(userId, searchTerm);

        // Filter out deleted files manually
        List<FileEntity> activeFiles = matchingFiles.stream()
                .filter(file -> !file.getIsDeleted())
                .toList();

        // Combine results
        List<Object> allResults = new ArrayList<>();
        allResults.addAll(matchingFolders);
        allResults.addAll(activeFiles);

        // Sort by creation date (newest first)
        allResults.sort((a, b) -> {
            LocalDateTime dateA = a instanceof FolderEntity ?
                    ((FolderEntity) a).getCreatedAt() : ((FileEntity) a).getCreatedAt();
            LocalDateTime dateB = b instanceof FolderEntity ?
                    ((FolderEntity) b).getCreatedAt() : ((FileEntity) b).getCreatedAt();
            return dateB.compareTo(dateA);
        });

        // Apply pagination
        Page<Object> pagedResults = applyPagination(allResults, pageable);

        // Convert to response objects
        List<SearchResponse.FolderResult> folders = new ArrayList<>();
        List<SearchResponse.FileResult> files = new ArrayList<>();

        for (Object item : pagedResults.getContent()) {
            if (item instanceof FolderEntity) {
                folders.add(convertToSearchFolderResult((FolderEntity) item));
            } else if (item instanceof FileEntity) {
                files.add(convertToSearchFileResult((FileEntity) item));
            }
        }

        // Build response
        return SearchResponse.builder()
                .query(searchTerm)
                .contents(SearchResponse.Contents.builder()
                        .folders(folders)
                        .files(files)
                        .build())
                .pagination(buildSearchPagination(pagedResults))
                .summary(SearchResponse.SummaryInfo.builder()
                        .totalResults(matchingFolders.size() + activeFiles.size())
                        .totalFolders(matchingFolders.size())
                        .totalFiles(activeFiles.size())
                        .build())
                .build();
    }


    @Override
    public SearchResponse searchItemsInFolder(UUID folderId, String query, Pageable pageable) throws ItemNotFoundException {

        if (query == null || query.trim().isEmpty()) {
            throw new RuntimeException("Search query cannot be empty");
        }

        AccountEntity user = getAuthenticatedAccount();
        UUID userId = user.getId();
        String searchTerm = query.trim();

        log.info("Searching for '{}' recursively in folder {} for user: {}", searchTerm, folderId, user.getUserName());

        // Validate folder ownership if provided
        validateFolderAccess(folderId, userId);

        // Get all folders and files recursively
        List<FolderEntity> allFolders = getAllFoldersRecursive(userId, folderId, searchTerm);
        List<FileEntity> allFiles = getAllFilesRecursive(userId, folderId, searchTerm);

        // Build and return response
        return buildSearchResponse(searchTerm, allFolders, allFiles, pageable);
    }

    @Override
    @Transactional
    public void moveFile(UUID fileId, UUID destinationFolderId) throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();
        UUID userId = user.getId();

        log.info("Moving file {} to folder {} for user: {}", fileId, destinationFolderId, user.getUserName());

        // Get and validate source file
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new ItemNotFoundException("File not found"));

        if (!file.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied: You don't own this file");
        }

        if (file.getIsDeleted()) {
            throw new RuntimeException("Cannot move deleted file");
        }

        // Get and validate destination folder
        FolderEntity destinationFolder = validateDestinationFolder(destinationFolderId, userId);

        // Check if file is already in destination folder
        if (isFileAlreadyInFolder(file, destinationFolderId)) {
            throw new RuntimeException("File is already in the destination folder");
        }

        // Handle name conflicts and get final filename
        String originalFolderPath = file.getFolder() != null ? file.getFolder().getFullPath() : "";
        String destinationFolderPath = destinationFolder != null ? destinationFolder.getFullPath() : "";
        String finalFileName = handleMoveNameConflict(userId, file.getFileName(), destinationFolderId);

        // Move file in MinIO
        String oldMinioKey = file.getMinioKey();
        String newMinioKey = generateNewMinioKey(destinationFolderPath, finalFileName);

        minioService.renameFile(userId, oldMinioKey, newMinioKey);

        // Update database
        file.setFolder(destinationFolder);
        file.setFileName(finalFileName);
        file.setMinioKey(newMinioKey);
        fileRepository.save(file);

        log.info("File moved successfully: {} -> {}", originalFolderPath + "/" + file.getFileName(),
                destinationFolderPath + "/" + finalFileName);
    }

    @Override
    @Transactional
    public FileUploadResponse copyFile(UUID fileId, UUID destinationFolderId) throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();
        UUID userId = user.getId();

        log.info("Copying file {} to folder {} for user: {}", fileId, destinationFolderId, user.getUserName());

        // Get and validate source file
        FileEntity sourceFile = fileRepository.findById(fileId)
                .orElseThrow(() -> new ItemNotFoundException("File not found"));

        if (!sourceFile.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied: You don't own this file");
        }

        if (sourceFile.getIsDeleted()) {
            throw new RuntimeException("Cannot copy deleted file");
        }

        // Get and validate destination folder
        FolderEntity destinationFolder = validateDestinationFolder(destinationFolderId, userId);
        String destinationFolderPath = destinationFolder != null ? destinationFolder.getFullPath() : "";

        // Handle name conflicts and get final filename
        String finalFileName = handleCopyNameConflict(userId, sourceFile.getFileName(), destinationFolderId);

        // Copy file in MinIO
        String sourceMinioKey = sourceFile.getMinioKey();
        String newMinioKey = generateNewMinioKey(destinationFolderPath, finalFileName);

        minioService.copyFile(userId, sourceMinioKey, newMinioKey);

        // Create new database record (duplicate everything except ID and timestamps)
        FileEntity newFile = createFileEntityCopy(sourceFile, destinationFolder, finalFileName, newMinioKey);
        FileEntity savedFile = fileRepository.save(newFile);

        log.info("File copied successfully: {} -> {}",
                sourceFile.getFileName(), destinationFolderPath + "/" + finalFileName);

        // Build response
        return FileUploadResponse.builder()
                .fileId(savedFile.getFileId())
                .fileName(savedFile.getFileName())
                .folderId(destinationFolderId)
                .folderPath(destinationFolderPath)
                .fileSize(savedFile.getFileSize())
                .mimeType(savedFile.getMimeType())
                .scanStatus(savedFile.getScanStatus())
                .uploadedAt(savedFile.getCreatedAt())
                .build();
    }

    @Override
    @Transactional
    public BulkDeleteResponse bulkDeleteFiles(BulkDeleteRequest request) throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();
        UUID userId = user.getId();

        log.info("Starting bulk file delete for user: {} - Files: {}",
                user.getUserName(), request.getFileIds().size());

        List<UUID> deletedFileIds = new ArrayList<>();
        List<BulkDeleteResponse.FailedDeletion> failures = new ArrayList<>();

        // Process file deletions
        for (UUID fileId : request.getFileIds()) {
            try {
                deleteFileInternal(fileId, userId);
                deletedFileIds.add(fileId);
                log.debug("Successfully deleted file: {}", fileId);

            } catch (Exception e) {
                String fileName = getFileNameSafely(fileId);
                failures.add(BulkDeleteResponse.FailedDeletion.builder()
                        .fileId(fileId)
                        .fileName(fileName)
                        .reason(e.getMessage())
                        .build());

                log.warn("Failed to delete file {}: {}", fileId, e.getMessage());
            }
        }

        int totalRequested = request.getFileIds().size();
        int successfulDeletions = deletedFileIds.size();
        int failedDeletions = failures.size();

        String summary = buildDeletionSummary(successfulDeletions, failedDeletions, totalRequested);

        log.info("Bulk delete completed for user: {} - Success: {}, Failed: {}",
                user.getUserName(), successfulDeletions, failedDeletions);

        return BulkDeleteResponse.builder()
                .totalFilesRequested(totalRequested)
                .successfulDeletions(successfulDeletions)
                .failedDeletions(failedDeletions)
                .deletedFileIds(deletedFileIds)
                .failures(failures)
                .summary(summary)
                .build();
    }

    @Override
    @Transactional
    public BulkCopyResponse bulkCopyFiles(BulkCopyRequest request) throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();
        UUID userId = user.getId();

        log.info("Starting bulk file copy for user: {} - Files: {}, Destination: {}",
                user.getUserName(), request.getFileIds().size(), request.getDestinationFolderId());

        // Validate destination folder first
        FolderEntity destinationFolder = validateDestinationFolder(request.getDestinationFolderId(), userId);
        String destinationPath = destinationFolder != null ? destinationFolder.getFullPath() : "Root";

        List<FileUploadResponse> copiedFiles = new ArrayList<>();
        List<BulkCopyResponse.FailedOperation> failures = new ArrayList<>();

        // Pre-validate all file permissions
        List<UUID> validFileIds = validateFilePermissionsForCopy(request.getFileIds(), userId, failures);

        log.info("Permission validation completed - Valid files: {}, Invalid files: {}",
                validFileIds.size(), failures.size());

        // Process file copies for validated files only
        for (UUID fileId : validFileIds) {
            try {
                FileUploadResponse copiedFile = copyFileInternal(fileId, request.getDestinationFolderId(), userId);
                copiedFiles.add(copiedFile);
                log.debug("Successfully copied file: {} to {}", fileId, destinationPath);

            } catch (Exception e) {
                String fileName = getFileNameSafely(fileId);
                failures.add(BulkCopyResponse.FailedOperation.builder()
                        .sourceFileId(fileId)
                        .fileName(fileName)
                        .reason("Copy failed: " + e.getMessage())
                        .build());

                log.warn("Failed to copy file {}: {}", fileId, e.getMessage());
            }
        }

        int totalRequested = request.getFileIds().size();
        int successfulCopies = copiedFiles.size();
        int failedCopies = failures.size();

        String summary = buildCopySummary(successfulCopies, failedCopies, totalRequested, destinationPath);

        log.info("Bulk copy completed for user: {} - Success: {}, Failed: {}",
                user.getUserName(), successfulCopies, failedCopies);

        return BulkCopyResponse.builder()
                .totalFilesRequested(totalRequested)
                .successfulCopies(successfulCopies)
                .failedCopies(failedCopies)
                .copiedFiles(copiedFiles)
                .failures(failures)
                .summary(summary)
                .destinationPath(destinationPath)
                .build();
    }


    @Override
    @Transactional
    public BulkMoveResponse bulkMoveFiles(BulkMoveRequest request) throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();
        UUID userId = user.getId();

        log.info("Starting bulk file move for user: {} - Files: {}, Destination: {}",
                user.getUserName(), request.getFileIds().size(), request.getDestinationFolderId());

        // Validate destination folder first
        FolderEntity destinationFolder = validateDestinationFolder(request.getDestinationFolderId(), userId);
        String destinationPath = destinationFolder != null ? destinationFolder.getFullPath() : "Root";

        List<UUID> movedFileIds = new ArrayList<>();
        List<BulkMoveResponse.FailedOperation> failures = new ArrayList<>();

        // Pre-validate all file permissions
        List<UUID> validFileIds = validateFilePermissionsForMove(request.getFileIds(), userId,
                request.getDestinationFolderId(), failures);

        log.info("Permission validation completed - Valid files: {}, Invalid files: {}",
                validFileIds.size(), failures.size());

        // Process file moves for validated files only
        for (UUID fileId : validFileIds) {
            try {
                moveFileInternal(fileId, request.getDestinationFolderId(), userId);
                movedFileIds.add(fileId);
                log.debug("Successfully moved file: {} to {}", fileId, destinationPath);

            } catch (Exception e) {
                String fileName = getFileNameSafely(fileId);
                failures.add(BulkMoveResponse.FailedOperation.builder()
                        .fileId(fileId)
                        .fileName(fileName)
                        .reason("Move failed: " + e.getMessage())
                        .build());

                log.warn("Failed to move file {}: {}", fileId, e.getMessage());
            }
        }

        int totalRequested = request.getFileIds().size();
        int successfulMoves = movedFileIds.size();
        int failedMoves = failures.size();

        String summary = buildMoveSummary(successfulMoves, failedMoves, totalRequested, destinationPath);

        log.info("Bulk move completed for user: {} - Success: {}, Failed: {}",
                user.getUserName(), successfulMoves, failedMoves);

        return BulkMoveResponse.builder()
                .totalFilesRequested(totalRequested)
                .successfulMoves(successfulMoves)
                .failedMoves(failedMoves)
                .movedFileIds(movedFileIds)
                .failures(failures)
                .summary(summary)
                .destinationPath(destinationPath)
                .build();
    }


    @Override
    @Transactional
    public EmptyTrashResponse emptyTrash(EmptyTrashRequest request) throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();
        UUID userId = user.getId();

        log.info("Starting empty trash for user: {} - Specific files: {}",
                user.getUserName(),
                request.getFileIds() != null ? request.getFileIds().size() : "ALL");

        List<UUID> permanentlyDeletedFileIds = new ArrayList<>();
        List<EmptyTrashResponse.FailedDeletion> failures = new ArrayList<>();
        long totalSpaceFreed = 0L;

        // Get files to permanently delete
        List<FileEntity> filesToDelete = getFilesToPermanentlyDelete(userId, request.getFileIds());

        log.info("Found {} deleted files to permanently remove for user: {}",
                filesToDelete.size(), user.getUserName());

        if (filesToDelete.isEmpty()) {
            return EmptyTrashResponse.builder()
                    .totalFilesProcessed(0)
                    .successfulDeletions(0)
                    .failedDeletions(0)
                    .permanentlyDeletedFileIds(new ArrayList<>())
                    .failures(new ArrayList<>())
                    .summary("No files found in trash to delete")
                    .totalSpaceFreed(0L)
                    .totalSpaceFreedFormatted("0 B")
                    .build();
        }

        // Pre-validate permissions for all files
        List<FileEntity> validFiles = validateTrashFilePermissions(filesToDelete, userId, failures);

        log.info("Permission validation completed - Valid files: {}, Invalid files: {}",
                validFiles.size(), failures.size());

        // Permanently delete validated files
        for (FileEntity file : validFiles) {
            try {
                long fileSize = file.getFileSize();
                permanentlyDeleteFileFromStorage(file, userId);
                permanentlyDeleteFileFromDatabase(file);

                permanentlyDeletedFileIds.add(file.getFileId());
                totalSpaceFreed += fileSize;

                log.debug("Permanently deleted file: {} (freed {} bytes)",
                        file.getFileId(), fileSize);

            } catch (Exception e) {
                failures.add(EmptyTrashResponse.FailedDeletion.builder()
                        .fileId(file.getFileId())
                        .fileName(file.getFileName())
                        .minioKey(file.getMinioKey())
                        .reason("Permanent deletion failed: " + e.getMessage())
                        .build());

                log.error("Failed to permanently delete file {}: {}", file.getFileId(), e.getMessage());

                // Stop processing if continueOnError is false
                if (!request.isContinueOnError()) {
                    log.warn("Stopping trash emptying due to error and continueOnError=false");
                    break;
                }
            }
        }

        int totalProcessed = filesToDelete.size();
        int successfulDeletions = permanentlyDeletedFileIds.size();
        int failedDeletions = failures.size();

        String summary = buildEmptyTrashSummary(successfulDeletions, failedDeletions, totalProcessed);
        String spaceFreedFormatted = formatFileSize(totalSpaceFreed);

        log.info("Empty trash completed for user: {} - Success: {}, Failed: {}, Space freed: {}",
                user.getUserName(), successfulDeletions, failedDeletions, spaceFreedFormatted);

        return EmptyTrashResponse.builder()
                .totalFilesProcessed(totalProcessed)
                .successfulDeletions(successfulDeletions)
                .failedDeletions(failedDeletions)
                .permanentlyDeletedFileIds(permanentlyDeletedFileIds)
                .failures(failures)
                .summary(summary)
                .totalSpaceFreed(totalSpaceFreed)
                .totalSpaceFreedFormatted(spaceFreedFormatted)
                .build();
    }


    @Override
    @Transactional(readOnly = true)
    public TrashResponse getTrashFiles(Pageable pageable) throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();
        UUID userId = user.getId();

        log.info("Getting trash files for user: {} (page: {}, size: {})",
                user.getUserName(), pageable.getPageNumber(), pageable.getPageSize());

        // Get all deleted files for user
        List<FileEntity> allTrashFiles = fileRepository.findByUserIdAndIsDeletedTrue(userId);

        // Sort by deletion date (newest first)
        allTrashFiles.sort((a, b) -> {
            LocalDateTime dateA = a.getDeletedAt() != null ? a.getDeletedAt() : a.getUpdatedAt();
            LocalDateTime dateB = b.getDeletedAt() != null ? b.getDeletedAt() : b.getUpdatedAt();
            return dateB.compareTo(dateA);
        });

        // Apply pagination
        Page<FileEntity> pagedTrashFiles = applyPaginationToTrashFiles(allTrashFiles, pageable);

        // Convert to response items
        List<TrashResponse.TrashFileItem> trashItems = pagedTrashFiles.getContent().stream()
                .map(this::convertToTrashFileItem)
                .toList();

        // Build summary
        TrashResponse.TrashSummary summary = buildTrashSummary(allTrashFiles);

        // Build pagination info
        TrashResponse.PaginationInfo paginationInfo = buildTrashPaginationInfo(pagedTrashFiles);

        log.info("Retrieved {} trash files for user: {} (total in trash: {})",
                trashItems.size(), user.getUserName(), allTrashFiles.size());

        return TrashResponse.builder()
                .summary(summary)
                .files(trashItems)
                .pagination(paginationInfo)
                .build();
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

    // Helper methods
    private String getFolderPath(FolderEntity folder) {
        return folder != null ? folder.getFullPath() : "";
    }

    private boolean canPreviewFile(String mimeType, VirusScanStatus scanStatus) {
        // Block infected files completely
        if (scanStatus == VirusScanStatus.INFECTED) {
            return false;
        }

        // Allow preview for safe file types only
        if (mimeType == null) return false;

        return mimeType.startsWith("image/") ||
                mimeType.equals("application/pdf") ||
                mimeType.startsWith("text/");
    }

    private boolean canDownloadFile(VirusScanStatus scanStatus) {
        // Block infected files completely
        return scanStatus != VirusScanStatus.INFECTED;
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";

        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));

        return String.format("%.1f %s",
                bytes / Math.pow(1024, digitGroups),
                units[digitGroups]);
    }

    private String getFileCategory(String mimeType) {
        if (mimeType == null) return "unknown";

        if (mimeType.startsWith("image/")) return "image";
        if (mimeType.startsWith("video/")) return "video";
        if (mimeType.startsWith("audio/")) return "audio";
        if (mimeType.contains("pdf")) return "document";
        if (mimeType.contains("word") || mimeType.contains("document")) return "document";
        if (mimeType.contains("sheet") || mimeType.contains("excel")) return "spreadsheet";
        if (mimeType.contains("presentation") || mimeType.contains("powerpoint")) return "presentation";
        if (mimeType.contains("zip") || mimeType.contains("archive")) return "archive";
        if (mimeType.startsWith("text/")) return "text";

        return "file";
    }


    // Helper method to extract original key from trash key
    private String extractOriginalKeyFromTrash(String trashKey) {
        if (trashKey.startsWith("TRASH_")) {
            // Format: "TRASH_uuid_original/path/file.ext"
            int firstUnderscore = trashKey.indexOf("_");
            int secondUnderscore = trashKey.indexOf("_", firstUnderscore + 1);
            if (secondUnderscore != -1) {
                return trashKey.substring(secondUnderscore + 1);
            }
        }
        return trashKey; // Fallback
    }

    // Handle name conflicts during restore
    private String handleRestoreNameConflict(UUID userId, FileEntity file, String originalKey) {
        UUID folderId = file.getFolder() != null ? file.getFolder().getFolderId() : null;
        String fileName = file.getFileName();

        // Check if active file with same name exists in target location
        boolean conflict = false;
        if (folderId == null) {
            conflict = fileRepository.findByUserIdAndFileNameAndFolderIsNullAndIsDeletedFalse(
                    userId, fileName).isPresent();
        } else {
            conflict = fileRepository.findByUserIdAndFileNameAndFolder_FolderIdAndIsDeletedFalse(
                    userId, fileName, folderId).isPresent();
        }

        if (conflict) {
            // Generate new name to avoid conflict
            return generateUniqueFileName(userId, fileName, folderId);
        }

        return fileName; // No conflict
    }

    // Generate unique filename for restore conflicts
    private String generateUniqueFileName(UUID userId, String originalFileName, UUID folderId) {
        String baseName = getFileBaseName(originalFileName);
        String extension = getFileExtension(originalFileName);

        String currentFileName = originalFileName;
        int counter = 1;

        while (activeFileExistsInLocation(userId, currentFileName, folderId)) {
            currentFileName = baseName + " (" + counter + ")" + extension;
            counter++;

            if (counter > 1000) {
                throw new RuntimeException("Too many files with similar names");
            }
        }

        return currentFileName;
    }

    // Check if active file exists in location
    private boolean activeFileExistsInLocation(UUID userId, String fileName, UUID folderId) {
        if (folderId == null) {
            return fileRepository.findByUserIdAndFileNameAndFolderIsNullAndIsDeletedFalse(
                    userId, fileName).isPresent();
        } else {
            return fileRepository.findByUserIdAndFileNameAndFolder_FolderIdAndIsDeletedFalse(
                    userId, fileName, folderId).isPresent();
        }
    }

    // Helper method to get file base name (without extension)
    private String getFileBaseName(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return fileName;
        }
        return fileName.substring(0, lastDotIndex);
    }



    // Simple folder conversion
    private SearchResponse.FolderResult convertToSearchFolderResult(FolderEntity folder) {
        return SearchResponse.FolderResult.builder()
                .id(folder.getFolderId())
                .name(folder.getFolderName())
                .type("folder")
                .folderPath(folder.getParentFolder() != null ? folder.getParentFolder().getFullPath() : "Root")
                .itemCount(countItemsInFolder(folder.getFolderId(), folder.getUserId()))
                .hasSubfolders(hasSubfolders(folder.getFolderId(), folder.getUserId()))
                .createdAt(folder.getCreatedAt())
                .updatedAt(folder.getUpdatedAt())
                .build();
    }

    private SearchResponse.FileResult convertToSearchFileResult(FileEntity file) {
        return SearchResponse.FileResult.builder()
                .id(file.getFileId())
                .name(file.getFileName())
                .type("file")
                .size(file.getFileSize())
                .sizeFormatted(formatFileSize(file.getFileSize()))
                .mimeType(file.getMimeType())
                .extension(getFileExtension(file.getFileName()))
                .category(getFileCategory(file.getMimeType()))
                .scanStatus(file.getScanStatus().toString())
                .folderPath(file.getFolder() != null ? file.getFolder().getFullPath() : "Root")
                .canPreview(canPreviewFile(file.getMimeType(), file.getScanStatus()))
                .canDownload(canDownloadFile(file.getScanStatus()))
                .createdAt(file.getCreatedAt())
                .updatedAt(file.getUpdatedAt())
                .build();
    }


    private SearchResponse.PaginationInfo buildSearchPagination(Page<Object> page) {
        return SearchResponse.PaginationInfo.builder()
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .isFirst(page.isFirst())
                .isLast(page.isLast())
                .build();
    }

    private int countItemsInFolder(UUID folderId, UUID userId) {
        long folders = folderRepository.countByUserIdAndParentFolder_FolderIdAndIsDeletedFalse(userId, folderId);
        long files = fileRepository.countByUserIdAndFolder_FolderIdAndIsDeletedFalse(userId, folderId);
        return (int) (folders + files);
    }

    private boolean hasSubfolders(UUID folderId, UUID userId) {
        return folderRepository.countByUserIdAndParentFolder_FolderIdAndIsDeletedFalse(userId, folderId) > 0;
    }

    private Page<Object> applyPagination(List<Object> items, Pageable pageable) {
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), items.size());

        List<Object> pagedItems = items.subList(start, end);
        return new PageImpl<>(pagedItems, pageable, items.size());
    }

    private List<FolderEntity> getAllFoldersRecursive(UUID userId, UUID folderId, String searchTerm) {
        // Get all folder IDs in scope (current folder + all subfolders)
        List<UUID> folderIds = getFolderIdsInScope(userId, folderId);

        List<FolderEntity> matchingFolders = new ArrayList<>();

        for (UUID currentFolderId : folderIds) {
            List<FolderEntity> foldersInThisLevel = folderRepository
                    .findByUserIdAndParentFolder_FolderIdAndFolderNameContainingIgnoreCase(userId, currentFolderId, searchTerm);
            matchingFolders.addAll(foldersInThisLevel);
        }

        return matchingFolders;
    }

    private List<FileEntity> getAllFilesRecursive(UUID userId, UUID folderId, String searchTerm) {
        // Get all folder IDs in scope (current folder + all subfolders)
        List<UUID> folderIds = getFolderIdsInScope(userId, folderId);

        List<FileEntity> matchingFiles = new ArrayList<>();

        // Search in each folder
        for (UUID currentFolderId : folderIds) {
            List<FileEntity> filesInThisFolder = fileRepository
                    .findByUserIdAndFolder_FolderIdAndFileNameContainingIgnoreCase(userId, currentFolderId, searchTerm);
            matchingFiles.addAll(filesInThisFolder);
        }

        // Also search root level if folderId is null
        if (folderId == null) {
            List<FileEntity> rootFiles = fileRepository
                    .findByUserIdAndFolderIsNullAndFileNameContainingIgnoreCase(userId, searchTerm);
            matchingFiles.addAll(rootFiles);
        }

        // Filter out deleted files
        return matchingFiles.stream().filter(file -> !file.getIsDeleted()).toList();
    }

    private List<UUID> getFolderIdsInScope(UUID userId, UUID folderId) {
        List<UUID> folderIds = new ArrayList<>();

        if (folderId == null) {
            // If no folder specified, get all root folders and their children
            List<FolderEntity> rootFolders = folderRepository.findByUserIdAndParentFolderIsNull(userId);
            for (FolderEntity rootFolder : rootFolders) {
                folderIds.add(rootFolder.getFolderId());
                folderIds.addAll(getAllSubfolderIds(rootFolder.getFolderId(), userId));
            }
        } else {
            // Include the specified folder and all its subfolders
            folderIds.add(folderId);
            folderIds.addAll(getAllSubfolderIds(folderId, userId));
        }

        return folderIds;
    }

    private List<UUID> getAllSubfolderIds(UUID parentFolderId, UUID userId) {
        List<UUID> subfolderIds = new ArrayList<>();

        List<FolderEntity> directSubfolders = folderRepository
                .findByUserIdAndParentFolder_FolderId(userId, parentFolderId);

        for (FolderEntity subfolder : directSubfolders) {
            subfolderIds.add(subfolder.getFolderId());
            // Recursively get subfolders of this subfolder
            subfolderIds.addAll(getAllSubfolderIds(subfolder.getFolderId(), userId));
        }

        return subfolderIds;
    }

    private void validateFolderAccess(UUID folderId, UUID userId) throws ItemNotFoundException {
        if (folderId == null) return;

        FolderEntity folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ItemNotFoundException("Folder not found"));

        if (!folder.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied: You don't own this folder");
        }
    }

    private SearchResponse buildSearchResponse(String searchTerm, List<FolderEntity> folders,
                                               List<FileEntity> files, Pageable pageable) {
        // Combine and sort
        List<Object> allResults = new ArrayList<>();
        allResults.addAll(folders);
        allResults.addAll(files);
        allResults.sort(this::compareByName);

        // Paginate and convert
        Page<Object> pagedResults = applyPagination(allResults, pageable);

        List<SearchResponse.FolderResult> folderResults = new ArrayList<>();
        List<SearchResponse.FileResult> fileResults = new ArrayList<>();

        for (Object item : pagedResults.getContent()) {
            if (item instanceof FolderEntity) {
                folderResults.add(convertToSearchFolderResult((FolderEntity) item));
            } else {
                fileResults.add(convertToSearchFileResult((FileEntity) item));
            }
        }

        return SearchResponse.builder()
                .query(searchTerm)
                .contents(SearchResponse.Contents.builder()
                        .folders(folderResults)
                        .files(fileResults)
                        .build())
                .pagination(buildSearchPagination(pagedResults))
                .summary(SearchResponse.SummaryInfo.builder()
                        .totalResults(folders.size() + files.size())
                        .totalFolders(folders.size())
                        .totalFiles(files.size())
                        .build())
                .build();
    }

    private int compareByName(Object a, Object b) {
        String nameA = a instanceof FolderEntity ?
                ((FolderEntity) a).getFolderName() : ((FileEntity) a).getFileName();
        String nameB = b instanceof FolderEntity ?
                ((FolderEntity) b).getFolderName() : ((FileEntity) b).getFileName();
        return nameA.compareToIgnoreCase(nameB);
    }

    private FolderEntity validateDestinationFolder(UUID destinationFolderId, UUID userId) throws ItemNotFoundException {
        if (destinationFolderId == null) {
            return null; // Moving to root
        }

        FolderEntity folder = folderRepository.findById(destinationFolderId)
                .orElseThrow(() -> new ItemNotFoundException("Destination folder not found"));

        if (!folder.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied: You don't own the destination folder");
        }

        return folder;
    }

    private boolean isFileAlreadyInFolder(FileEntity file, UUID destinationFolderId) {
        UUID currentFolderId = file.getFolder() != null ? file.getFolder().getFolderId() : null;

        // Both null (moving from root to root)
        if (currentFolderId == null && destinationFolderId == null) {
            return true;
        }

        // Compare folder IDs
        return currentFolderId != null && currentFolderId.equals(destinationFolderId);
    }

    private String handleMoveNameConflict(UUID userId, String originalFileName, UUID destinationFolderId) {
        String baseName = getFileBaseName(originalFileName);
        String extension = getFileExtension(originalFileName);

        String currentFileName = originalFileName;
        int counter = 1;

        // Keep checking until we find an available name
        while (fileExistsInDestination(userId, currentFileName, destinationFolderId)) {
            currentFileName = baseName + " (" + counter + ")" + extension;
            counter++;

            if (counter > 1000) {
                throw new RuntimeException("Too many files with similar names");
            }
        }

        if (!currentFileName.equals(originalFileName)) {
            log.info("Renamed file during move: {} -> {}", originalFileName, currentFileName);
        }

        return currentFileName;
    }

    private boolean fileExistsInDestination(UUID userId, String fileName, UUID destinationFolderId) {
        if (destinationFolderId == null) {
            return fileRepository.findByUserIdAndFileNameAndFolderIsNullAndIsDeletedFalse(userId, fileName).isPresent();
        } else {
            return fileRepository.findByUserIdAndFileNameAndFolder_FolderIdAndIsDeletedFalse(userId, fileName, destinationFolderId).isPresent();
        }
    }

    private String generateNewMinioKey(String folderPath, String fileName) {
        if (folderPath == null || folderPath.trim().isEmpty()) {
            return fileName;
        }
        return folderPath.endsWith("/") ? folderPath + fileName : folderPath + "/" + fileName;
    }

    private String handleCopyNameConflict(UUID userId, String originalFileName, UUID destinationFolderId) {
        String baseName = getFileBaseName(originalFileName);
        String extension = getFileExtension(originalFileName);

        String currentFileName = originalFileName;
        int counter = 1;

        // Keep checking until we find an available name
        while (fileExistsInDestination(userId, currentFileName, destinationFolderId)) {
            currentFileName = baseName + " (" + counter + ")" + extension;
            counter++;

            if (counter > 1000) {
                throw new RuntimeException("Too many files with similar names");
            }
        }

        if (!currentFileName.equals(originalFileName)) {
            log.info("Renamed file during copy: {} -> {}", originalFileName, currentFileName);
        }

        return currentFileName;
    }

    private FileEntity createFileEntityCopy(FileEntity sourceFile, FolderEntity destinationFolder,
                                            String finalFileName, String newMinioKey) {
        FileEntity newFile = new FileEntity();

        // Copy all properties from source
        newFile.setFileName(finalFileName);
        newFile.setUserId(sourceFile.getUserId());
        newFile.setFolder(destinationFolder);
        newFile.setFileSize(sourceFile.getFileSize());
        newFile.setMimeType(sourceFile.getMimeType());
        newFile.setScanStatus(sourceFile.getScanStatus()); // Inherit scan status
        newFile.setMinioKey(newMinioKey);
        newFile.setIsDeleted(false);

        // Let database handle timestamps (createdAt, updatedAt)

        return newFile;
    }

    private void deleteFileInternal(UUID fileId, UUID userId) throws ItemNotFoundException {

        // Get file and validate ownership
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new ItemNotFoundException("File not found: " + fileId));

        if (!file.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied: You don't own this file");
        }

        if (file.getIsDeleted()) {
            throw new RuntimeException("File already deleted");
        }

        // Create unique trash key to prevent conflicts
        String originalKey = file.getMinioKey();
        String trashKey = "TRASH_" + UUID.randomUUID() + "_" + originalKey;

        // Rename in MinIO
        minioService.renameFile(userId, originalKey, trashKey);

        // Update database
        file.setMinioKey(trashKey);
        file.setIsDeleted(true);
        file.setDeletedAt(LocalDateTime.now());
        fileRepository.save(file);
    }

    private String getFileNameSafely(UUID fileId) {
        try {
            return fileRepository.findById(fileId)
                    .map(FileEntity::getFileName)
                    .orElse("Unknown file");
        } catch (Exception e) {
            return "Unknown file";
        }
    }

    private String buildDeletionSummary(int successful, int failed, int total) {
        if (failed == 0) {
            return String.format("Successfully moved %d file(s) to trash", successful);
        } else if (successful == 0) {
            return String.format("Failed to delete all %d file(s)", total);
        } else {
            return String.format("Moved %d file(s) to trash, %d failed", successful, failed);
        }
    }

    private List<UUID> validateFilePermissionsForCopy(List<UUID> fileIds, UUID userId,
                                                      List<BulkCopyResponse.FailedOperation> failures) {

        List<UUID> validFileIds = new ArrayList<>();

        for (UUID fileId : fileIds) {
            try {
                // Check if file exists and user has permission
                if (!fileRepository.existsByFileIdAndUserId(fileId, userId)) {
                    String fileName = getFileNameSafely(fileId);
                    failures.add(BulkCopyResponse.FailedOperation.builder()
                            .sourceFileId(fileId)
                            .fileName(fileName)
                            .reason("Access denied: You don't have permission to copy this file")
                            .build());

                    log.warn("Permission denied for copying file {} by user {}", fileId, userId);
                    continue;
                }

                // Verify file exists and is not deleted
                FileEntity file = fileRepository.findById(fileId).orElse(null);
                if (file == null) {
                    failures.add(BulkCopyResponse.FailedOperation.builder()
                            .sourceFileId(fileId)
                            .fileName("Unknown file")
                            .reason("File not found")
                            .build());
                    continue;
                }

                if (file.getIsDeleted()) {
                    failures.add(BulkCopyResponse.FailedOperation.builder()
                            .sourceFileId(fileId)
                            .fileName(file.getFileName())
                            .reason("Cannot copy deleted file")
                            .build());
                    continue;
                }

                validFileIds.add(fileId);

            } catch (Exception e) {
                String fileName = getFileNameSafely(fileId);
                failures.add(BulkCopyResponse.FailedOperation.builder()
                        .sourceFileId(fileId)
                        .fileName(fileName)
                        .reason("Validation error: " + e.getMessage())
                        .build());

                log.error("Error validating file {} for copy by user {}: {}", fileId, userId, e.getMessage());
            }
        }

        return validFileIds;
    }

    private List<UUID> validateFilePermissionsForMove(List<UUID> fileIds, UUID userId, UUID destinationFolderId,
                                                      List<BulkMoveResponse.FailedOperation> failures) {

        List<UUID> validFileIds = new ArrayList<>();

        for (UUID fileId : fileIds) {
            try {
                // Check if file exists and user has permission
                if (!fileRepository.existsByFileIdAndUserId(fileId, userId)) {
                    String fileName = getFileNameSafely(fileId);
                    failures.add(BulkMoveResponse.FailedOperation.builder()
                            .fileId(fileId)
                            .fileName(fileName)
                            .reason("Access denied: You don't have permission to move this file")
                            .build());

                    log.warn("Permission denied for moving file {} by user {}", fileId, userId);
                    continue;
                }

                // Verify file exists and is not deleted
                FileEntity file = fileRepository.findById(fileId).orElse(null);
                if (file == null) {
                    failures.add(BulkMoveResponse.FailedOperation.builder()
                            .fileId(fileId)
                            .fileName("Unknown file")
                            .reason("File not found")
                            .build());
                    continue;
                }

                if (file.getIsDeleted()) {
                    failures.add(BulkMoveResponse.FailedOperation.builder()
                            .fileId(fileId)
                            .fileName(file.getFileName())
                            .reason("Cannot move deleted file")
                            .build());
                    continue;
                }

                // Check if file is already in destination folder
                if (isFileAlreadyInFolder(file, destinationFolderId)) {
                    failures.add(BulkMoveResponse.FailedOperation.builder()
                            .fileId(fileId)
                            .fileName(file.getFileName())
                            .reason("File is already in the destination folder")
                            .build());
                    continue;
                }

                validFileIds.add(fileId);

            } catch (Exception e) {
                String fileName = getFileNameSafely(fileId);
                failures.add(BulkMoveResponse.FailedOperation.builder()
                        .fileId(fileId)
                        .fileName(fileName)
                        .reason("Validation error: " + e.getMessage())
                        .build());

                log.error("Error validating file {} for move by user {}: {}", fileId, userId, e.getMessage());
            }
        }

        return validFileIds;
    }


    private FileUploadResponse copyFileInternal(UUID fileId, UUID destinationFolderId, UUID userId) throws ItemNotFoundException {

        // Get and validate source file
        FileEntity sourceFile = fileRepository.findById(fileId)
                .orElseThrow(() -> new ItemNotFoundException("File not found: " + fileId));

        // CRITICAL: Verify user ownership/permission
        if (!sourceFile.getUserId().equals(userId)) {
            throw new SecurityException("Access denied: You don't have permission to copy this file");
        }

        if (sourceFile.getIsDeleted()) {
            throw new RuntimeException("Cannot copy deleted file");
        }

        // Additional permission check for extra security
        if (!fileRepository.existsByFileIdAndUserId(fileId, userId)) {
            throw new SecurityException("Permission verification failed: File doesn't belong to user");
        }

        // Get and validate destination folder
        FolderEntity destinationFolder = validateDestinationFolder(destinationFolderId, userId);
        String destinationFolderPath = destinationFolder != null ? destinationFolder.getFullPath() : "";

        // Handle name conflicts and get final filename
        String finalFileName = handleCopyNameConflict(userId, sourceFile.getFileName(), destinationFolderId);

        // Copy file in MinIO
        String sourceMinioKey = sourceFile.getMinioKey();
        String newMinioKey = generateNewMinioKey(destinationFolderPath, finalFileName);

        minioService.copyFile(userId, sourceMinioKey, newMinioKey);

        // Create new database record
        FileEntity newFile = createFileEntityCopy(sourceFile, destinationFolder, finalFileName, newMinioKey);
        FileEntity savedFile = fileRepository.save(newFile);

        log.debug("File {} successfully copied by user {} to {}", fileId, userId, destinationFolderPath);

        // Build response
        return FileUploadResponse.builder()
                .fileId(savedFile.getFileId())
                .fileName(savedFile.getFileName())
                .folderId(destinationFolderId)
                .folderPath(destinationFolderPath)
                .fileSize(savedFile.getFileSize())
                .mimeType(savedFile.getMimeType())
                .scanStatus(savedFile.getScanStatus())
                .uploadedAt(savedFile.getCreatedAt())
                .build();
    }

    private void moveFileInternal(UUID fileId, UUID destinationFolderId, UUID userId) throws ItemNotFoundException {

        // Get and validate source file
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new ItemNotFoundException("File not found: " + fileId));

        // CRITICAL: Verify user ownership/permission
        if (!file.getUserId().equals(userId)) {
            throw new SecurityException("Access denied: You don't have permission to move this file");
        }

        if (file.getIsDeleted()) {
            throw new RuntimeException("Cannot move deleted file");
        }

        // Additional permission check for extra security
        if (!fileRepository.existsByFileIdAndUserId(fileId, userId)) {
            throw new SecurityException("Permission verification failed: File doesn't belong to user");
        }

        // Get and validate destination folder
        FolderEntity destinationFolder = validateDestinationFolder(destinationFolderId, userId);
        String destinationFolderPath = destinationFolder != null ? destinationFolder.getFullPath() : "";

        // Check if file is already in destination folder
        if (isFileAlreadyInFolder(file, destinationFolderId)) {
            throw new RuntimeException("File is already in the destination folder");
        }

        // Handle name conflicts and get final filename
        String finalFileName = handleMoveNameConflict(userId, file.getFileName(), destinationFolderId);

        // Move file in MinIO
        String oldMinioKey = file.getMinioKey();
        String newMinioKey = generateNewMinioKey(destinationFolderPath, finalFileName);

        minioService.renameFile(userId, oldMinioKey, newMinioKey);

        // Update database
        file.setFolder(destinationFolder);
        file.setFileName(finalFileName);
        file.setMinioKey(newMinioKey);
        fileRepository.save(file);

        log.debug("File {} successfully moved by user {} to {}", fileId, userId, destinationFolderPath);
    }

    private String buildCopySummary(int successful, int failed, int total, String destinationPath) {
        if (failed == 0) {
            return String.format("Successfully copied %d file(s) to %s", successful, destinationPath);
        } else if (successful == 0) {
            return String.format("Failed to copy all %d file(s)", total);
        } else {
            return String.format("Copied %d file(s) to %s, %d failed", successful, destinationPath, failed);
        }
    }

    /**
     * Build summary message for move results
     */
    private String buildMoveSummary(int successful, int failed, int total, String destinationPath) {
        if (failed == 0) {
            return String.format("Successfully moved %d file(s) to %s", successful, destinationPath);
        } else if (successful == 0) {
            return String.format("Failed to move all %d file(s)", total);
        } else {
            return String.format("Moved %d file(s) to %s, %d failed", successful, destinationPath, failed);
        }
    }

    private List<FileEntity> getFilesToPermanentlyDelete(UUID userId, List<UUID> specificFileIds) {

        if (specificFileIds != null && !specificFileIds.isEmpty()) {
            // Delete specific files - but only if they're already in trash
            return specificFileIds.stream()
                    .map(fileId -> fileRepository.findById(fileId).orElse(null))
                    .filter(file -> file != null &&
                            file.getUserId().equals(userId) &&
                            file.getIsDeleted())
                    .collect(Collectors.toList());
        } else {
            // Delete all trash files for user
            return fileRepository.findByUserIdAndIsDeletedTrue(userId);
        }
    }

    private List<FileEntity> validateTrashFilePermissions(List<FileEntity> filesToDelete, UUID userId,
                                                          List<EmptyTrashResponse.FailedDeletion> failures) {

        List<FileEntity> validFiles = new ArrayList<>();

        for (FileEntity file : filesToDelete) {
            try {
                // CRITICAL: Verify user ownership
                if (!file.getUserId().equals(userId)) {
                    failures.add(EmptyTrashResponse.FailedDeletion.builder()
                            .fileId(file.getFileId())
                            .fileName(file.getFileName())
                            .minioKey(file.getMinioKey())
                            .reason("Access denied: You don't have permission to permanently delete this file")
                            .build());

                    log.warn("Permission denied for permanent deletion of file {} by user {}",
                            file.getFileId(), userId);
                    continue;
                }

                // Verify file is actually in trash
                if (!file.getIsDeleted()) {
                    failures.add(EmptyTrashResponse.FailedDeletion.builder()
                            .fileId(file.getFileId())
                            .fileName(file.getFileName())
                            .minioKey(file.getMinioKey())
                            .reason("File is not in trash - move to trash first")
                            .build());
                    continue;
                }

                // Additional repository-level permission check
                if (!fileRepository.existsByFileIdAndUserId(file.getFileId(), userId)) {
                    failures.add(EmptyTrashResponse.FailedDeletion.builder()
                            .fileId(file.getFileId())
                            .fileName(file.getFileName())
                            .minioKey(file.getMinioKey())
                            .reason("Permission verification failed")
                            .build());
                    continue;
                }

                validFiles.add(file);

            } catch (Exception e) {
                failures.add(EmptyTrashResponse.FailedDeletion.builder()
                        .fileId(file.getFileId())
                        .fileName(file.getFileName())
                        .minioKey(file.getMinioKey())
                        .reason("Validation error: " + e.getMessage())
                        .build());

                log.error("Error validating file {} for permanent deletion by user {}: {}",
                        file.getFileId(), userId, e.getMessage());
            }
        }

        return validFiles;
    }
    private void permanentlyDeleteFileFromStorage(FileEntity file, UUID userId) {

        try {
            // Delete from MinIO using the trash key
            minioService.deleteFile(userId, file.getMinioKey());

            log.debug("File {} permanently deleted from MinIO storage", file.getFileId());

        } catch (Exception e) {
            log.error("Failed to delete file {} from MinIO: {}", file.getFileId(), e.getMessage());
            throw new RuntimeException("Failed to delete file from storage: " + e.getMessage(), e);
        }
    }

    /**
     * Permanently delete file record from database
     */
    private void permanentlyDeleteFileFromDatabase(FileEntity file) {

        try {
            // Hard delete from database
            fileRepository.delete(file);

            log.debug("File {} permanently deleted from database", file.getFileId());

        } catch (Exception e) {
            log.error("Failed to delete file {} from database: {}", file.getFileId(), e.getMessage());
            throw new RuntimeException("Failed to delete file from database: " + e.getMessage(), e);
        }
    }

    /**
     * Build summary message for empty trash results
     */
    private String buildEmptyTrashSummary(int successful, int failed, int total) {
        if (failed == 0) {
            if (successful == 0) {
                return "No files were found in trash to delete";
            }
            return String.format("Successfully permanently deleted %d file(s) from trash", successful);
        } else if (successful == 0) {
            return String.format("Failed to permanently delete all %d file(s)", total);
        } else {
            return String.format("Permanently deleted %d file(s), %d failed", successful, failed);
        }
    }


    private TrashResponse.TrashFileItem convertToTrashFileItem(FileEntity file) {
        // Get original folder path (before deletion)
        String originalFolderPath = "Root"; // Default
        if (file.getFolder() != null) {
            originalFolderPath = file.getFolder().getFullPath();
        }

        return TrashResponse.TrashFileItem.builder()
                .id(file.getFileId())
                .name(file.getFileName())
                .size(file.getFileSize())
                .sizeFormatted(formatFileSize(file.getFileSize()))
                .mimeType(file.getMimeType())
                .extension(getFileExtension(file.getFileName()))
                .category(getFileCategory(file.getMimeType()))
                .scanStatus(file.getScanStatus().toString())
                .originalFolderPath(originalFolderPath)
                .deletedAt(file.getDeletedAt())
                .originalCreatedAt(file.getCreatedAt())
                .canRestore(true) // All files in trash can be restored
                .build();
    }

    private TrashResponse.TrashSummary buildTrashSummary(List<FileEntity> trashFiles) {
        if (trashFiles.isEmpty()) {
            return TrashResponse.TrashSummary.builder()
                    .totalTrashFiles(0)
                    .totalTrashSize(0L)
                    .totalTrashSizeFormatted("0 B")
                    .oldestDeletedAt(null)
                    .newestDeletedAt(null)
                    .build();
        }

        long totalSize = trashFiles.stream().mapToLong(FileEntity::getFileSize).sum();

        LocalDateTime oldest = trashFiles.stream()
                .map(file -> file.getDeletedAt() != null ? file.getDeletedAt() : file.getUpdatedAt())
                .min(LocalDateTime::compareTo)
                .orElse(null);

        LocalDateTime newest = trashFiles.stream()
                .map(file -> file.getDeletedAt() != null ? file.getDeletedAt() : file.getUpdatedAt())
                .max(LocalDateTime::compareTo)
                .orElse(null);

        return TrashResponse.TrashSummary.builder()
                .totalTrashFiles(trashFiles.size())
                .totalTrashSize(totalSize)
                .totalTrashSizeFormatted(formatFileSize(totalSize))
                .oldestDeletedAt(oldest)
                .newestDeletedAt(newest)
                .build();
    }

    private TrashResponse.PaginationInfo buildTrashPaginationInfo(Page<FileEntity> page) {
        return TrashResponse.PaginationInfo.builder()
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .isFirst(page.isFirst())
                .isLast(page.isLast())
                .build();
    }

    private Page<FileEntity> applyPaginationToTrashFiles(List<FileEntity> trashFiles, Pageable pageable) {
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), trashFiles.size());

        List<FileEntity> pagedFiles = trashFiles.subList(start, end);
        return new PageImpl<>(pagedFiles, pageable, trashFiles.size());
    }

    // Helper methods for bulk restore:

    private List<UUID> validateFilePermissionsForRestore(List<UUID> fileIds, UUID userId,
                                                         List<BulkRestoreResponse.FailedRestore> failures) {

        List<UUID> validFileIds = new ArrayList<>();

        for (UUID fileId : fileIds) {
            try {
                // Check if file exists and user has permission
                if (!fileRepository.existsByFileIdAndUserId(fileId, userId)) {
                    String fileName = getFileNameSafely(fileId);
                    failures.add(BulkRestoreResponse.FailedRestore.builder()
                            .fileId(fileId)
                            .fileName(fileName)
                            .reason("Access denied: You don't have permission to restore this file")
                            .build());

                    log.warn("Permission denied for restoring file {} by user {}", fileId, userId);
                    continue;
                }

                // Verify file exists and is actually deleted
                FileEntity file = fileRepository.findById(fileId).orElse(null);
                if (file == null) {
                    failures.add(BulkRestoreResponse.FailedRestore.builder()
                            .fileId(fileId)
                            .fileName("Unknown file")
                            .reason("File not found")
                            .build());
                    continue;
                }

                if (!file.getIsDeleted()) {
                    failures.add(BulkRestoreResponse.FailedRestore.builder()
                            .fileId(fileId)
                            .fileName(file.getFileName())
                            .reason("File is not in trash")
                            .build());
                    continue;
                }

                validFileIds.add(fileId);

            } catch (Exception e) {
                String fileName = getFileNameSafely(fileId);
                failures.add(BulkRestoreResponse.FailedRestore.builder()
                        .fileId(fileId)
                        .fileName(fileName)
                        .reason("Validation error: " + e.getMessage())
                        .build());

                log.error("Error validating file {} for restore by user {}: {}", fileId, userId, e.getMessage());
            }
        }

        return validFileIds;
    }

    private void restoreFileInternal(UUID fileId, UUID userId) throws ItemNotFoundException {

        // Get deleted file and validate ownership
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new ItemNotFoundException("File not found: " + fileId));

        // CRITICAL: Verify user ownership
        if (!file.getUserId().equals(userId)) {
            throw new SecurityException("Access denied: You don't have permission to restore this file");
        }

        if (!file.getIsDeleted()) {
            throw new RuntimeException("File is not in trash");
        }

        // Additional permission check for extra security
        if (!fileRepository.existsByFileIdAndUserId(fileId, userId)) {
            throw new SecurityException("Permission verification failed: File doesn't belong to user");
        }

        // Extract original key from trash key
        String trashKey = file.getMinioKey();
        String originalKey = extractOriginalKeyFromTrash(trashKey);

        // Handle potential name conflicts in restore location
        String finalFileName = handleRestoreNameConflict(userId, file, originalKey);

        // Rename in MinIO back to original location
        minioService.renameFile(userId, trashKey, originalKey);

        // Update database
        file.setMinioKey(originalKey);
        file.setIsDeleted(false);
        file.setDeletedAt(null);

        // Update filename if there was a conflict
        if (!finalFileName.equals(file.getFileName())) {
            file.setFileName(finalFileName);
            log.debug("File renamed during restore: {} -> {}", file.getFileName(), finalFileName);
        }

        fileRepository.save(file);

        log.debug("File {} successfully restored by user {}", fileId, userId);
    }

    private String buildRestoreSummary(int successful, int failed, int total) {
        if (failed == 0) {
            return String.format("Successfully restored %d file(s) from trash", successful);
        } else if (successful == 0) {
            return String.format("Failed to restore all %d file(s)", total);
        } else {
            return String.format("Restored %d file(s) from trash, %d failed", successful, failed);
        }
    }

    // Helper method 1: Input validation
    private void validateBatchUploadInputs(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new RuntimeException("No files provided");
        }

        if (files.size() > 50) {
            throw new RuntimeException("Too many files. Maximum 50 files per batch.");
        }
    }

    // Helper method 2: Folder validation
    private String validateAndGetFolderPath(UUID folderId, UUID userId) throws ItemNotFoundException {
        if (folderId == null) {
            return ""; // Root folder
        }

        FolderEntity folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ItemNotFoundException("Folder not found"));

        if (!folder.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied: You don't own this folder");
        }

        return folder.getFullPath();
    }
    // Helper method 1: Validate inputs
    private void validateSyncBatchInputs(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new RuntimeException("No files provided");
        }

        if (files.size() > 50) {
            throw new RuntimeException("Too many files. Maximum 50 files per batch.");
        }

        // Validate each file
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                throw new RuntimeException("One or more files are empty");
            }
            if (file.getOriginalFilename() == null || file.getOriginalFilename().trim().isEmpty()) {
                throw new RuntimeException("One or more files have invalid names");
            }
        }
    }

    // Helper method 2: Validate folder
    private FolderEntity validateSyncBatchFolder(UUID folderId, UUID userId) throws ItemNotFoundException {
        if (folderId == null) {
            return null; // Root folder
        }

        FolderEntity folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ItemNotFoundException("Folder not found"));

        if (!folder.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied: You don't own this folder");
        }

        return folder;
    }

    // Helper method 3: Process all files
    private void processSyncBatchFiles(UUID folderId, List<MultipartFile> files, BatchUploadOptions options,
                                       UUID userId, String folderPath, FolderEntity targetFolder,
                                       List<FileUploadResponse> uploadedFiles,
                                       List<BatchUploadSyncResponse.FailedUpload> failures) {

        for (MultipartFile file : files) {
            boolean success = processSyncSingleFile(file, folderId, options, userId, folderPath,
                    targetFolder, uploadedFiles, failures);

            // Stop on first error if configured
            if (!success && options.isStopOnFirstError()) {
                log.info("Stopping batch upload due to error and stopOnFirstError=true");
                break;
            }
        }
    }

    // Helper method 4: Process single file (self-contained upload logic)
    private boolean processSyncSingleFile(MultipartFile file, UUID folderId, BatchUploadOptions options,
                                          UUID userId, String folderPath, FolderEntity targetFolder,
                                          List<FileUploadResponse> uploadedFiles,
                                          List<BatchUploadSyncResponse.FailedUpload> failures) {
        try {
            String fileName = file.getOriginalFilename();

            // Basic file validation
            validateFile(file);

            // Check for duplicates if not allowed
            if (!options.isAllowDuplicates()) {
                if (fileExistsInLocation(userId, fileName, folderId)) {
                    failures.add(BatchUploadSyncResponse.FailedUpload.builder()
                            .fileName(fileName)
                            .fileSize(file.getSize())
                            .reason("File already exists and duplicates not allowed")
                            .build());
                    return false;
                }
            }

            // Handle duplicate filename by renaming
            String finalFileName = handleDuplicateFileName(userId, fileName, folderId);

            // Perform virus scan
            VirusScanStatus scanStatus = performVirusScan(file);

            // Upload to MinIO storage
            String minioKey = minioService.uploadFile(userId, folderPath, finalFileName, file);
            if (minioKey == null || minioKey.trim().isEmpty()) {
                throw new RuntimeException("Storage upload failed");
            }

            // Save to database
            FileEntity fileEntity = new FileEntity();
            fileEntity.setFileName(finalFileName);
            fileEntity.setUserId(userId);
            fileEntity.setFolder(targetFolder);
            fileEntity.setFileSize(file.getSize());
            fileEntity.setMimeType(file.getContentType());
            fileEntity.setScanStatus(scanStatus);
            fileEntity.setMinioKey(minioKey);

            FileEntity savedFile = fileRepository.save(fileEntity);

            // Build response
            FileUploadResponse uploadResponse = FileUploadResponse.builder()
                    .fileId(savedFile.getFileId())
                    .fileName(savedFile.getFileName())
                    .folderId(folderId)
                    .folderPath(folderPath)
                    .fileSize(savedFile.getFileSize())
                    .mimeType(savedFile.getMimeType())
                    .scanStatus(savedFile.getScanStatus())
                    .uploadedAt(savedFile.getCreatedAt())
                    .build();

            uploadedFiles.add(uploadResponse);
            log.debug("Successfully uploaded file: {}", fileName);
            return true;

        } catch (Exception e) {
            failures.add(BatchUploadSyncResponse.FailedUpload.builder()
                    .fileName(file.getOriginalFilename())
                    .fileSize(file.getSize())
                    .reason(e.getMessage())
                    .build());

            log.warn("Failed to upload file {}: {}", file.getOriginalFilename(), e.getMessage());
            return false;
        }
    }

    // Helper method 5: Build response
    private BatchUploadSyncResponse buildSyncBatchResponse(int totalRequested,
                                                           List<FileUploadResponse> uploadedFiles,
                                                           List<BatchUploadSyncResponse.FailedUpload> failures,
                                                           String userName) {

        int successfulUploads = uploadedFiles.size();
        int failedUploads = failures.size();

        // Build summary message
        String summary;
        if (failedUploads == 0) {
            summary = String.format("Successfully uploaded %d file(s)", successfulUploads);
        } else if (successfulUploads == 0) {
            summary = String.format("Failed to upload all %d file(s)", totalRequested);
        } else {
            summary = String.format("Uploaded %d file(s), %d failed", successfulUploads, failedUploads);
        }

        log.info("Synchronous batch upload completed for user: {} - Success: {}, Failed: {}",
                userName, successfulUploads, failedUploads);

        return BatchUploadSyncResponse.builder()
                .totalFilesRequested(totalRequested)
                .successfulUploads(successfulUploads)
                .failedUploads(failedUploads)
                .uploadedFiles(uploadedFiles)
                .failures(failures)
                .summary(summary)
                .build();
    }

}