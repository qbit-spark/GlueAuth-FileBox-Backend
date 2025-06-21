package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;

@Data
@Builder
public class BatchUploadStatus {
    private String batchId;
    private int totalFiles;
    private int completedFiles;
    private int failedFiles;
    private double overallProgress;
    private String status; // QUEUED, PROCESSING, COMPLETED, FAILED, PARTIAL
    private LocalDateTime startTime;
    private LocalDateTime lastUpdated;
    @Builder.Default
    private Map<String, UploadStatus> files = new ConcurrentHashMap<>(); // uploadId -> UploadStatus
    @Builder.Default
    private List<FileUploadResponse> completedUploads = new ArrayList<>();
    private String message;

    public void updateOverallProgress() {
        if (totalFiles == 0) {
            overallProgress = 0;
            return;
        }

        double totalProgress = files.values().stream()
                .mapToDouble(UploadStatus::getProgress)
                .sum();

        overallProgress = totalProgress / totalFiles;

        // Update status based on progress
        if (completedFiles == totalFiles) {
            status = failedFiles > 0 ? "PARTIAL" : "COMPLETED";
        } else if (completedFiles > 0 || files.values().stream().anyMatch(f -> f.getProgress() > 0)) {
            status = "PROCESSING";
        }

        lastUpdated = LocalDateTime.now();
    }
}