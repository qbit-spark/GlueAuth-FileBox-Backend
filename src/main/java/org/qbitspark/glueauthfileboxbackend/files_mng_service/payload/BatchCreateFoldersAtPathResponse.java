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
public class BatchCreateFoldersAtPathResponse {

    // Target location information
    private TargetLocationInfo targetLocation;

    // Batch creation results
    private BatchResults batchResults;

    // Navigation path to the target location
    private List<BreadcrumbItem> navigationPath;

    // Operation summary
    private String summary;
    private LocalDateTime createdAt;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TargetLocationInfo {
        private UUID folderId;
        private String folderName;
        private String fullPath;
        private String relativePath; // From starting point
        private boolean isRoot;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class BatchResults {
        private int totalRequested;
        private int successfullyCreated;
        private int alreadyExisted;
        private int failed;

        // Detailed results
        private List<CreatedFolderInfo> createdFolders;
        private List<String> existingFolders;
        private List<FailedFolderInfo> failedFolders;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CreatedFolderInfo {
        private UUID folderId;
        private String folderName;
        private String fullPath;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FailedFolderInfo {
        private String folderName;
        private String reason;
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
}