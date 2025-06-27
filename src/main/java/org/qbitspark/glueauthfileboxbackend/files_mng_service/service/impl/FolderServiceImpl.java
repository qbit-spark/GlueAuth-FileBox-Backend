package org.qbitspark.glueauthfileboxbackend.files_mng_service.service.impl;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.AccountRepo;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.AccountEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.entity.FileEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.entity.FolderEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.*;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.repo.FileRepository;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.repo.FolderRepository;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.service.FolderService;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.qbitspark.glueauthfileboxbackend.minio_service.service.MinioService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

import static com.google.common.io.Files.getFileExtension;

@Service
@RequiredArgsConstructor
@Slf4j
public class FolderServiceImpl implements FolderService {

    private final AccountRepo accountRepo;
    private final FolderRepository folderRepository;
    private final MinioService minioService;
    private final FileRepository fileRepository;

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
    @Transactional
    public CreateFolderByPathResponse createFolderByPath(CreateFolderByPathRequest request) throws ItemNotFoundException {

        AccountEntity authenticatedUser = getAuthenticatedAccount();
        UUID userId = authenticatedUser.getId();

        log.info("Creating folder path: '{}' with parent: {} for user: {}",
                request.getPath(), request.getParentFolderId(), authenticatedUser.getUserName());


        String cleanPath = validateAndPreparePath(request.getPath());
        String[] pathSegments = cleanPath.split("/");

        // Validate path depth (max 10 levels)
        if (pathSegments.length > 10) {
            throw new RuntimeException("Path depth exceeds maximum allowed limit of 10 levels");
        }

        // Validate parent folder if provided
        FolderEntity currentParent = null;
        if (request.getParentFolderId() != null) {
            currentParent = folderRepository.findById(request.getParentFolderId())
                    .orElseThrow(() -> new ItemNotFoundException("Parent folder not found"));

            if (!currentParent.getUserId().equals(userId)) {
                throw new RuntimeException("Access denied: You don't own the parent folder");
            }
        }


        try {
            if (!minioService.bucketExists(userId)) {
                log.info("Creating MinIO bucket for user: {}", userId);
                minioService.createUserBucket(userId);
            }
        } catch (Exception e) {
            log.error("Failed to create/check MinIO bucket for user: {}", userId, e);
            throw new RuntimeException("Storage initialization failed. Please try again.");
        }

        // Track created folders for potential rollback
        List<String> createdFolders = new ArrayList<>();
        List<String> existingFolders = new ArrayList<>();
        List<FolderEntity> createdFolderEntities = new ArrayList<>();
        List<String> minioPathsCreated = new ArrayList<>();

        try {
            // Process each path segment
            FolderEntity finalFolder = processPathSegments(
                    pathSegments, currentParent, userId,
                    createdFolders, existingFolders,
                    createdFolderEntities, minioPathsCreated
            );

            // Build response
            String fullPath = buildFullPath(finalFolder);
            String summary = buildSummary(createdFolders.size(), existingFolders.size(), pathSegments.length);

            log.info("Folder path created successfully: '{}' - {} new folders created",
                    fullPath, createdFolders.size());

            return CreateFolderByPathResponse.builder()
                    .finalFolderId(finalFolder.getFolderId())
                    .fullPath(fullPath)
                    .createdFolders(createdFolders)
                    .existingFolders(existingFolders)
                    .totalCreated(createdFolders.size())
                    .finalFolderName(finalFolder.getFolderName())
                    .createdAt(finalFolder.getCreatedAt())
                    .summary(summary)
                    .build();

        } catch (Exception e) {
            // Rollback: Clean up created MinIO folder structures
            rollbackMinioFolders(userId, minioPathsCreated);

            // Database rollback will be handled by @Transactional annotation
            log.error("Folder path creation failed, rollback initiated: {}", e.getMessage());
            throw new RuntimeException("Failed to create folder path: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public BatchCreateFoldersResponse createMultipleFolders(BatchCreateFoldersRequest request) throws ItemNotFoundException {

        // Get authenticated user
        AccountEntity authenticatedUser = getAuthenticatedAccount();
        UUID userId = authenticatedUser.getId();

        log.info("Creating {} folders with parent: {} for user: {}",
                request.getDistinctFolderNames().size(), request.getParentFolderId(),
                authenticatedUser.getUserName());

        // Step 1: Validate request
        validateBatchCreateRequest(request);

        // Step 2: Validate and get parent folder
        FolderEntity parentFolder = validateParentFolder(request.getParentFolderId(), userId);
        String parentPath = parentFolder != null ? parentFolder.getFullPath() : "";

        // Step 3: Ensure MinIO bucket exists
        ensureMinIOBucketExists(userId);

        // Step 4: Process folder creation with rollback support
        return processBatchFolderCreation(request, userId, parentFolder, parentPath);
    }

    @Override
    @Transactional
    public BatchCreateFoldersAtPathResponse batchCreateFoldersAtPath(BatchCreateFoldersAtPathRequest request) throws ItemNotFoundException {


        AccountEntity authenticatedUser = getAuthenticatedAccount();
        UUID userId = authenticatedUser.getId();

        log.info("Creating {} folders at path: '{}' with parent: {} for user: {}",
                request.getFolderNames().size(), request.getTargetPath(),
                request.getParentId(), authenticatedUser.getUserName());

        // Step 1: Validate request
        validateBatchAtPathRequest(request);

        // Step 2: Navigate to target location
        PathNavigationResult navigationResult = navigateToTargetLocation(request, userId);

        // Step 3: Ensure MinIO bucket exists
        ensureMinIOBucketExists(userId);

        // Step 4: Create folders at target location
        BatchCreateFoldersAtPathResponse.BatchResults batchResults = createFoldersAtTargetLocation(
                request.getFolderNames(), navigationResult.getTargetFolder(), userId);

        // Step 5: Build comprehensive response
        return buildBatchAtPathResponse(request, navigationResult, batchResults);
    }


    @Override
    @Transactional(readOnly = true)
    public GetFolderByPathResponse getFolderByPath(GetFolderByPathRequest request) throws ItemNotFoundException {

        AccountEntity authenticatedUser = getAuthenticatedAccount();
        UUID userId = authenticatedUser.getId();

        log.info("Getting folder by path: '{}' with parent: {} for user: {}",
                request.getPath(), request.getParentId(), authenticatedUser.getUserName());

        // Step 1: Validate and prepare path
        String cleanPath = validateAndPrepareFolderPath(request.getPath());
        String[] pathSegments = cleanPath.split("/");

        // Step 2: Determine and validate starting point
        FolderEntity startingFolder = determineStartingPoint(request.getParentId(), userId);

        // Step 3: Navigate through path segments
        PathNavigationResult navigationResult = navigateToTargetFolder(
                pathSegments, startingFolder, userId);

        // Step 4: Get folder contents if requested
        GetFolderByPathResponse.FolderContents contents = null;
        if (request.isIncludeContents()) {
            contents = getFolderContentsForPath(navigationResult.getTargetFolder(), userId);
        }

        // Step 5: Build comprehensive response
        return buildPathResponse(request, startingFolder, navigationResult, contents);
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

    @Override
    public FolderContentsResponse getFolderContents(UUID folderId, Pageable pageable) throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();
        FolderEntity folder = validateAndGetFolder(folderId, user.getId());
        boolean isRoot = (folder == null);

        // Get all items (folders + files) with pagination
        List<Object> allItems = getAllItemsSorted(user.getId(), folderId, isRoot);

        // Apply pagination manually (since we're combining two entity types)
        Page<Object> pagedItems = applyPagination(allItems, pageable);

        // Separate folders and files from paged results
        List<FolderContentsResponse.FolderItem> folders = new ArrayList<>();
        List<FolderContentsResponse.FileItem> files = new ArrayList<>();

        for (Object item : pagedItems.getContent()) {
            if (item instanceof FolderEntity) {
                folders.add(convertToFolderItem((FolderEntity) item));
            } else if (item instanceof FileEntity) {
                files.add(convertToFileItem((FileEntity) item));
            }
        }

        // Build response
        return FolderContentsResponse.builder()
                .folder(buildFolderInfo(folder, isRoot))
                .statistics(buildStatistics(user.getId(), folderId, isRoot, folders.size(), files.size()))
                .contents(FolderContentsResponse.Contents.builder()
                        .folders(folders)
                        .files(files)
                        .build())
                .pagination(buildPaginationInfo(pagedItems))
                .build();
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

    private FolderEntity validateAndGetFolder(UUID folderId, UUID userId) throws ItemNotFoundException {
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

    private List<Object> getAllItemsSorted(UUID userId, UUID folderId, boolean isRoot) {
        List<Object> allItems = new ArrayList<>();

        // Get active folders only
        List<FolderEntity> folders;
        if (isRoot) {
            folders = folderRepository.findByUserIdAndParentFolderIsNull(userId);
        } else {
            folders = folderRepository.findByUserIdAndParentFolder_FolderId(userId, folderId);
        }
        allItems.addAll(folders);

        // Get active files only (exclude deleted)
        List<FileEntity> files;
        if (isRoot) {
            files = fileRepository.findByUserIdAndFolderIsNullAndIsDeletedFalse(userId);
        } else {
            files = fileRepository.findByUserIdAndFolder_FolderIdAndIsDeletedFalse(userId, folderId);
        }
        allItems.addAll(files);

        // Sort: folders first, then files, then by name
        allItems.sort((a, b) -> {
            boolean aIsFolder = a instanceof FolderEntity;
            boolean bIsFolder = b instanceof FolderEntity;

            if (aIsFolder && !bIsFolder) return -1;
            if (!aIsFolder && bIsFolder) return 1;

            String aName = aIsFolder ? ((FolderEntity) a).getFolderName() : ((FileEntity) a).getFileName();
            String bName = bIsFolder ? ((FolderEntity) b).getFolderName() : ((FileEntity) b).getFileName();

            return aName.compareToIgnoreCase(bName);
        });

        return allItems;
    }

    private Page<Object> applyPagination(List<Object> items, Pageable pageable) {
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), items.size());

        List<Object> pagedItems = items.subList(start, end);
        return new PageImpl<>(pagedItems, pageable, items.size());
    }

    private FolderContentsResponse.FolderItem convertToFolderItem(FolderEntity folder) {
        // Count items in folder
        int itemCount = countItemsInFolder(folder.getFolderId(), folder.getUserId());

        return FolderContentsResponse.FolderItem.builder()
                .id(folder.getFolderId())
                .name(folder.getFolderName())
                .type("folder")
                .itemCount(itemCount)
                .size(null) // TODO: Calculate folder size if needed
                .sizeFormatted(null)
                .hasSubfolders(hasSubfolders(folder.getFolderId(), folder.getUserId()))
                .createdAt(folder.getCreatedAt())
                .updatedAt(folder.getUpdatedAt())
                .build();
    }

    private FolderContentsResponse.FileItem convertToFileItem(FileEntity file) {
        return FolderContentsResponse.FileItem.builder()
                .id(file.getFileId())
                .name(file.getFileName())
                .type("file")
                .size(file.getFileSize())
                .sizeFormatted(formatFileSize(file.getFileSize()))
                .mimeType(file.getMimeType())
                .extension(getFileExtension(file.getFileName()))
                .category(getFileCategory(file.getMimeType()))
                .scanStatus(file.getScanStatus().toString())
                .scanDate(file.getUpdatedAt()) // Use updated date as scan date for now
                .createdAt(file.getCreatedAt())
                .updatedAt(file.getUpdatedAt())
                .build();
    }

    private FolderContentsResponse.FolderInfo buildFolderInfo(FolderEntity folder, boolean isRoot) {
        return FolderContentsResponse.FolderInfo.builder()
                .id(isRoot ? null : folder.getFolderId())
                .name(isRoot ? "Root" : folder.getFolderName())
                .fullPath(isRoot ? "" : folder.getFullPath())
                .isRoot(isRoot)
                .createdAt(isRoot ? null : folder.getCreatedAt())
                .updatedAt(isRoot ? null : folder.getUpdatedAt())
                .breadcrumbs(generateBreadcrumbs(folder))
                .build();
    }

    // Now this will work with the new repository methods
    private FolderContentsResponse.Statistics buildStatistics(UUID userId, UUID folderId, boolean isRoot, int currentFolders, int currentFiles) {

        // Count active folders (use the existing method since folders don't have soft delete yet)
        long totalFolders = isRoot
                ? folderRepository.countByUserIdAndParentFolderIsNull(userId)
                : folderRepository.countByUserIdAndParentFolder_FolderId(userId, folderId);

        // Count active files only (exclude deleted files)
        long totalFiles = isRoot
                ? fileRepository.countByUserIdAndFolderIsNullAndIsDeletedFalse(userId)
                : fileRepository.countByUserIdAndFolder_FolderIdAndIsDeletedFalse(userId, folderId);

        // TODO: Calculate total size of active files only
        long totalSize = 0;

        return FolderContentsResponse.Statistics.builder()
                .totalItems(totalFolders + totalFiles)
                .totalFolders(totalFolders)
                .totalFiles(totalFiles)
                .totalSize(totalSize)
                .totalSizeFormatted(formatFileSize(totalSize))
                .currentPage(FolderContentsResponse.CurrentPage.builder()
                        .items(currentFolders + currentFiles)
                        .folders(currentFolders)
                        .files(currentFiles)
                        .build())
                .build();
    }

    private FolderContentsResponse.PaginationInfo buildPaginationInfo(Page<Object> page) {
        return FolderContentsResponse.PaginationInfo.builder()
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

    private List<FolderContentsResponse.BreadcrumbItem> generateBreadcrumbs(FolderEntity currentFolder) {
        List<FolderContentsResponse.BreadcrumbItem> breadcrumbs = new ArrayList<>();

        // Add root
        breadcrumbs.add(FolderContentsResponse.BreadcrumbItem.builder()
                .id(null)
                .name("Root")
                .path("")
                .isCurrent(currentFolder == null)
                .build());

        if (currentFolder != null) {
            List<FolderEntity> pathFolders = new ArrayList<>();
            FolderEntity folder = currentFolder;

            while (folder != null) {
                pathFolders.add(0, folder);
                folder = folder.getParentFolder();
            }

            StringBuilder pathBuilder = new StringBuilder();
            for (int i = 0; i < pathFolders.size(); i++) {
                FolderEntity pathFolder = pathFolders.get(i);

                if (i > 0) pathBuilder.append("/");
                pathBuilder.append(pathFolder.getFolderName());

                breadcrumbs.add(FolderContentsResponse.BreadcrumbItem.builder()
                        .id(pathFolder.getFolderId())
                        .name(pathFolder.getFolderName())
                        .path(pathBuilder.toString())
                        .isCurrent(i == pathFolders.size() - 1)
                        .build());
            }
        }

        return breadcrumbs;
    }

    // Helper methods
    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format("%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        return lastDotIndex == -1 ? "" : fileName.substring(lastDotIndex + 1).toLowerCase();
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


    private int countItemsInFolder(UUID folderId, UUID userId) {
        long folders = folderRepository.countByUserIdAndParentFolder_FolderIdAndIsDeletedFalse(userId, folderId);
        long files = fileRepository.countByUserIdAndFolder_FolderIdAndIsDeletedFalse(userId, folderId);
        return (int) (folders + files);
    }

    private boolean hasSubfolders(UUID folderId, UUID userId) {
        return folderRepository.countByUserIdAndParentFolder_FolderIdAndIsDeletedFalse(userId, folderId) > 0;
    }


    private String validateAndPreparePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new RuntimeException("Path cannot be empty");
        }

        // Clean the path: remove leading/trailing slashes and extra spaces
        String cleanPath = path.trim().replaceAll("^/+|/+$", "");

        if (cleanPath.isEmpty()) {
            throw new RuntimeException("Path cannot be empty after cleaning");
        }

        // Check for consecutive slashes and clean them
        cleanPath = cleanPath.replaceAll("/+", "/");

        // Validate each segment
        String[] segments = cleanPath.split("/");
        for (String segment : segments) {
            if (segment.trim().isEmpty()) {
                throw new RuntimeException("Path cannot contain empty segments");
            }
            if (segment.length() > 255) {
                throw new RuntimeException("Folder name '" + segment + "' exceeds maximum length of 255 characters");
            }
        }

        return cleanPath;
    }

    private FolderEntity processPathSegments(String[] pathSegments,
                                             FolderEntity currentParent,
                                             UUID userId,
                                             List<String> createdFolders,
                                             List<String> existingFolders,
                                             List<FolderEntity> createdFolderEntities,
                                             List<String> minioPathsCreated) {

        FolderEntity currentFolder = currentParent;

        for (String segmentName : pathSegments) {
            segmentName = segmentName.trim();

            // Check if folder already exists at current level
            FolderEntity existingFolder = findExistingFolder(userId, segmentName, currentFolder);

            if (existingFolder != null) {
                // Folder exists, use it as current parent for next iteration
                existingFolders.add(segmentName);
                currentFolder = existingFolder;
                log.debug("Using existing folder: {}", segmentName);

            } else {
                // Folder doesn't exist, create it
                currentFolder = createFolderAtLevel(userId, segmentName, currentFolder);
                createdFolders.add(segmentName);
                createdFolderEntities.add(currentFolder);

                // Create folder structure in MinIO
                String folderPath = currentFolder.getFullPath();
                try {
                    minioService.createFolderStructure(userId, folderPath);
                    minioPathsCreated.add(folderPath);
                    log.debug("Created folder in MinIO: {}", folderPath);
                } catch (Exception e) {
                    log.error("Failed to create MinIO folder structure for: {}", folderPath, e);
                    throw new RuntimeException("Failed to create folder structure in storage: " + e.getMessage());
                }

                log.debug("Created new folder: {} with ID: {}", segmentName, currentFolder.getFolderId());
            }
        }

        return currentFolder;
    }

    private FolderEntity findExistingFolder(UUID userId, String folderName, FolderEntity parentFolder) {
        if (parentFolder == null) {
            // Look in root level
            return folderRepository.findByUserIdAndFolderNameAndParentFolderIsNull(userId, folderName)
                    .orElse(null);
        } else {
            // Look in specific parent folder
            return folderRepository.findByUserIdAndFolderNameAndParentFolder_FolderId(
                            userId, folderName, parentFolder.getFolderId())
                    .orElse(null);
        }
    }

    private FolderEntity createFolderAtLevel(UUID userId, String folderName, FolderEntity parentFolder) {
        FolderEntity newFolder = new FolderEntity();
        newFolder.setFolderName(folderName);
        newFolder.setUserId(userId);
        newFolder.setParentFolder(parentFolder);
        newFolder.setCreatedAt(LocalDateTime.now());

        FolderEntity savedFolder = folderRepository.save(newFolder);
        log.debug("Saved folder to database: {} with ID: {}", folderName, savedFolder.getFolderId());

        return savedFolder;
    }

    private String buildFullPath(FolderEntity folder) {
        return folder.getFullPath();
    }

    private String buildSummary(int created, int existing, int total) {
        if (created == 0) {
            return String.format("All %d folders already existed", total);
        } else if (existing == 0) {
            return String.format("Created %d new folders", created);
        } else {
            return String.format("Created %d new folders, %d already existed", created, existing);
        }
    }

    private void rollbackMinioFolders(UUID userId, List<String> minioPathsCreated) {
        // Clean up in reverse order (deepest first)
        for (int i = minioPathsCreated.size() - 1; i >= 0; i--) {
            String folderPath = minioPathsCreated.get(i);
            try {
                minioService.deleteFolderStructure(userId, folderPath);
                log.info("Rolled back MinIO folder: {}", folderPath);
            } catch (Exception cleanupError) {
                log.error("Failed to cleanup MinIO folder during rollback: {}", folderPath, cleanupError);
                // Continue cleanup even if some fail
            }
        }
    }
    private void validateBatchCreateRequest(BatchCreateFoldersRequest request) {
        List<String> folderNames = request.getDistinctFolderNames();

        // Check for null or empty list (additional validation beyond @NotEmpty)
        if (folderNames == null || folderNames.isEmpty()) {
            throw new RuntimeException("Folder names list cannot be empty");
        }

        // Check maximum limit
        if (folderNames.size() > 20) {
            throw new RuntimeException("Maximum 20 folders can be created in one request. Requested: " + folderNames.size());
        }

        // Check for duplicates in the request array
        Set<String> uniqueNames = new HashSet<>();
        List<String> duplicates = new ArrayList<>();

        for (String folderName : folderNames) {
            if (folderName == null || folderName.trim().isEmpty()) {
                throw new RuntimeException("Folder name cannot be null or empty");
            }

            String trimmedName = folderName.trim();

            // Check for invalid characters
            if (!isValidFolderName(trimmedName)) {
                throw new RuntimeException("Folder name contains invalid characters: " + trimmedName);
            }

            // Check for duplicates
            if (!uniqueNames.add(trimmedName)) {
                duplicates.add(trimmedName);
            }
        }

        // Throw error if duplicates found in request
        if (!duplicates.isEmpty()) {
            throw new RuntimeException("Duplicate folder names found in request: " + String.join(", ", duplicates));
        }
    }

    private boolean isValidFolderName(String folderName) {
        // Check length
        if (folderName.length() > 255) {
            return false;
        }

        // Check for invalid characters (same pattern as single folder creation)
        String invalidCharsPattern = "[<>:\"/\\\\|?*]";
        return !folderName.matches(".*" + invalidCharsPattern + ".*");
    }

    private FolderEntity validateParentFolder(UUID parentFolderId, UUID userId) throws ItemNotFoundException {
        if (parentFolderId == null) {
            return null; // Creating in root
        }

        FolderEntity parentFolder = folderRepository.findById(parentFolderId)
                .orElseThrow(() -> new ItemNotFoundException("Parent folder not found"));

        if (!parentFolder.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied: You don't own the parent folder");
        }

        return parentFolder;
    }

    private void ensureMinIOBucketExists(UUID userId) {
        try {
            if (!minioService.bucketExists(userId)) {
                log.info("Creating MinIO bucket for user: {}", userId);
                minioService.createUserBucket(userId);
            }
        } catch (Exception e) {
            log.error("Failed to create/check MinIO bucket for user: {}", userId, e);
            throw new RuntimeException("Storage initialization failed. Please try again.");
        }
    }

    private BatchCreateFoldersResponse processBatchFolderCreation(BatchCreateFoldersRequest request,
                                                                  UUID userId,
                                                                  FolderEntity parentFolder,
                                                                  String parentPath) {

        // Track creation results
        List<BatchCreateFoldersResponse.CreatedFolderInfo> createdFolders = new ArrayList<>();
        List<String> existingFolders = new ArrayList<>();
        List<BatchCreateFoldersResponse.FailedFolderInfo> failedFolders = new ArrayList<>();
        List<FolderEntity> createdEntities = new ArrayList<>(); // For rollback
        List<String> minioPathsCreated = new ArrayList<>(); // For rollback

        try {
            // Process each folder name
            for (String folderName : request.getDistinctFolderNames()) {
                String trimmedName = folderName.trim();

                // Check if folder already exists
                FolderEntity existingFolder = findExistingFolderInParent(userId, trimmedName, parentFolder);

                if (existingFolder != null) {
                    // Skip existing folder
                    existingFolders.add(trimmedName);
                    log.debug("Skipping existing folder: {}", trimmedName);
                    continue;
                }

                // Create new folder
                FolderEntity newFolder = createSingleFolderInBatch(userId, trimmedName, parentFolder);
                createdEntities.add(newFolder);

                // Create MinIO folder structure
                String folderPath = newFolder.getFullPath();
                try {
                    minioService.createFolderStructure(userId, folderPath);
                    minioPathsCreated.add(folderPath);
                    log.debug("Created MinIO structure for: {}", folderPath);
                } catch (Exception e) {
                    log.error("Failed to create MinIO structure for: {}", folderPath, e);
                    throw new RuntimeException("Failed to create folder structure in storage: " + e.getMessage());
                }

                // Add to successful creations
                createdFolders.add(BatchCreateFoldersResponse.CreatedFolderInfo.builder()
                        .folderId(newFolder.getFolderId())
                        .folderName(newFolder.getFolderName())
                        .fullPath(newFolder.getFullPath())
                        .createdAt(newFolder.getCreatedAt())
                        .build());

                log.debug("Successfully created folder: {} with ID: {}", trimmedName, newFolder.getFolderId());
            }

            // Build successful response
            return buildSuccessResponse(request, createdFolders, existingFolders, failedFolders, parentFolder);

        } catch (Exception e) {
            // Rollback: Clean up created MinIO folders
            rollbackMinioFoldersInBatch(userId, minioPathsCreated);

            // Database rollback handled by @Transactional
            log.error("Batch folder creation failed, rollback initiated: {}", e.getMessage());
            throw new RuntimeException("Failed to create folders: " + e.getMessage(), e);
        }
    }

    private FolderEntity findExistingFolderInParent(UUID userId, String folderName, FolderEntity parentFolder) {
        if (parentFolder == null) {
            // Look in root level
            return folderRepository.findByUserIdAndFolderNameAndParentFolderIsNull(userId, folderName)
                    .orElse(null);
        } else {
            // Look in specific parent folder
            return folderRepository.findByUserIdAndFolderNameAndParentFolder_FolderId(
                            userId, folderName, parentFolder.getFolderId())
                    .orElse(null);
        }
    }

    private FolderEntity createSingleFolderInBatch(UUID userId, String folderName, FolderEntity parentFolder) {
        FolderEntity newFolder = new FolderEntity();
        newFolder.setFolderName(folderName);
        newFolder.setUserId(userId);
        newFolder.setParentFolder(parentFolder);
        newFolder.setCreatedAt(LocalDateTime.now());

        FolderEntity savedFolder = folderRepository.save(newFolder);
        log.debug("Saved folder to database: {} with ID: {}", folderName, savedFolder.getFolderId());

        return savedFolder;
    }

    private BatchCreateFoldersResponse buildSuccessResponse(BatchCreateFoldersRequest request,
                                                            List<BatchCreateFoldersResponse.CreatedFolderInfo> createdFolders,
                                                            List<String> existingFolders,
                                                            List<BatchCreateFoldersResponse.FailedFolderInfo> failedFolders,
                                                            FolderEntity parentFolder) {

        int totalRequested = request.getDistinctFolderNames().size();
        int created = createdFolders.size();
        int existing = existingFolders.size();
        int failed = failedFolders.size();

        String summary = buildBatchSummary(created, existing, failed, totalRequested);
        String parentPath = parentFolder != null ? parentFolder.getFullPath() : "Root";

        log.info("Batch folder creation completed - Created: {}, Existing: {}, Failed: {}",
                created, existing, failed);

        return BatchCreateFoldersResponse.builder()
                .totalRequested(totalRequested)
                .successfullyCreated(created)
                .alreadyExisted(existing)
                .failed(failed)
                .createdFolders(createdFolders)
                .existingFolders(existingFolders)
                .failedFolders(failedFolders)
                .parentFolderId(request.getParentFolderId())
                .parentFolderPath(parentPath)
                .summary(summary)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private String buildBatchSummary(int created, int existing, int failed, int total) {
        if (failed > 0) {
            return String.format("Created %d folders, %d already existed, %d failed out of %d requested",
                    created, existing, failed, total);
        } else if (existing > 0 && created > 0) {
            return String.format("Created %d new folders, %d already existed", created, existing);
        } else if (existing > 0 && created == 0) {
            return String.format("All %d folders already existed", total);
        } else {
            return String.format("Successfully created %d folders", created);
        }
    }

    private void rollbackMinioFoldersInBatch(UUID userId, List<String> minioPathsCreated) {
        // Clean up in reverse order (deepest first, though all are same level in this case)
        for (int i = minioPathsCreated.size() - 1; i >= 0; i--) {
            String folderPath = minioPathsCreated.get(i);
            try {
                minioService.deleteFolderStructure(userId, folderPath);
                log.info("Rolled back MinIO folder: {}", folderPath);
            } catch (Exception cleanupError) {
                log.error("Failed to cleanup MinIO folder during rollback: {}", folderPath, cleanupError);
                // Continue cleanup even if some fail
            }
        }
    }

    private String validateAndPrepareFolderPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new RuntimeException("Path cannot be empty");
        }

        // Clean the path: remove leading/trailing slashes and extra spaces
        String cleanPath = path.trim().replaceAll("^/+|/+$", "");

        if (cleanPath.isEmpty()) {
            throw new RuntimeException("Path cannot be empty after cleaning");
        }

        // Check for consecutive slashes and clean them
        cleanPath = cleanPath.replaceAll("/+", "/");

        // Validate each segment
        String[] segments = cleanPath.split("/");
        for (String segment : segments) {
            if (segment.trim().isEmpty()) {
                throw new RuntimeException("Path cannot contain empty segments");
            }
            if (segment.length() > 255) {
                throw new RuntimeException("Folder name '" + segment + "' exceeds maximum length of 255 characters");
            }
        }

        return cleanPath;
    }

    private FolderEntity determineStartingPoint(UUID parentId, UUID userId) throws ItemNotFoundException {
        if (parentId == null) {
            // Start from root
            log.debug("Starting navigation from root");
            return null;
        }

        // Validate and get starting folder
        FolderEntity startingFolder = folderRepository.findById(parentId)
                .orElseThrow(() -> new ItemNotFoundException("Starting folder not found"));

        if (!startingFolder.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied: You don't own the starting folder");
        }

        log.debug("Starting navigation from folder: {} ({})", startingFolder.getFolderName(), parentId);
        return startingFolder;
    }

    private PathNavigationResult navigateToTargetFolder(String[] pathSegments,
                                                        FolderEntity startingFolder,
                                                        UUID userId) throws ItemNotFoundException {

        List<GetFolderByPathResponse.BreadcrumbItem> navigationPath = new ArrayList<>();
        FolderEntity currentFolder = startingFolder;
        StringBuilder relativePath = new StringBuilder();

        // Add starting point to navigation path
        if (startingFolder != null) {
            navigationPath.add(GetFolderByPathResponse.BreadcrumbItem.builder()
                    .id(startingFolder.getFolderId())
                    .name(startingFolder.getFolderName())
                    .pathFromStart("")
                    .isStartingPoint(true)
                    .isTarget(false)
                    .build());
        } else {
            navigationPath.add(GetFolderByPathResponse.BreadcrumbItem.builder()
                    .id(null)
                    .name("Root")
                    .pathFromStart("")
                    .isStartingPoint(true)
                    .isTarget(false)
                    .build());
        }

        // Navigate through each path segment
        for (int i = 0; i < pathSegments.length; i++) {
            String segmentName = pathSegments[i].trim();
            boolean isLastSegment = (i == pathSegments.length - 1);

            // Find folder in current location
            FolderEntity nextFolder = findFolderInCurrentLocation(userId, segmentName, currentFolder);

            if (nextFolder == null) {
                String currentLocation = currentFolder != null ? currentFolder.getFullPath() : "Root";
                throw new ItemNotFoundException(
                        String.format("Folder '%s' not found in location '%s'", segmentName, currentLocation));
            }

            // Build relative path
            if (relativePath.length() > 0) {
                relativePath.append("/");
            }
            relativePath.append(segmentName);

            // Add to navigation path
            navigationPath.add(GetFolderByPathResponse.BreadcrumbItem.builder()
                    .id(nextFolder.getFolderId())
                    .name(nextFolder.getFolderName())
                    .pathFromStart(relativePath.toString())
                    .isStartingPoint(false)
                    .isTarget(isLastSegment)
                    .build());

            currentFolder = nextFolder;
            log.debug("Navigated to folder: {} at path: {}", segmentName, relativePath);
        }

        return new PathNavigationResult(currentFolder, navigationPath, relativePath.toString());
    }

    private FolderEntity findFolderInCurrentLocation(UUID userId, String folderName, FolderEntity currentFolder) {
        if (currentFolder == null) {
            // Look in root level
            return folderRepository.findByUserIdAndFolderNameAndParentFolderIsNull(userId, folderName)
                    .orElse(null);
        } else {
            // Look in specific parent folder
            return folderRepository.findByUserIdAndFolderNameAndParentFolder_FolderId(
                            userId, folderName, currentFolder.getFolderId())
                    .orElse(null);
        }
    }

    private GetFolderByPathResponse.FolderContents getFolderContentsForPath(FolderEntity targetFolder, UUID userId) {
        UUID folderId = targetFolder != null ? targetFolder.getFolderId() : null;

        // Get subfolders
        List<FolderEntity> subfolders;
        if (folderId == null) {
            subfolders = folderRepository.findByUserIdAndParentFolderIsNull(userId);
        } else {
            subfolders = folderRepository.findByUserIdAndParentFolder_FolderId(userId, folderId);
        }

        // Get files (active only)
        List<FileEntity> files;
        if (folderId == null) {
            files = fileRepository.findByUserIdAndFolderIsNullAndIsDeletedFalse(userId);
        } else {
            files = fileRepository.findByUserIdAndFolder_FolderIdAndIsDeletedFalse(userId, folderId);
        }

        // Convert to response DTOs
        List<GetFolderByPathResponse.FolderItem> folderItems = subfolders.stream()
                .map(folder -> GetFolderByPathResponse.FolderItem.builder()
                        .id(folder.getFolderId())
                        .name(folder.getFolderName())
                        .createdAt(folder.getCreatedAt())
                        .hasSubfolders(hasSubfolders(folder.getFolderId(), userId))
                        .itemCount(countItemsInFolder(folder.getFolderId(), userId))
                        .build())
                .toList();

        List<GetFolderByPathResponse.FileItem> fileItems = files.stream()
                .map(file -> GetFolderByPathResponse.FileItem.builder()
                        .id(file.getFileId())
                        .name(file.getFileName())
                        .size(file.getFileSize())
                        .sizeFormatted(formatFileSize(file.getFileSize()))
                        .mimeType(file.getMimeType())
                        .extension(getFileExtension(file.getFileName()))
                        .category(getFileCategory(file.getMimeType()))
                        .scanStatus(file.getScanStatus().toString())
                        .createdAt(file.getCreatedAt())
                        .build())
                .toList();

        return GetFolderByPathResponse.FolderContents.builder()
                .folders(folderItems)
                .files(fileItems)
                .totalFolders(folderItems.size())
                .totalFiles(fileItems.size())
                .totalItems(folderItems.size() + fileItems.size())
                .build();
    }

    private GetFolderByPathResponse buildPathResponse(GetFolderByPathRequest request,
                                                      FolderEntity startingFolder,
                                                      PathNavigationResult navigationResult,
                                                      GetFolderByPathResponse.FolderContents contents) {

        FolderEntity targetFolder = navigationResult.getTargetFolder();

        // Build target folder info
        GetFolderByPathResponse.FolderInfo targetFolderInfo = GetFolderByPathResponse.FolderInfo.builder()
                .id(targetFolder.getFolderId())
                .name(targetFolder.getFolderName())
                .fullPath(targetFolder.getFullPath())
                .relativePath(navigationResult.getRelativePath())
                .parentId(targetFolder.getParentFolder() != null ? targetFolder.getParentFolder().getFolderId() : null)
                .createdAt(targetFolder.getCreatedAt())
                .updatedAt(targetFolder.getUpdatedAt())
                .isRoot(false)
                .build();

        // Build starting point info
        GetFolderByPathResponse.StartingPoint startingPointInfo = GetFolderByPathResponse.StartingPoint.builder()
                .id(startingFolder != null ? startingFolder.getFolderId() : null)
                .name(startingFolder != null ? startingFolder.getFolderName() : "Root")
                .fullPath(startingFolder != null ? startingFolder.getFullPath() : "")
                .isRoot(startingFolder == null)
                .build();

        // Build summary
        String summary = buildPathResolutionSummary(request, startingFolder, navigationResult);

        log.info("Path resolution completed: '{}' -> {}",
                request.getPath(), targetFolder.getFullPath());

        return GetFolderByPathResponse.builder()
                .targetFolder(targetFolderInfo)
                .navigationPath(navigationResult.getNavigationPath())
                .startingPoint(startingPointInfo)
                .contents(contents)
                .pathResolutionSummary(summary)
                .build();
    }

    private String buildPathResolutionSummary(GetFolderByPathRequest request,
                                              FolderEntity startingFolder,
                                              PathNavigationResult navigationResult) {

        String startingPoint = startingFolder != null ? startingFolder.getFolderName() : "Root";
        String targetPath = navigationResult.getTargetFolder().getFullPath();
        int segmentsTraversed = navigationResult.getNavigationPath().size() - 1; // Exclude starting point

        return String.format("Successfully navigated from '%s' through %d segment(s) to reach '%s'",
                startingPoint, segmentsTraversed, targetPath);
    }

    // Helper class for navigation result
    private static class PathNavigationResult {
        private final FolderEntity targetFolder;
        private final List<GetFolderByPathResponse.BreadcrumbItem> navigationPath;
        private final String relativePath;

        public PathNavigationResult(FolderEntity targetFolder,
                                    List<GetFolderByPathResponse.BreadcrumbItem> navigationPath,
                                    String relativePath) {
            this.targetFolder = targetFolder;
            this.navigationPath = navigationPath;
            this.relativePath = relativePath;
        }

        public FolderEntity getTargetFolder() { return targetFolder; }
        public List<GetFolderByPathResponse.BreadcrumbItem> getNavigationPath() { return navigationPath; }
        public String getRelativePath() { return relativePath; }
    }
    private void validateBatchAtPathRequest(BatchCreateFoldersAtPathRequest request) {
        // Validate folder names (reuse existing validation)
        List<String> folderNames = request.getFolderNames();

        if (folderNames == null || folderNames.isEmpty()) {
            throw new RuntimeException("Folder names list cannot be empty");
        }

        if (folderNames.size() > 20) {
            throw new RuntimeException("Maximum 20 folders can be created in one request. Requested: " + folderNames.size());
        }

        // Check for duplicates in request
        Set<String> uniqueNames = new HashSet<>();
        List<String> duplicates = new ArrayList<>();

        for (String folderName : folderNames) {
            if (folderName == null || folderName.trim().isEmpty()) {
                throw new RuntimeException("Folder name cannot be null or empty");
            }

            String trimmedName = folderName.trim();

            if (!isValidFolderName(trimmedName)) {
                throw new RuntimeException("Folder name contains invalid characters: " + trimmedName);
            }

            if (!uniqueNames.add(trimmedName)) {
                duplicates.add(trimmedName);
            }
        }

        if (!duplicates.isEmpty()) {
            throw new RuntimeException("Duplicate folder names found in request: " + String.join(", ", duplicates));
        }

        // Validate target path
        String cleanPath = validateAndPrepareFolderPath(request.getTargetPath());
        if (cleanPath.split("/").length > 10) {
            throw new RuntimeException("Target path depth exceeds maximum allowed limit of 10 levels");
        }
    }

    private PathNavigationResult navigateToTargetLocation(BatchCreateFoldersAtPathRequest request, UUID userId) throws ItemNotFoundException {

        // Determine starting point
        FolderEntity startingFolder = determineStartingPoint(request.getParentId(), userId);

        // Navigate to target path
        String cleanPath = validateAndPrepareFolderPath(request.getTargetPath());
        String[] pathSegments = cleanPath.split("/");

        return navigateToTargetFolder(pathSegments, startingFolder, userId);
    }

    private BatchCreateFoldersAtPathResponse.BatchResults createFoldersAtTargetLocation(List<String> folderNames,
                                                                                        FolderEntity targetFolder,
                                                                                        UUID userId) {

        // Track creation results
        List<BatchCreateFoldersAtPathResponse.CreatedFolderInfo> createdFolders = new ArrayList<>();
        List<String> existingFolders = new ArrayList<>();
        List<BatchCreateFoldersAtPathResponse.FailedFolderInfo> failedFolders = new ArrayList<>();
        List<FolderEntity> createdEntities = new ArrayList<>(); // For rollback
        List<String> minioPathsCreated = new ArrayList<>(); // For rollback

        try {
            // Process each folder name
            for (String folderName : folderNames) {
                String trimmedName = folderName.trim();

                // Check if folder already exists in target location
                FolderEntity existingFolder = findFolderInCurrentLocation(userId, trimmedName, targetFolder);

                if (existingFolder != null) {
                    // Skip existing folder
                    existingFolders.add(trimmedName);
                    log.debug("Skipping existing folder: {} in target location", trimmedName);
                    continue;
                }

                // Create new folder in target location
                FolderEntity newFolder = createSingleFolderInBatch(userId, trimmedName, targetFolder);
                createdEntities.add(newFolder);

                // Create MinIO folder structure
                String folderPath = newFolder.getFullPath();
                try {
                    minioService.createFolderStructure(userId, folderPath);
                    minioPathsCreated.add(folderPath);
                    log.debug("Created MinIO structure for: {}", folderPath);
                } catch (Exception e) {
                    log.error("Failed to create MinIO structure for: {}", folderPath, e);
                    throw new RuntimeException("Failed to create folder structure in storage: " + e.getMessage());
                }

                // Add to successful creations
                createdFolders.add(BatchCreateFoldersAtPathResponse.CreatedFolderInfo.builder()
                        .folderId(newFolder.getFolderId())
                        .folderName(newFolder.getFolderName())
                        .fullPath(newFolder.getFullPath())
                        .createdAt(newFolder.getCreatedAt())
                        .build());

                log.debug("Successfully created folder: {} in target location with ID: {}",
                        trimmedName, newFolder.getFolderId());
            }

            // Build batch results
            return BatchCreateFoldersAtPathResponse.BatchResults.builder()
                    .totalRequested(folderNames.size())
                    .successfullyCreated(createdFolders.size())
                    .alreadyExisted(existingFolders.size())
                    .failed(failedFolders.size())
                    .createdFolders(createdFolders)
                    .existingFolders(existingFolders)
                    .failedFolders(failedFolders)
                    .build();

        } catch (Exception e) {
            // Rollback: Clean up created MinIO folders
            rollbackMinioFoldersInBatch(userId, minioPathsCreated);

            // Database rollback handled by @Transactional
            log.error("Batch folder creation at path failed, rollback initiated: {}", e.getMessage());
            throw new RuntimeException("Failed to create folders at target location: " + e.getMessage(), e);
        }
    }

    private BatchCreateFoldersAtPathResponse buildBatchAtPathResponse(BatchCreateFoldersAtPathRequest request,
                                                                      PathNavigationResult navigationResult,
                                                                      BatchCreateFoldersAtPathResponse.BatchResults batchResults) {

        FolderEntity targetFolder = navigationResult.getTargetFolder();

        // Build target location info
        BatchCreateFoldersAtPathResponse.TargetLocationInfo targetLocationInfo =
                BatchCreateFoldersAtPathResponse.TargetLocationInfo.builder()
                        .folderId(targetFolder.getFolderId())
                        .folderName(targetFolder.getFolderName())
                        .fullPath(targetFolder.getFullPath())
                        .relativePath(navigationResult.getRelativePath())
                        .isRoot(targetFolder.getParentFolder() == null)
                        .build();

        // Convert navigation path
        List<BatchCreateFoldersAtPathResponse.BreadcrumbItem> breadcrumbs =
                navigationResult.getNavigationPath().stream()
                        .map(item -> BatchCreateFoldersAtPathResponse.BreadcrumbItem.builder()
                                .id(item.getId())
                                .name(item.getName())
                                .pathFromStart(item.getPathFromStart())
                                .isStartingPoint(item.isStartingPoint())
                                .isTarget(item.isTarget())
                                .build())
                        .toList();

        // Build summary
        String summary = buildBatchAtPathSummary(request, navigationResult, batchResults);

        log.info("Batch folder creation at path completed - Created: {}, Existing: {}, Failed: {} at location: {}",
                batchResults.getSuccessfullyCreated(), batchResults.getAlreadyExisted(),
                batchResults.getFailed(), targetFolder.getFullPath());

        return BatchCreateFoldersAtPathResponse.builder()
                .targetLocation(targetLocationInfo)
                .batchResults(batchResults)
                .navigationPath(breadcrumbs)
                .summary(summary)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private String buildBatchAtPathSummary(BatchCreateFoldersAtPathRequest request,
                                           PathNavigationResult navigationResult,
                                           BatchCreateFoldersAtPathResponse.BatchResults batchResults) {

        String targetLocation = navigationResult.getTargetFolder().getFullPath();
        int created = batchResults.getSuccessfullyCreated();
        int existing = batchResults.getAlreadyExisted();
        int failed = batchResults.getFailed();
        int total = batchResults.getTotalRequested();

        if (failed > 0) {
            return String.format("At '%s': Created %d folders, %d already existed, %d failed out of %d requested",
                    targetLocation, created, existing, failed, total);
        } else if (existing > 0 && created > 0) {
            return String.format("At '%s': Created %d new folders, %d already existed",
                    targetLocation, created, existing);
        } else if (existing > 0 && created == 0) {
            return String.format("At '%s': All %d folders already existed", targetLocation, total);
        } else {
            return String.format("At '%s': Successfully created %d folders", targetLocation, created);
        }
    }

}