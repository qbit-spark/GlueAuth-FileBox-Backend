package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class BatchFileData {
    private List<FileData> files;
    private UUID folderId;
    private String folderPath;
    private BatchUploadOptions options;
}