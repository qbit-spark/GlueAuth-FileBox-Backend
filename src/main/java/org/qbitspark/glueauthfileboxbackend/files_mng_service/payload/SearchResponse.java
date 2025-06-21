// Create: SearchResponse.java
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
public class SearchResponse {

    private String query;
    private Contents contents;
    private PaginationInfo pagination;
    private SummaryInfo summary;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Contents {
        private List<FolderResult> folders;
        private List<FileResult> files;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FolderResult {
        private UUID id;
        private String name;
        private String type = "folder";
        private String folderPath;
        private Integer itemCount;
        private Boolean hasSubfolders;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FileResult {
        private UUID id;
        private String name;
        private String type = "file";
        private Long size;
        private String sizeFormatted;
        private String mimeType;
        private String extension;
        private String category;
        private String scanStatus;
        private String folderPath;
        private boolean canPreview;
        private boolean canDownload;
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

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SummaryInfo {
        private long totalResults;
        private long totalFolders;
        private long totalFiles;
    }
}