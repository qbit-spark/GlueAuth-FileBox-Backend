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
public class FolderContentsResponse {

    private FolderInfo folder;
    private Statistics statistics;
    private Contents contents;
    private PaginationInfo pagination;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FolderInfo {
        private UUID id;
        private String name;
        private String fullPath;
        private boolean isRoot;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private List<BreadcrumbItem> breadcrumbs;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class BreadcrumbItem {
        private UUID id;
        private String name;
        private String path;
        private boolean isCurrent;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Statistics {
        private long totalItems;
        private long totalFolders;
        private long totalFiles;
        private long totalSize;
        private String totalSizeFormatted;
        private CurrentPage currentPage;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CurrentPage {
        private int items;
        private int folders;
        private int files;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Contents {
        private List<FolderItem> folders;
        private List<FileItem> files;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FolderItem {
        private UUID id;
        private String name;
        private String type = "folder";
        private Integer itemCount;
        private Long size;
        private String sizeFormatted;
        private Boolean hasSubfolders;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FileItem {
        private UUID id;
        private String name;
        private String type = "file";
        private Long size;
        private String sizeFormatted;
        private String mimeType;
        private String extension;
        private String category;
        private String scanStatus;
        private LocalDateTime scanDate;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PaginationInfo {
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
        private boolean hasNext;
        private boolean hasPrevious;
        private boolean isFirst;
        private boolean isLast;
    }
}