package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class CreateFolderRequest {

    @NotBlank(message = "Folder name is required")
    @Size(min = 1, max = 255, message = "Folder name must be between 1 and 255 characters")
    @Pattern(regexp = "^[^<>:\"/\\\\|?*]+$", message = "Folder name contains invalid characters")
    private String folderName;

    // Optional: null means root level folder
    private UUID parentFolderId;
}