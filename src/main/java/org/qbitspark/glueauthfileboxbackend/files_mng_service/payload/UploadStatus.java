package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UploadStatus {
    private String uploadId;
    private String fileName;
    private String stage;
    private int progress; // 0-100
    private String message;
    private boolean completed;
    private boolean failed;
    private String errorMessage;
    private FileUploadResponse result;
    private LocalDateTime startTime;
    private LocalDateTime lastUpdated;
}
