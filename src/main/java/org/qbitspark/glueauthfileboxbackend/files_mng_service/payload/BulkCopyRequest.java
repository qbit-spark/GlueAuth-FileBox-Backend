package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BulkCopyRequest {

    @NotEmpty(message = "At least one file must be selected for copying")
    private List<UUID> fileIds;

    private UUID destinationFolderId; // null means root folder
}