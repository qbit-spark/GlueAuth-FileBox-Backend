package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BatchCreateFoldersRequest {

    @NotEmpty(message = "Folder names list cannot be empty")
    @Size(max = 20, message = "Maximum 20 folders can be created in one request")
    private List<String> distinctFolderNames;

    // Optional: null means create in root, otherwise create in this parent folder
    private UUID parentFolderId;
}