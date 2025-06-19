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
public class CreateFolderResponse {
    private UUID folderId;
    private String folderName;
    private UUID parentFolderId;  // null if root level
    private String fullPath;      // "Documents/Work/Projects"
    private LocalDateTime createdAt;

}