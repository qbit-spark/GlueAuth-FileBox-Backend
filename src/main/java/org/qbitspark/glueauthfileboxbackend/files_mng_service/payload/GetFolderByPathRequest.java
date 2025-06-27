package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GetFolderByPathRequest {

    @NotBlank(message = "Path is required")
    @Pattern(
            regexp = "^[^<>:\"/\\\\|?*]+(/[^<>:\"/\\\\|?*]+)*$",
            message = "Path contains invalid characters or format"
    )
    private String path;

    // Optional: If provided, start navigation from this folder
    // If null, start from root
    private UUID parentId;

    // Optional: Whether to include folder contents in response
    private boolean includeContents = true;
}