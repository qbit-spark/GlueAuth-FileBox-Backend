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
public class BatchCreateFoldersResponse {

    // Summary counts
    private int totalRequested;
    private int successfullyCreated;
    private int alreadyExisted;
    private int failed;

    // Detailed results
    private List<CreatedFolderInfo> createdFolders;
    private List<String> existingFolders;
    private List<FailedFolderInfo> failedFolders;

    // Parent folder information
    private UUID parentFolderId;
    private String parentFolderPath;

    // Operation summary
    private String summary;
    private LocalDateTime createdAt;

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
}