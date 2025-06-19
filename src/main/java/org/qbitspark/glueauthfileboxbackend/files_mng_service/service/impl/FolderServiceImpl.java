package org.qbitspark.glueauthfileboxbackend.files_mng_service.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.AccountRepo;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.AccountEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.entity.FolderEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.CreateFolderRequest;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.CreateFolderResponse;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.FolderListResponse;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.repo.FolderRepository;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.service.FolderService;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.qbitspark.glueauthfileboxbackend.minio_service.service.MinioService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FolderServiceImpl implements FolderService {

    private final AccountRepo accountRepo;
    private final FolderRepository folderRepository;
    private final MinioService minioService;

    @Override
    @Transactional
    public CreateFolderResponse createFolder(CreateFolderRequest createFolderRequest) throws ItemNotFoundException {
        // 1. Get an authenticated user
        AccountEntity authenticatedUser = getAuthenticatedAccount();
        UUID userId = authenticatedUser.getId();

        // 2. Ensure a user's MinIO bucket exists
        try {
            if (!minioService.bucketExists(userId)) {
                log.info("Creating MinIO bucket for user: {}", userId);
                minioService.createUserBucket(userId);
            }
        } catch (Exception e) {
            log.error("Failed to create/check MinIO bucket for user: {}", userId, e);
            throw new RuntimeException("Storage initialization failed. Please try again.");
        }

        // 3. Validate parent folder if provided
        FolderEntity parentFolder = null;
        if (createFolderRequest.getParentFolderId() != null) {
            parentFolder = folderRepository.findById(createFolderRequest.getParentFolderId())
                    .orElseThrow(() -> new ItemNotFoundException("Parent folder not found"));

            // Verify user owns parent folder
            if (!parentFolder.getUserId().equals(userId)) {
                throw new RuntimeException("Access denied: You don't own this parent folder");
            }
        }

        // 4. Check for duplicate folder name in the same location
        boolean folderExists;
        if (parentFolder == null) {
            // Check for duplicate in the root level
            folderExists = folderRepository.findByUserIdAndFolderNameAndParentFolderIsNull(
                    userId, createFolderRequest.getFolderName()).isPresent();
        } else {
            // Check for duplicate in a specific parent folder
            folderExists = folderRepository.findByUserIdAndFolderNameAndParentFolder_FolderId(
                    userId, createFolderRequest.getFolderName(), parentFolder.getFolderId()).isPresent();
        }

        if (folderExists) {
            throw new RuntimeException("Folder with this name already exists in this location");
        }

        // 5. Create a new folder entity
        FolderEntity newFolder = new FolderEntity();
        newFolder.setFolderName(createFolderRequest.getFolderName());
        newFolder.setUserId(userId);
        newFolder.setParentFolder(parentFolder);
        newFolder.setCreatedAt(LocalDateTime.now());

        // 6. Save to a database first
        FolderEntity savedFolder = folderRepository.save(newFolder);

        // 7. Create a folder structure in MinIO (optional - for consistency)
        try {
            String folderPath = savedFolder.getFullPath();
            log.info("Creating folder structure in MinIO: {} for user: {}", folderPath, userId);
            minioService.createFolderStructure(userId, folderPath);
        } catch (Exception e) {
            log.warn("Failed to create folder structure in MinIO, but database folder created: {}", e.getMessage());
            // Don't fail the entire operation - folder exists in database
            // MinIO structure will be created when files are uploaded
        }

        // 8. Build and return response
        return CreateFolderResponse.builder()
                .folderId(savedFolder.getFolderId())
                .folderName(savedFolder.getFolderName())
                .parentFolderId(parentFolder != null ? parentFolder.getFolderId() : null)
                .fullPath(savedFolder.getFullPath())
                .createdAt(savedFolder.getCreatedAt())
                .build();
    }


    @Override
    public List<FolderListResponse> getRootFolders() throws ItemNotFoundException {

        AccountEntity authenticatedUser = getAuthenticatedAccount();
        UUID userId = authenticatedUser.getId();

        List<FolderEntity> rootFolders = folderRepository.findByUserIdAndParentFolderIsNull(userId);

        return rootFolders.stream()
                .map(this::convertToFolderListResponse)
                .toList();
    }

    @Override
    public List<FolderListResponse> getSubFolders(UUID parentFolderId) throws ItemNotFoundException {

        AccountEntity authenticatedUser = getAuthenticatedAccount();
        UUID userId = authenticatedUser.getId();

        // Verify parent folder exists and user owns it
        FolderEntity parentFolder = folderRepository.findById(parentFolderId)
                .orElseThrow(() -> new ItemNotFoundException("Parent folder not found"));

        if (!parentFolder.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied: You don't own this folder");
        }

        // Get subfolders
        List<FolderEntity> subFolders = folderRepository.findByUserIdAndParentFolder_FolderId(userId, parentFolderId);

        // Convert to response DTOs
        return subFolders.stream()
                .map(this::convertToFolderListResponse)
                .toList();
    }

    private AccountEntity getAuthenticatedAccount() throws ItemNotFoundException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return extractAccount(authentication);
    }

    // Helper method to convert entity to response
    private FolderListResponse convertToFolderListResponse(FolderEntity folder) {
        // Check if the folder has subfolders
        boolean hasSubFolders = !folder.getSubFolders().isEmpty();

        // Count files in a folder
        int fileCount = folder.getFiles().size();

        return FolderListResponse.builder()
                .folderId(folder.getFolderId())
                .folderName(folder.getFolderName())
                .parentFolderId(folder.getParentFolder() != null ? folder.getParentFolder().getFolderId() : null)
                .fullPath(folder.getFullPath())
                .createdAt(folder.getCreatedAt())
                .hasSubFolders(hasSubFolders)
                .fileCount(fileCount)
                .build();
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