package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EmptyTrashResponse {

    private int totalFilesProcessed;
    private int successfulDeletions;
    private int failedDeletions;

    // Details of successful permanent deletions
    private List<UUID> permanentlyDeletedFileIds;

    // Details of failed operations
    private List<FailedDeletion> failures;

    private String summary;
    private long totalSpaceFreed; // in bytes
    private String totalSpaceFreedFormatted;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FailedDeletion {
        private UUID fileId;
        private String reason;
        private String fileName;
        private String minioKey;
    }
}