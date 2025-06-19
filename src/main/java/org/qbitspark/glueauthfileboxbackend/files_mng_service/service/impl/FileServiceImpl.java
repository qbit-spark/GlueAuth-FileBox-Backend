package org.qbitspark.glueauthfileboxbackend.files_mng_service.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.AccountRepo;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.AccountEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.entity.FileEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.entity.FolderEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.enums.VirusScanStatus;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.FileMetadata;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.FileUploadResponse;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.repo.FileRepository;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.repo.FolderRepository;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.service.FileService;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.VirusScanException;
import org.qbitspark.glueauthfileboxbackend.minio_service.service.MinioService;
import org.qbitspark.glueauthfileboxbackend.virus_scanner_service.config.VirusScanConfig;
import org.qbitspark.glueauthfileboxbackend.virus_scanner_service.payload.VirusScanResult;
import org.qbitspark.glueauthfileboxbackend.virus_scanner_service.service.VirusScanService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

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

    // Extract virus scanning logic

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
}