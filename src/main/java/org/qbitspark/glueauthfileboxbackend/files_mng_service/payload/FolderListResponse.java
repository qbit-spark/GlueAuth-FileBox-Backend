package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FolderListResponse {
    private UUID folderId;
    private String folderName;
    private UUID parentFolderId;
    private String fullPath;
    private LocalDateTime createdAt;
    private boolean hasSubFolders;  // Useful for UI tree rendering
    private int fileCount;          // Number of files in this folder
}