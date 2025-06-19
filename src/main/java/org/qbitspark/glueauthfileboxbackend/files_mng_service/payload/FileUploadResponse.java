package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.enums.VirusScanStatus;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FileUploadResponse {
    private UUID fileId;
    private String fileName;
    private UUID folderId;
    private String folderPath;
    private Long fileSize;
    private String mimeType;
    private VirusScanStatus scanStatus;
    private LocalDateTime uploadedAt;
}