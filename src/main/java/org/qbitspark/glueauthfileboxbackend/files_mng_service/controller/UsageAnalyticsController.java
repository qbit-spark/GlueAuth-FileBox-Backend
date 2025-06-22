package org.qbitspark.glueauthfileboxbackend.files_mng_service.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.QuickStatsResponse;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.UsageSummaryResponse;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.service.UsageAnalyticsService;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.qbitspark.glueauthfileboxbackend.globeresponsebody.GlobeSuccessResponseBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Slf4j
public class UsageAnalyticsController {

    private final UsageAnalyticsService usageAnalyticsService;

    /**
     * Get comprehensive usage summary for authenticated user
     */
    @GetMapping("/usage-summary")
    public ResponseEntity<GlobeSuccessResponseBuilder> getUserUsageSummary() throws ItemNotFoundException {

        log.info("Request for comprehensive usage summary");

        UsageSummaryResponse response = usageAnalyticsService.getUserUsageSummary();

        return ResponseEntity.ok(
                GlobeSuccessResponseBuilder.success("Usage summary retrieved successfully", response)
        );
    }

    /**
     * Get storage breakdown and quota information
     */
    @GetMapping("/storage-breakdown")
    public ResponseEntity<GlobeSuccessResponseBuilder> getStorageBreakdown() throws ItemNotFoundException {

        log.info("Request for storage breakdown");

        UsageSummaryResponse.StorageInfo response = usageAnalyticsService.getStorageBreakdown();

        return ResponseEntity.ok(
                GlobeSuccessResponseBuilder.success("Storage breakdown retrieved successfully", response)
        );
    }

    /**
     * Get activity summary for specific time period
     */
    @GetMapping("/activity-summary")
    public ResponseEntity<GlobeSuccessResponseBuilder> getActivitySummary(
            @RequestParam(defaultValue = "30") int days) throws ItemNotFoundException {

        log.info("Request for activity summary - {} days", days);

        // Validate days parameter
        if (days < 1 || days > 365) {
            throw new RuntimeException("Days parameter must be between 1 and 365");
        }

        UsageSummaryResponse.ActivitySummary response = usageAnalyticsService.getActivitySummary(days);

        return ResponseEntity.ok(
                GlobeSuccessResponseBuilder.success(
                        String.format("Activity summary for %d days retrieved successfully", days),
                        response)
        );
    }

    /**
     * Get security overview and virus scan status
     */
    @GetMapping("/security-overview")
    public ResponseEntity<GlobeSuccessResponseBuilder> getSecurityOverview() throws ItemNotFoundException {

        log.info("Request for security overview");

        UsageSummaryResponse.SecurityInfo response = usageAnalyticsService.getSecurityOverview();

        return ResponseEntity.ok(
                GlobeSuccessResponseBuilder.success("Security overview retrieved successfully", response)
        );
    }


    /**
     * Get quick stats - lightweight endpoint for dashboard widgets
     */
    @GetMapping("/quick-stats")
    public ResponseEntity<GlobeSuccessResponseBuilder> getQuickStats() throws ItemNotFoundException {

        log.info("Request for quick stats");

        // Get basic storage and file info
        UsageSummaryResponse.StorageInfo storageInfo = usageAnalyticsService.getStorageBreakdown();
        UsageSummaryResponse.SecurityInfo securityInfo = usageAnalyticsService.getSecurityOverview();

        // Build a lightweight response
        QuickStatsResponse quickStats = QuickStatsResponse.builder()
                .totalStorageUsed(storageInfo.getTotalStorageUsed())
                .totalStorageUsedFormatted(storageInfo.getTotalStorageUsedFormatted())
                .storageUsagePercentage(storageInfo.getStorageUsagePercentage())
                .availableStorage(storageInfo.getAvailableStorage())
                .availableStorageFormatted(storageInfo.getAvailableStorageFormatted())
                .totalFiles(securityInfo.getTotalScannedFiles())
                .cleanFiles(securityInfo.getCleanFiles())
                .infectedFiles(securityInfo.getInfectedFiles())
                .pendingScans(securityInfo.getPendingScans())
                .hasSecurityAlerts(!securityInfo.getSecurityAlerts().isEmpty())
                .build();

        return ResponseEntity.ok(
                GlobeSuccessResponseBuilder.success("Quick stats retrieved successfully", quickStats)
        );
    }

}