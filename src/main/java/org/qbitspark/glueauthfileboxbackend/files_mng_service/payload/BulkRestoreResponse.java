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
public class BulkRestoreResponse {

    private int totalFilesRequested;
    private int successfulRestores;
    private int failedRestores;

    // Details of successful operations
    private List<UUID> restoredFileIds;

    // Details of failed operations
    private List<FailedRestore> failures;

    private String summary;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FailedRestore {
        private UUID fileId;
        private String reason;
        private String fileName;
    }
}