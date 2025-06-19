package org.qbitspark.glueauthfileboxbackend.virus_scanner_service.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.enums.VirusScanStatus;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class VirusScanResult {
    private VirusScanStatus status;
    private String virusName;
    private String message;
    private long scanDurationMs;
    private String fileName;
}


