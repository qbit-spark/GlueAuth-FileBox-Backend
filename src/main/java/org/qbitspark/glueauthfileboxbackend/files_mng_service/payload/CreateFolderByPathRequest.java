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
public class CreateFolderByPathRequest {

    @NotBlank(message = "Path is required")
    @Pattern(
            regexp = "^[^<>:\"/\\\\|?*]+(/[^<>:\"/\\\\|?*]+)*$",
            message = "Path contains invalid characters or format"
    )
    private String path;

    private UUID parentFolderId;
}