package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BatchUploadOptions {
    private int maxConcurrentUploads = 3; // Limit concurrent uploads
    private boolean stopOnFirstError = false;
    private boolean allowDuplicates = false;
    private int virusScanTimeout = 30000; // 30 seconds per file
}
