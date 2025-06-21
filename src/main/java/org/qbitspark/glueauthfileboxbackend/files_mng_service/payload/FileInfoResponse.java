package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FileInfoResponse {
    private UUID id;
    private String name;
    private Long size;
    private String sizeFormatted;
    private String mimeType;
    private String extension;
    private String category;
    private String scanStatus;
    private String folderPath;
    private boolean canPreview;
    private boolean canDownload;
    private LocalDateTime uploadedAt;
    private LocalDateTime updatedAt;
}
