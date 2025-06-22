package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BatchUploadSyncResponse {

    private int totalFilesRequested;
    private int successfulUploads;
    private int failedUploads;

    // Details of successful operations
    private List<FileUploadResponse> uploadedFiles;

    // Details of failed operations
    private List<FailedUpload> failures;

    private String summary;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FailedUpload {
        private String fileName;
        private String reason;
        private long fileSize;
    }
}
