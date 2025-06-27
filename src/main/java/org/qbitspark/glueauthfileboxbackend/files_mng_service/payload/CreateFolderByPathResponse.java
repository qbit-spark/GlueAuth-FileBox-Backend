package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CreateFolderByPathResponse {


    private UUID finalFolderId;

    private String fullPath;

    private List<String> createdFolders;

    private List<String> existingFolders;

    private int totalCreated;

    private String finalFolderName;
    private LocalDateTime createdAt;

    private String summary;
}