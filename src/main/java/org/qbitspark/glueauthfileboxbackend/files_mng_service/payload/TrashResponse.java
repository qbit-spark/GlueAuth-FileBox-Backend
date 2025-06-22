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
public class TrashResponse {

    private TrashSummary summary;
    private List<TrashFileItem> files;
    private PaginationInfo pagination;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TrashSummary {
        private int totalTrashFiles;
        private long totalTrashSize;
        private String totalTrashSizeFormatted;
        private LocalDateTime oldestDeletedAt;
        private LocalDateTime newestDeletedAt;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TrashFileItem {
        private UUID id;
        private String name;
        private Long size;
        private String sizeFormatted;
        private String mimeType;
        private String extension;
        private String category;
        private String scanStatus;
        private String originalFolderPath;
        private LocalDateTime deletedAt;
        private LocalDateTime originalCreatedAt;
        private boolean canRestore;
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
