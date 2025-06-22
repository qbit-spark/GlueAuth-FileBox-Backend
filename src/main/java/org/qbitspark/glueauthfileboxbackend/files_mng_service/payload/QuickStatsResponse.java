package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public  class QuickStatsResponse {
    private long totalStorageUsed;
    private String totalStorageUsedFormatted;
    private Double storageUsagePercentage;
    private long availableStorage;
    private String availableStorageFormatted;
    private int totalFiles;
    private int cleanFiles;
    private int infectedFiles;
    private int pendingScans;
    private boolean hasSecurityAlerts;
}
