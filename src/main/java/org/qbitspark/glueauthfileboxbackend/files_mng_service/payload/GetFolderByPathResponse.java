package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GetFolderByPathResponse {

    // Target folder information
    private FolderInfo targetFolder;

    // Navigation path taken to reach target folder
    private List<BreadcrumbItem> navigationPath;

    // Starting point information
    private StartingPoint startingPoint;

    // Folder contents (if requested)
    private FolderContents contents;

    // Path resolution summary
    private String pathResolutionSummary;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FolderInfo {
        private UUID id;
        private String name;
        private String fullPath;
        private String relativePath; // Path from starting point
        private UUID parentId;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private boolean isRoot;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class BreadcrumbItem {
        private UUID id;
        private String name;
        private String pathFromStart;
        private boolean isStartingPoint;
        private boolean isTarget;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StartingPoint {
        private UUID id;
        private String name;
        private String fullPath;
        private boolean isRoot;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FolderContents {
        private List<FolderItem> folders;
        private List<FileItem> files;
        private int totalFolders;
        private int totalFiles;
        private int totalItems;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FolderItem {
        private UUID id;
        private String name;
        private LocalDateTime createdAt;
        private boolean hasSubfolders;
        private int itemCount;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FileItem {
        private UUID id;
        private String name;
        private Long size;
        private String sizeFormatted;
        private String mimeType;
        private String extension;
        private String category;
        private String scanStatus;
        private LocalDateTime createdAt;
    }
}