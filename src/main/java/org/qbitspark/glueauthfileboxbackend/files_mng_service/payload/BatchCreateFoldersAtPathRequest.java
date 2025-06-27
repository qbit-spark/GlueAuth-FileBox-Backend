package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BatchCreateFoldersAtPathRequest {

    @NotBlank(message = "Target path is required")
    @Pattern(
            regexp = "^[^<>:\"/\\\\|?*]+(/[^<>:\"/\\\\|?*]+)*$",
            message = "Target path contains invalid characters or format"
    )
    private String targetPath;

    @NotEmpty(message = "Folder names list cannot be empty")
    @Size(max = 20, message = "Maximum 20 folders can be created in one request")
    private List<String> folderNames;

    // Optional: If provided, start navigation from this folder
    // If null, start from root
    private UUID parentId;
}