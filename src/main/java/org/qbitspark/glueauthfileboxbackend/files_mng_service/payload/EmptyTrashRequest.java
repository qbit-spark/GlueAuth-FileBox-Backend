package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class EmptyTrashRequest {

    // Optional: specific file IDs to permanently delete
    // If empty or null, all trash files for user will be deleted
    private List<UUID> fileIds;

    // Force deletion even if some files fail
    private boolean continueOnError = true;
}