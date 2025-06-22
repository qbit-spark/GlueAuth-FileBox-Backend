package org.qbitspark.glueauthfileboxbackend.files_mng_service.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.AccountRepo;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.AccountEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.entity.FileEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.entity.FolderEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.enums.VirusScanStatus;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.UsageSummaryResponse;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.repo.FileRepository;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.repo.FolderRepository;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.service.UsageAnalyticsService;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UsageAnalyticsServiceImpl implements UsageAnalyticsService {

    private final AccountRepo accountRepo;
    private final FileRepository fileRepository;
    private final FolderRepository folderRepository;

    @Override
    @Transactional(readOnly = true)
    public UsageSummaryResponse getUserUsageSummary() throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();
        UUID userId = user.getId();

        log.info("Generating comprehensive usage summary for user: {}", user.getUserName());

        // Get all user data in one go
        List<FileEntity> allFiles = fileRepository.findByUserId(userId);
        List<FolderEntity> allFolders = folderRepository.findByUserIdOrderByCreatedAtDesc(userId);

        // Split files into active and deleted
        List<FileEntity> activeFiles = allFiles.stream()
                .filter(file -> !file.getIsDeleted())
                .toList();
        List<FileEntity> deletedFiles = allFiles.stream()
                .filter(FileEntity::getIsDeleted)
                .toList();

        // Build comprehensive summary
        return UsageSummaryResponse.builder()
                .userInfo(buildUserInfo(user))
                .storageInfo(buildStorageInfo(allFiles, activeFiles, deletedFiles))
                .fileStatistics(buildFileStatistics(allFiles, activeFiles, deletedFiles))
                .folderStatistics(buildFolderStatistics(allFolders, userId))
                .activitySummary(buildActivitySummary(allFiles))
                .fileTypeBreakdown(buildFileTypeBreakdown(activeFiles))
                .recentActivities(buildRecentActivities(allFiles))
                .securityInfo(buildSecurityInfo(activeFiles))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public UsageSummaryResponse.StorageInfo getStorageBreakdown() throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();
        UUID userId = user.getId();

        log.info("Getting storage breakdown for user: {}", user.getUserName());

        List<FileEntity> allFiles = fileRepository.findByUserId(userId);
        List<FileEntity> activeFiles = allFiles.stream()
                .filter(file -> !file.getIsDeleted())
                .toList();
        List<FileEntity> deletedFiles = allFiles.stream()
                .filter(FileEntity::getIsDeleted)
                .toList();

        return buildStorageInfo(allFiles, activeFiles, deletedFiles);
    }

    @Override
    @Transactional(readOnly = true)
    public UsageSummaryResponse.ActivitySummary getActivitySummary(int days) throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();
        UUID userId = user.getId();

        log.info("Getting activity summary for {} days for user: {}", days, user.getUserName());

        List<FileEntity> allFiles = fileRepository.findByUserId(userId);
        return buildActivitySummaryForPeriod(allFiles, days);
    }

    @Override
    @Transactional(readOnly = true)
    public UsageSummaryResponse.SecurityInfo getSecurityOverview() throws ItemNotFoundException {

        AccountEntity user = getAuthenticatedAccount();
        UUID userId = user.getId();

        log.info("Getting security overview for user: {}", user.getUserName());

        List<FileEntity> activeFiles = fileRepository.findByUserId(userId).stream()
                .filter(file -> !file.getIsDeleted())
                .toList();

        return buildSecurityInfo(activeFiles);
    }

    // ==================== BUILDER METHODS ====================

    private UsageSummaryResponse.UserInfo buildUserInfo(AccountEntity user) {
        return UsageSummaryResponse.UserInfo.builder()
                .userName(user.getUserName())
                .email(user.getEmail())
                .accountCreated(user.getCreatedAt())
                .lastLogin(user.getEditedAt())
                .isVerified(user.getIsVerified())
                .accountStatus(user.getIsVerified() ? "Active" : "Pending Verification")
                .build();
    }

    private UsageSummaryResponse.StorageInfo buildStorageInfo(List<FileEntity> allFiles,
                                                              List<FileEntity> activeFiles,
                                                              List<FileEntity> deletedFiles) {

        long totalStorageUsed = allFiles.stream().mapToLong(FileEntity::getFileSize).sum();
        long activeStorageUsed = activeFiles.stream().mapToLong(FileEntity::getFileSize).sum();
        long trashStorageUsed = deletedFiles.stream().mapToLong(FileEntity::getFileSize).sum();

        // Configurable storage quota (5GB default)
        long storageQuota = 5L * 1024 * 1024 * 1024;
        long availableStorage = Math.max(0, storageQuota - activeStorageUsed);
        double usagePercentage = storageQuota > 0 ? (double) activeStorageUsed / storageQuota * 100 : 0;

        return UsageSummaryResponse.StorageInfo.builder()
                .totalStorageUsed(totalStorageUsed)
                .totalStorageUsedFormatted(formatFileSize(totalStorageUsed))
                .activeStorageUsed(activeStorageUsed)
                .activeStorageUsedFormatted(formatFileSize(activeStorageUsed))
                .trashStorageUsed(trashStorageUsed)
                .trashStorageUsedFormatted(formatFileSize(trashStorageUsed))
                .storageQuota(storageQuota)
                .storageQuotaFormatted(formatFileSize(storageQuota))
                .availableStorage(availableStorage)
                .availableStorageFormatted(formatFileSize(availableStorage))
                .storageUsagePercentage(Math.round(usagePercentage * 100.0) / 100.0)
                .build();
    }

    private UsageSummaryResponse.FileStatistics buildFileStatistics(List<FileEntity> allFiles,
                                                                    List<FileEntity> activeFiles,
                                                                    List<FileEntity> deletedFiles) {

        Map<VirusScanStatus, Long> scanCounts = activeFiles.stream()
                .collect(Collectors.groupingBy(FileEntity::getScanStatus, Collectors.counting()));

        OptionalDouble avgSize = activeFiles.stream().mapToLong(FileEntity::getFileSize).average();
        Optional<FileEntity> largest = activeFiles.stream().max(Comparator.comparing(FileEntity::getFileSize));
        Optional<FileEntity> smallest = activeFiles.stream()
                .filter(file -> file.getFileSize() > 0)
                .min(Comparator.comparing(FileEntity::getFileSize));

        return UsageSummaryResponse.FileStatistics.builder()
                .totalFiles(allFiles.size())
                .activeFiles(activeFiles.size())
                .deletedFiles(deletedFiles.size())
                .cleanFiles(scanCounts.getOrDefault(VirusScanStatus.CLEAN, 0L).intValue())
                .infectedFiles(scanCounts.getOrDefault(VirusScanStatus.INFECTED, 0L).intValue())
                .pendingScanFiles(scanCounts.getOrDefault(VirusScanStatus.PENDING, 0L).intValue())
                .averageFileSize(avgSize.orElse(0.0))
                .averageFileSizeFormatted(avgSize.isPresent() ? formatFileSize((long) avgSize.getAsDouble()) : "0 B")
                .largestFile(largest.map(this::convertToFileEntity).orElse(null))
                .smallestFile(smallest.map(this::convertToFileEntity).orElse(null))
                .build();
    }

    private UsageSummaryResponse.FolderStatistics buildFolderStatistics(List<FolderEntity> allFolders, UUID userId) {

        List<FolderEntity> rootFolders = allFolders.stream()
                .filter(folder -> folder.getParentFolder() == null)
                .toList();

        int maxDepth = allFolders.stream()
                .mapToInt(folder -> folder.getFullPath().split("/").length)
                .max()
                .orElse(0);

        Optional<FolderEntity> largestFolder = findLargestFolder(allFolders, userId);
        Optional<FolderEntity> mostRecentFolder = allFolders.stream()
                .max(Comparator.comparing(FolderEntity::getCreatedAt));

        return UsageSummaryResponse.FolderStatistics.builder()
                .totalFolders(allFolders.size())
                .rootLevelFolders(rootFolders.size())
                .maxFolderDepth(maxDepth)
                .largestFolder(largestFolder.map(folder -> convertToFolderInfo(folder, userId)).orElse(null))
                .mostRecentFolder(mostRecentFolder.map(folder -> convertToFolderInfo(folder, userId)).orElse(null))
                .build();
    }

    private UsageSummaryResponse.ActivitySummary buildActivitySummary(List<FileEntity> allFiles) {

        Optional<LocalDateTime> firstUpload = allFiles.stream()
                .map(FileEntity::getCreatedAt)
                .min(LocalDateTime::compareTo);

        Optional<LocalDateTime> lastUpload = allFiles.stream()
                .map(FileEntity::getCreatedAt)
                .max(LocalDateTime::compareTo);

        LocalDateTime weekAgo = LocalDateTime.now().minusWeeks(1);
        LocalDateTime monthAgo = LocalDateTime.now().minusMonths(1);

        int uploadsThisWeek = (int) allFiles.stream()
                .filter(file -> file.getCreatedAt().isAfter(weekAgo))
                .count();

        int uploadsThisMonth = (int) allFiles.stream()
                .filter(file -> file.getCreatedAt().isAfter(monthAgo))
                .count();

        return UsageSummaryResponse.ActivitySummary.builder()
                .firstUpload(firstUpload.orElse(null))
                .lastUpload(lastUpload.orElse(null))
                .totalUploads(allFiles.size())
                .uploadsThisWeek(uploadsThisWeek)
                .uploadsThisMonth(uploadsThisMonth)
                .totalDownloads(0) // TODO: Implement download tracking
                .downloadsThisWeek(0)
                .downloadsThisMonth(0)
                .activityByDay(buildDailyActivityMap(allFiles, 7))
                .build();
    }

    private UsageSummaryResponse.ActivitySummary buildActivitySummaryForPeriod(List<FileEntity> allFiles, int days) {

        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(days);
        List<FileEntity> recentFiles = allFiles.stream()
                .filter(file -> file.getCreatedAt().isAfter(cutoffDate))
                .toList();

        Optional<LocalDateTime> firstUpload = recentFiles.stream()
                .map(FileEntity::getCreatedAt)
                .min(LocalDateTime::compareTo);

        Optional<LocalDateTime> lastUpload = recentFiles.stream()
                .map(FileEntity::getCreatedAt)
                .max(LocalDateTime::compareTo);

        return UsageSummaryResponse.ActivitySummary.builder()
                .firstUpload(firstUpload.orElse(null))
                .lastUpload(lastUpload.orElse(null))
                .totalUploads(recentFiles.size())
                .uploadsThisWeek(recentFiles.size())
                .uploadsThisMonth(recentFiles.size())
                .totalDownloads(0)
                .downloadsThisWeek(0)
                .downloadsThisMonth(0)
                .activityByDay(buildDailyActivityMap(recentFiles, days))
                .build();
    }

    private List<UsageSummaryResponse.FileTypeBreakdown> buildFileTypeBreakdown(List<FileEntity> activeFiles) {

        Map<String, List<FileEntity>> filesByCategory = activeFiles.stream()
                .collect(Collectors.groupingBy(file -> getFileCategory(file.getMimeType())));

        long totalSize = activeFiles.stream().mapToLong(FileEntity::getFileSize).sum();

        return filesByCategory.entrySet().stream()
                .map(entry -> {
                    String category = entry.getKey();
                    List<FileEntity> files = entry.getValue();
                    long categorySize = files.stream().mapToLong(FileEntity::getFileSize).sum();
                    double percentage = totalSize > 0 ? (double) categorySize / totalSize * 100 : 0;

                    return UsageSummaryResponse.FileTypeBreakdown.builder()
                            .fileType(category)
                            .category(category)
                            .count(files.size())
                            .totalSize(categorySize)
                            .totalSizeFormatted(formatFileSize(categorySize))
                            .percentage(Math.round(percentage * 100.0) / 100.0)
                            .build();
                })
                .sorted((a, b) -> Long.compare(b.getTotalSize(), a.getTotalSize()))
                .toList();
    }

    private List<UsageSummaryResponse.RecentActivity> buildRecentActivities(List<FileEntity> allFiles) {

        return allFiles.stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(10)
                .map(file -> UsageSummaryResponse.RecentActivity.builder()
                        .activityType("UPLOAD")
                        .fileName(file.getFileName())
                        .fileId(file.getFileId().toString())
                        .timestamp(file.getCreatedAt())
                        .details(file.getIsDeleted() ? "File in trash" : "Active file")
                        .build())
                .toList();
    }

    private UsageSummaryResponse.SecurityInfo buildSecurityInfo(List<FileEntity> activeFiles) {

        Map<VirusScanStatus, Long> scanCounts = activeFiles.stream()
                .collect(Collectors.groupingBy(FileEntity::getScanStatus, Collectors.counting()));

        Optional<LocalDateTime> lastScan = activeFiles.stream()
                .map(FileEntity::getUpdatedAt)
                .max(LocalDateTime::compareTo);

        List<UsageSummaryResponse.SecurityAlert> alerts = buildSecurityAlerts(activeFiles);

        return UsageSummaryResponse.SecurityInfo.builder()
                .totalScannedFiles(activeFiles.size())
                .cleanFiles(scanCounts.getOrDefault(VirusScanStatus.CLEAN, 0L).intValue())
                .infectedFiles(scanCounts.getOrDefault(VirusScanStatus.INFECTED, 0L).intValue())
                .failedScans(scanCounts.getOrDefault(VirusScanStatus.FAILED, 0L).intValue())
                .pendingScans(scanCounts.getOrDefault(VirusScanStatus.PENDING, 0L).intValue())
                .lastScanDate(lastScan.orElse(null))
                .securityAlerts(alerts)
                .build();
    }

    // ==================== HELPER METHODS ====================

    private UsageSummaryResponse.FileEntity convertToFileEntity(FileEntity file) {
        return UsageSummaryResponse.FileEntity.builder()
                .fileName(file.getFileName())
                .fileSize(file.getFileSize())
                .fileSizeFormatted(formatFileSize(file.getFileSize()))
                .createdAt(file.getCreatedAt())
                .build();
    }

    private UsageSummaryResponse.FolderInfo convertToFolderInfo(FolderEntity folder, UUID userId) {
        long fileCount = fileRepository.countByUserIdAndFolder_FolderIdAndIsDeletedFalse(userId, folder.getFolderId());
        long totalSize = calculateFolderSize(folder, userId);

        return UsageSummaryResponse.FolderInfo.builder()
                .folderName(folder.getFolderName())
                .fullPath(folder.getFullPath())
                .fileCount((int) fileCount)
                .totalSize(totalSize)
                .totalSizeFormatted(formatFileSize(totalSize))
                .createdAt(folder.getCreatedAt())
                .build();
    }

    private Optional<FolderEntity> findLargestFolder(List<FolderEntity> folders, UUID userId) {
        return folders.stream()
                .max(Comparator.comparing(folder -> calculateFolderSize(folder, userId)));
    }

    private long calculateFolderSize(FolderEntity folder, UUID userId) {
        return fileRepository.findByUserIdAndFolder_FolderIdAndIsDeletedFalse(userId, folder.getFolderId())
                .stream()
                .mapToLong(FileEntity::getFileSize)
                .sum();
    }

    private Map<String, Integer> buildDailyActivityMap(List<FileEntity> files, int days) {
        Map<String, Integer> activityMap = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();

        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            String dateKey = date.format(DateTimeFormatter.ofPattern("MMM dd"));

            int count = (int) files.stream()
                    .filter(file -> file.getCreatedAt().toLocalDate().equals(date))
                    .count();

            activityMap.put(dateKey, count);
        }

        return activityMap;
    }

    private List<UsageSummaryResponse.SecurityAlert> buildSecurityAlerts(List<FileEntity> files) {
        List<UsageSummaryResponse.SecurityAlert> alerts = new ArrayList<>();

        // High priority: Infected files
        files.stream()
                .filter(file -> file.getScanStatus() == VirusScanStatus.INFECTED)
                .forEach(file -> alerts.add(
                        UsageSummaryResponse.SecurityAlert.builder()
                                .alertType("VIRUS_DETECTED")
                                .message("Virus detected in file")
                                .fileName(file.getFileName())
                                .timestamp(file.getUpdatedAt())
                                .severity("HIGH")
                                .build()
                ));

        // Medium priority: Failed scans
        long failedScans = files.stream()
                .filter(file -> file.getScanStatus() == VirusScanStatus.FAILED)
                .count();

        if (failedScans > 0) {
            alerts.add(UsageSummaryResponse.SecurityAlert.builder()
                    .alertType("SCAN_FAILURES")
                    .message(failedScans + " file(s) failed virus scanning")
                    .fileName(null)
                    .timestamp(LocalDateTime.now())
                    .severity("MEDIUM")
                    .build());
        }

        return alerts;
    }

    private String getFileCategory(String mimeType) {
        if (mimeType == null) return "unknown";

        if (mimeType.startsWith("image/")) return "image";
        if (mimeType.startsWith("video/")) return "video";
        if (mimeType.startsWith("audio/")) return "audio";
        if (mimeType.contains("pdf")) return "document";
        if (mimeType.contains("word") || mimeType.contains("document")) return "document";
        if (mimeType.contains("sheet") || mimeType.contains("excel")) return "spreadsheet";
        if (mimeType.contains("presentation") || mimeType.contains("powerpoint")) return "presentation";
        if (mimeType.contains("zip") || mimeType.contains("archive")) return "archive";
        if (mimeType.startsWith("text/")) return "text";

        return "file";
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format("%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private AccountEntity getAuthenticatedAccount() throws ItemNotFoundException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.isAuthenticated()) {
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String userName = userDetails.getUsername();
            return accountRepo.findByUserName(userName)
                    .orElseThrow(() -> new ItemNotFoundException("User not found: " + userName));
        }

        throw new ItemNotFoundException("User is not authenticated");
    }
}