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
public class BulkMoveResponse {

    private int totalFilesRequested;
    private int successfulMoves;
    private int failedMoves;

    // Details of successful operations
    private List<UUID> movedFileIds;

    // Details of failed operations
    private List<FailedOperation> failures;

    private String summary;
    private String destinationPath;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FailedOperation {
        private UUID fileId;
        private String reason;
        private String fileName;
    }
}