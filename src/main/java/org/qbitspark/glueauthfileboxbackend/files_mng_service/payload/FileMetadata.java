package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import lombok.Builder;
import lombok.Data;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.entity.FolderEntity;

import java.util.UUID;

@Data
@Builder
public class FileMetadata {
    private UUID userId;
    private FolderEntity folder;
    private String folderPath;
    private String originalFileName;
    private String finalFileName;
    private boolean wasRenamed;
}