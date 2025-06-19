package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class FileUploadRequest {
    @NotNull(message = "Folder ID is required")
    private UUID folderId;  // null = root level
}