package org.qbitspark.glueauthfileboxbackend.files_mng_service.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.AccountRepo;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.AccountEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.entity.FileEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.entity.FolderEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.CreateFolderRequest;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.CreateFolderResponse;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.FolderContentsResponse;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.FolderListResponse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

        // Get folders
        List<FolderEntity> folders;
        if (isRoot) {
            folders = folderRepository.findByUserIdAndParentFolderIsNull(userId);
        } else {
            folders = folderRepository.findByUserIdAndParentFolder_FolderId(userId, folderId);
        }
        allItems.addAll(folders);

        // Get files
        List<FileEntity> files;
        if (isRoot) {
            files = fileRepository.findByUserIdAndFolderIsNull(userId);
        } else {
            files = fileRepository.findByUserIdAndFolder_FolderId(userId, folderId);
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

    private FolderContentsResponse.Statistics buildStatistics(UUID userId, UUID folderId, boolean isRoot, int currentFolders, int currentFiles) {
        long totalFolders = isRoot
                ? folderRepository.countByUserIdAndParentFolderIsNull(userId)
                : folderRepository.countByUserIdAndParentFolder_FolderId(userId, folderId);

        long totalFiles = isRoot
                ? fileRepository.countByUserIdAndFolderIsNull(userId)
                : fileRepository.countByUserIdAndFolder_FolderId(userId, folderId);

        // TODO: Calculate total size
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
        long folders = folderRepository.countByUserIdAndParentFolder_FolderId(userId, folderId);
        long files = fileRepository.countByUserIdAndFolder_FolderId(userId, folderId);
        return (int) (folders + files);
    }

    private boolean hasSubfolders(UUID folderId, UUID userId) {
        return folderRepository.countByUserIdAndParentFolder_FolderId(userId, folderId) > 0;
    }

}