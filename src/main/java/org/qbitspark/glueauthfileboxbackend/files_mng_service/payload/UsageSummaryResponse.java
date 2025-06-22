package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UsageSummaryResponse {

    // User Information
    private UserInfo userInfo;

    // Storage Statistics
    private StorageInfo storageInfo;

    // File Statistics
    private FileStatistics fileStatistics;

    // Folder Statistics
    private FolderStatistics folderStatistics;

    // Activity Summary
    private ActivitySummary activitySummary;

    // File Type Breakdown
    private List<FileTypeBreakdown> fileTypeBreakdown;

    // Recent Activity
    private List<RecentActivity> recentActivities;

    // Security & Scan Status
    private SecurityInfo securityInfo;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class UserInfo {
        private String userName;
        private String email;
        private LocalDateTime accountCreated;
        private LocalDateTime lastLogin;
        private boolean isVerified;
        private String accountStatus;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StorageInfo {
        private long totalStorageUsed; // bytes
        private String totalStorageUsedFormatted;
        private long trashStorageUsed; // bytes
        private String trashStorageUsedFormatted;
        private long activeStorageUsed; // bytes
        private String activeStorageUsedFormatted;
        private Double storageUsagePercentage;
        private long storageQuota; // bytes (if applicable)
        private String storageQuotaFormatted;
        private long availableStorage; // bytes
        private String availableStorageFormatted;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FileStatistics {
        private int totalFiles;
        private int activeFiles;
        private int deletedFiles; // in trash
        private int cleanFiles;
        private int infectedFiles;
        private int pendingScanFiles;
        private Double averageFileSize;
        private String averageFileSizeFormatted;
        private FileEntity largestFile;
        private FileEntity smallestFile;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FileEntity {
        private String fileName;
        private long fileSize;
        private String fileSizeFormatted;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FolderStatistics {
        private int totalFolders;
        private int rootLevelFolders;
        private int maxFolderDepth;
        private FolderInfo largestFolder;
        private FolderInfo mostRecentFolder;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FolderInfo {
        private String folderName;
        private String fullPath;
        private int fileCount;
        private long totalSize;
        private String totalSizeFormatted;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ActivitySummary {
        private LocalDateTime firstUpload;
        private LocalDateTime lastUpload;
        private int totalUploads;
        private int uploadsThisWeek;
        private int uploadsThisMonth;
        private int totalDownloads;
        private int downloadsThisWeek;
        private int downloadsThisMonth;
        private Map<String, Integer> activityByDay; // last 7 days
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FileTypeBreakdown {
        private String fileType;
        private String category;
        private int count;
        private long totalSize;
        private String totalSizeFormatted;
        private Double percentage;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RecentActivity {
        private String activityType; // UPLOAD, DOWNLOAD, DELETE, MOVE, COPY
        private String fileName;
        private String fileId;
        private LocalDateTime timestamp;
        private String details;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SecurityInfo {
        private int totalScannedFiles;
        private int cleanFiles;
        private int infectedFiles;
        private int failedScans;
        private int pendingScans;
        private LocalDateTime lastScanDate;
        private List<SecurityAlert> securityAlerts;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SecurityAlert {
        private String alertType;
        private String message;
        private String fileName;
        private LocalDateTime timestamp;
        private String severity; // LOW, MEDIUM, HIGH
    }
}