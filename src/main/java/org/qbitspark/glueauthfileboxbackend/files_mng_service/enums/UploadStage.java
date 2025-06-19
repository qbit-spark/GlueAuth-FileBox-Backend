package org.qbitspark.glueauthfileboxbackend.files_mng_service.enums;

import lombok.Getter;

@Getter
public enum UploadStage {
    VALIDATING("Validating file..."),
    VIRUS_SCANNING("Scanning for viruses..."),
    UPLOADING_STORAGE("Uploading to storage..."),
    SAVING_DATABASE("Saving to database..."),
    COMPLETED("Upload completed"),
    FAILED("Upload failed");

    private final String description;

    UploadStage(String description) {
        this.description = description;
    }

}