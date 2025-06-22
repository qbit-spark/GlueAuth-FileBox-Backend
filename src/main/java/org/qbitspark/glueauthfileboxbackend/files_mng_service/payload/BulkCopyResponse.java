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
public class BulkCopyResponse {

    private int totalFilesRequested;
    private int successfulCopies;
    private int failedCopies;

    // Details of successful operations
    private List<FileUploadResponse> copiedFiles;

    // Details of failed operations
    private List<FailedOperation> failures;

    private String summary;
    private String destinationPath;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FailedOperation {
        private UUID sourceFileId;
        private String reason;
        private String fileName;
    }
}