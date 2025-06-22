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
public class BulkDeleteResponse {

    private int totalFilesRequested;
    private int successfulDeletions;
    private int failedDeletions;

    // Details of successful operations
    private List<UUID> deletedFileIds;

    // Details of failed operations
    private List<FailedDeletion> failures;

    private String summary;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FailedDeletion {
        private UUID fileId;
        private String reason;
        private String fileName;
    }
}