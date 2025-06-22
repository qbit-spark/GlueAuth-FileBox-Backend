package org.qbitspark.glueauthfileboxbackend.files_mng_service.service;

import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.UsageSummaryResponse;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;

public interface UsageAnalyticsService {

    /**
     * Get comprehensive usage summary for authenticated user
     */
    UsageSummaryResponse getUserUsageSummary() throws ItemNotFoundException;

    /**
     * Get storage breakdown by file types
     */
    UsageSummaryResponse.StorageInfo getStorageBreakdown() throws ItemNotFoundException;

    /**
     * Get activity summary for specific time period
     */
    UsageSummaryResponse.ActivitySummary getActivitySummary(int days) throws ItemNotFoundException;

    /**
     * Get security status overview
     */
    UsageSummaryResponse.SecurityInfo getSecurityOverview() throws ItemNotFoundException;
}