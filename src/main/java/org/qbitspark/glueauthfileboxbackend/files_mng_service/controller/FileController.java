package org.qbitspark.glueauthfileboxbackend.files_mng_service.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.AccountRepo;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.AccountEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.entity.FolderEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.*;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.repo.FolderRepository;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.service.FileService;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.RandomExceptions;
import org.qbitspark.glueauthfileboxbackend.globeresponsebody.GlobeSuccessResponseBuilder;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Slf4j
public class FileController {

    private final FileService fileService;

    // Constants for better maintainability
    private static final long SSE_TIMEOUT = 300_000L; // 5 minutes
    private static final long MAX_MONITORING_TIME = 240_000L; // 4 minutes (leave buffer)
    private static final int POLL_INTERVAL_SECONDS = 1;
    private static final int MAX_CONSECUTIVE_ERRORS = 5;
    private final AccountRepo accountRepo;
    private final FolderRepository folderRepository;

    @PostMapping("/upload")
    public ResponseEntity<FileUploadResponse> uploadFile(
            @RequestParam(value = "folderId", required = false) UUID folderId,
            @RequestParam("file") MultipartFile file) throws ItemNotFoundException {

        FileUploadResponse response = fileService.uploadFile(folderId, file);
        return ResponseEntity.ok(response);
    }


    @PostMapping("/upload-async")
    public ResponseEntity<Map<String, String>> uploadFileAsync(
            @RequestParam(value = "folderId", required = false) UUID folderId,
            @RequestParam("file") MultipartFile file) {

        try {
            String uploadId = UUID.randomUUID().toString();

            // Get authenticated user
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            AccountEntity authenticatedUser = extractAccount(authentication);
            UUID userId = authenticatedUser.getId();

            // Get folder data
            String folderPath = "";
            if (folderId != null) {
                FolderEntity folder = folderRepository.findById(folderId)
                        .orElseThrow(() -> new ItemNotFoundException("Folder not found"));

                if (!folder.getUserId().equals(userId)) {
                    throw new RuntimeException("Access denied: You don't own this folder");
                }

                folderPath = folder.getFullPath();
            }

            // Convert MultipartFile to FileData
            FileData fileData = FileData.builder()
                    .content(file.getBytes())
                    .originalFileName(file.getOriginalFilename())
                    .contentType(file.getContentType())
                    .size(file.getSize())
                    .build();

            // Start async processing
            fileService.uploadFileAsync(folderId, fileData, uploadId, userId, folderPath);

            Map<String, String> response = new HashMap<>();
            response.put("uploadId", uploadId);
            response.put("message", "Upload started in background");
            response.put("statusUrl", "/api/v1/files/upload-status/" + uploadId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to start async upload: {}", e.getMessage());
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to start upload: " + e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }


    @GetMapping("/upload-status/{uploadId}")
    public ResponseEntity<UploadStatus> getUploadStatus(@PathVariable String uploadId) throws RandomExceptions {
        UploadStatus status = fileService.getUploadStatus(uploadId);

        if (status == null) {
            throw new RandomExceptions("The task do not exit or ready flushed");
        }

        return ResponseEntity.ok(status);
    }

    @GetMapping("/upload-status/{uploadId}/stream")
    public SseEmitter streamUploadStatus(@PathVariable String uploadId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        // Set up error handlers
        emitter.onError(throwable -> {
            log.warn("SSE error for upload {}: {}", uploadId, throwable.getMessage());
        });

        emitter.onTimeout(() -> {
            log.warn("SSE timeout for upload {}", uploadId);
        });

        CompletableFuture.runAsync(() -> monitorUploadStatus(uploadId, emitter));

        return emitter;
    }


    /**
     * Batch upload multiple files with individual tracking
     */
    @PostMapping("/upload-batch")
    public ResponseEntity<Map<String, Object>> uploadFilesBatch(
            @RequestParam(value = "folderId", required = false) UUID folderId,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "maxConcurrentUploads", defaultValue = "3") int maxConcurrentUploads,
            @RequestParam(value = "stopOnFirstError", defaultValue = "false") boolean stopOnFirstError,
            @RequestParam(value = "allowDuplicates", defaultValue = "false") boolean allowDuplicates) throws RandomExceptions, ExecutionException, InterruptedException, ItemNotFoundException {

        // Get authenticated user
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AccountEntity authenticatedUser = extractAccount(authentication);
        UUID userId = authenticatedUser.getId();

        // Get folder data
        String folderPath = "";
        if (folderId != null) {
            FolderEntity folder = folderRepository.findById(folderId)
                    .orElseThrow(() -> new ItemNotFoundException("Folder not found"));

            if (!folder.getUserId().equals(userId)) {
                throw new RuntimeException("Access denied: You don't own this folder");
            }

            folderPath = folder.getFullPath();
        }



        // Validate input
        if (files == null || files.isEmpty()) {
            throw new RandomExceptions("No files provided");
        }

        if (files.size() > 50) { // Reasonable limit
            throw new RandomExceptions("Too many files. Maximum 50 files per batch.");
        }

        // Validate individual files
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                throw new RandomExceptions("One or more files are empty");
            }
        }

        // Create batch upload options
        BatchUploadOptions options = BatchUploadOptions.builder()
                .maxConcurrentUploads(Math.min(maxConcurrentUploads, 10)) // Cap at 10
                .stopOnFirstError(stopOnFirstError)
                .allowDuplicates(allowDuplicates)
                .virusScanTimeout(30000)
                .build();

        // Start batch upload
        CompletableFuture<String> batchFuture = fileService.uploadFilesBatch(folderId, files, options, userId, folderPath);
        String batchId = batchFuture.get(); // This returns immediately with batchId

        Map<String, Object> response = new HashMap<>();
        response.put("batchId", batchId);
        response.put("totalFiles", files.size());
        response.put("message", "Batch upload started");
        response.put("statusUrl", "/api/v1/files/batch-status/" + batchId);
        response.put("streamUrl", "/api/v1/files/batch-status/" + batchId + "/stream");

        return ResponseEntity.ok(response);

    }

    /**
     * Get batch upload status
     */
    @GetMapping("/batch-status/{batchId}")
    public ResponseEntity<BatchUploadStatus> getBatchStatus(@PathVariable String batchId) throws RandomExceptions {
        BatchUploadStatus status = fileService.getBatchUploadStatus(batchId);

        if (status == null) {
            throw new RandomExceptions("Batch not found or was already cleaned");
        }

        return ResponseEntity.ok(status);
    }

    /**
     * Stream batch upload progress via SSE
     */
    @GetMapping("/batch-status/{batchId}/stream")
    public SseEmitter streamBatchStatus(@PathVariable String batchId) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 minutes for batch uploads

        emitter.onError(throwable -> {
            log.warn("SSE error for batch {}: {}", batchId, throwable.getMessage());
        });

        emitter.onTimeout(() -> {
            log.warn("SSE timeout for batch {}", batchId);
        });

        CompletableFuture.runAsync(() -> monitorBatchStatus(batchId, emitter));

        return emitter;
    }

    /**
     * Get individual file status within a batch
     */
    @GetMapping("/batch-status/{batchId}/file/{uploadId}")
    public ResponseEntity<UploadStatus> getFileStatusInBatch(
            @PathVariable String batchId,
            @PathVariable String uploadId) {

        BatchUploadStatus batchStatus = fileService.getBatchUploadStatus(batchId);
        if (batchStatus == null) {
            return ResponseEntity.notFound().build();
        }

        UploadStatus fileStatus = batchStatus.getFiles().get(uploadId);
        if (fileStatus == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(fileStatus);
    }

    /**
     * Cancel a batch upload (the best effort)
     */
    @PostMapping("/batch-status/{batchId}/cancel")
    public ResponseEntity<Map<String, String>> cancelBatchUpload(@PathVariable String batchId) throws RandomExceptions {
        BatchUploadStatus status = fileService.getBatchUploadStatus(batchId);

        if (status == null) {
            throw new RandomExceptions("Batch not found");
        }

        if ("COMPLETED".equals(status.getStatus()) || "FAILED".equals(status.getStatus())) {
            throw new RandomExceptions("Batch already completed or failed");
        }

        // Note: Actual cancellation would require additional implementation
        // to interrupt running tasks - this is a placeholder
        Map<String, String> response = new HashMap<>();
        response.put("message", "Cancellation requested (some files may still complete)");
        return ResponseEntity.ok(response);
    }

    /**
     * Cleanup batch status
     */
    @DeleteMapping("/batch-status/{batchId}")
    public ResponseEntity<Void> cleanupBatchStatus(@PathVariable String batchId) {
        fileService.cleanupBatchUploadStatus(batchId);
        return ResponseEntity.noContent().build();
    }


    @GetMapping("/{fileId}")
    public ResponseEntity<GlobeSuccessResponseBuilder> getFileInfo(@PathVariable UUID fileId) throws ItemNotFoundException {

        log.info("Getting file info for: {}", fileId);

        FileInfoResponse response = fileService.getFileInfo(fileId);

        log.info("File info retrieved: {}", response.getName());

        return ResponseEntity.ok(
                GlobeSuccessResponseBuilder.success("File info retrieved successfully", response)
        );
    }

    @GetMapping("/{fileId}/download")
    public ResponseEntity<InputStreamResource> downloadFile(@PathVariable UUID fileId) throws ItemNotFoundException {

        log.info("Download request for file: {}", fileId);

        return fileService.downloadFile(fileId);
    }


    @GetMapping("/{fileId}/preview")
    public ResponseEntity<InputStreamResource> previewFile(@PathVariable UUID fileId) throws ItemNotFoundException {

        log.info("Preview request for file: {}", fileId);

        return fileService.previewFile(fileId);
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<GlobeSuccessResponseBuilder> deleteFile(@PathVariable UUID fileId) throws ItemNotFoundException {

        log.info("Delete request for file: {}", fileId);

        fileService.deleteFile(fileId);

        return ResponseEntity.ok(
                GlobeSuccessResponseBuilder.success("File moved to trash successfully")
        );
    }

    @PostMapping("/{fileId}/restore")
    public ResponseEntity<GlobeSuccessResponseBuilder> restoreFile(@PathVariable UUID fileId) throws ItemNotFoundException {

        log.info("Restore request for file: {}", fileId);

        fileService.restoreFile(fileId);

        return ResponseEntity.ok(
                GlobeSuccessResponseBuilder.success("File restored successfully")
        );
    }


    @GetMapping("/search")
    public ResponseEntity<GlobeSuccessResponseBuilder> searchItems(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) throws ItemNotFoundException {

        log.info("Search request: '{}' (page: {}, size: {})", q, page, size);

        int limitedSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, limitedSize);

        SearchResponse response = fileService.searchItems(q, pageable);

        log.info("Search '{}' returned {} folders and {} files",
                q, response.getSummary().getTotalFolders(), response.getSummary().getTotalFiles());

        return ResponseEntity.ok(
                GlobeSuccessResponseBuilder.success("Search completed successfully", response)
        );
    }


    @GetMapping("/search-in-folder")
    public ResponseEntity<GlobeSuccessResponseBuilder> searchItemsInFolder(
            @RequestParam(required = false) UUID folderId,
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) throws ItemNotFoundException {

        log.info("Folder search request: '{}' in folder {} (page: {}, size: {})", q, folderId, page, size);

        int limitedSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, limitedSize);

        SearchResponse response = fileService.searchItemsInFolder(folderId, q, pageable);

        log.info("Folder search '{}' returned {} folders and {} files",
                q, response.getSummary().getTotalFolders(), response.getSummary().getTotalFiles());

        return ResponseEntity.ok(
                GlobeSuccessResponseBuilder.success("Folder search completed successfully", response)
        );
    }


    // Private helper methods for batch monitoring
    private void monitorBatchStatus(String batchId, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        BatchUploadStatus lastBatchStatus = null;
        int consecutiveErrors = 0;
        final long MAX_MONITORING_TIME = 540_000L; // 9 minutes (leave buffer)
        final int POLL_INTERVAL_SECONDS = 2; // Slower polling for batch

        try {
            BatchUploadStatus initialStatus = fileService.getBatchUploadStatus(batchId);
            if (initialStatus != null) {
                sendBatchProgressUpdate(emitter, initialStatus);
                lastBatchStatus = initialStatus;

                if (isBatchFinalStatus(initialStatus)) {
                    sendBatchCompletion(emitter, initialStatus);
                    emitter.complete();
                    scheduleBatchCleanup(batchId);
                    return;
                }
            } else {
                sendBatchError(emitter, "Batch not found");
                emitter.complete();
                return;
            }

            while (!Thread.currentThread().isInterrupted()) {
                long elapsedTime = System.currentTimeMillis() - startTime;
                if (elapsedTime > MAX_MONITORING_TIME) {
                    log.warn("SSE monitoring exceeded time limit for batch: {}", batchId);
                    sendBatchError(emitter, "Monitoring time limit exceeded");
                    break;
                }

                try {
                    BatchUploadStatus currentStatus = fileService.getBatchUploadStatus(batchId);

                    if (currentStatus == null) {
                        consecutiveErrors++;
                        if (consecutiveErrors >= 5) {
                            sendBatchError(emitter, "Batch status unavailable");
                            break;
                        }
                        TimeUnit.SECONDS.sleep(POLL_INTERVAL_SECONDS);
                        continue;
                    }

                    consecutiveErrors = 0;

                    if (shouldSendBatchUpdate(lastBatchStatus, currentStatus)) {
                        sendBatchProgressUpdate(emitter, currentStatus);
                        lastBatchStatus = currentStatus;
                    }

                    if (isBatchFinalStatus(currentStatus)) {
                        sendBatchCompletion(emitter, currentStatus);
                        break;
                    }

                    TimeUnit.SECONDS.sleep(POLL_INTERVAL_SECONDS);

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.info("SSE monitoring interrupted for batch: {}", batchId);
                    break;
                } catch (Exception e) {
                    consecutiveErrors++;
                    log.error("Error monitoring batch {} (attempt {}): {}", batchId, consecutiveErrors, e.getMessage());

                    if (consecutiveErrors >= 5) {
                        sendBatchError(emitter, "Too many errors occurred");
                        break;
                    }

                    try {
                        TimeUnit.SECONDS.sleep(POLL_INTERVAL_SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

        } catch (Exception e) {
            handleBatchMonitorError(batchId, emitter, e);
        } finally {
            try {
                emitter.complete();
                scheduleBatchCleanup(batchId);
            } catch (Exception e) {
                log.error("Error completing SSE emitter for batch {}: {}", batchId, e.getMessage());
            }
        }
    }

    // Helper methods for batch monitoring
    private boolean shouldSendBatchUpdate(BatchUploadStatus last, BatchUploadStatus current) {
        if (last == null) return true;
        return hasBatchStatusChanged(last, current);
    }

    private boolean isBatchFinalStatus(BatchUploadStatus status) {
        return status != null && ("COMPLETED".equals(status.getStatus()) ||
                "FAILED".equals(status.getStatus()) ||
                "PARTIAL".equals(status.getStatus()));
    }

    private void sendBatchProgressUpdate(SseEmitter emitter, BatchUploadStatus status) throws IOException {
        // Send overall batch progress
        emitter.send(SseEmitter.event().name("batch-progress").data(status));

        // Send individual file updates that have changed
        for (Map.Entry<String, UploadStatus> entry : status.getFiles().entrySet()) {
            UploadStatus fileStatus = entry.getValue();
            if (fileStatus.getLastUpdated().isAfter(
                    status.getLastUpdated().minusSeconds(3))) { // Recently updated files

                Map<String, Object> fileUpdate = new HashMap<>();
                fileUpdate.put("uploadId", entry.getKey());
                fileUpdate.put("status", fileStatus);

                emitter.send(SseEmitter.event().name("file-progress").data(fileUpdate));
            }
        }

        log.debug("Sent batch progress update: {} - {:.1f}%",
                status.getStatus(), status.getOverallProgress());
    }

    private void sendBatchError(SseEmitter emitter, String message) throws IOException {
        Map<String, String> errorData = Map.of("error", message);
        emitter.send(SseEmitter.event().name("batch-error").data(errorData));
        log.warn("Sent batch error event: {}", message);
    }

    private void sendBatchCompletion(SseEmitter emitter, BatchUploadStatus status) throws IOException {
        emitter.send(SseEmitter.event().name("batch-complete").data(status));
        log.info("Batch upload completed: {} - {} files completed, {} failed",
                status.getBatchId(), status.getCompletedFiles(), status.getFailedFiles());
    }

    private void handleBatchMonitorError(String batchId, SseEmitter emitter, Exception e) {
        log.error("SSE monitoring error for batch {}: {}", batchId, e.getMessage());
        try {
            sendBatchError(emitter, "Internal monitoring error");
        } catch (Exception sendError) {
            log.error("Failed to send batch error event: {}", sendError.getMessage());
        }
        emitter.completeWithError(e);
    }

    private boolean hasBatchStatusChanged(BatchUploadStatus last, BatchUploadStatus current) {
        if (last == null || current == null) return true;

        return !safeEquals(last.getStatus(), current.getStatus()) ||
                Math.abs(last.getOverallProgress() - current.getOverallProgress()) > 0.1 ||
                last.getCompletedFiles() != current.getCompletedFiles() ||
                last.getFailedFiles() != current.getFailedFiles() ||
                !safeEquals(last.getMessage(), current.getMessage()) ||
                hasAnyFileStatusChanged(last.getFiles(), current.getFiles());
    }

    private boolean hasAnyFileStatusChanged(Map<String, UploadStatus> lastFiles,
                                            Map<String, UploadStatus> currentFiles) {
        if (lastFiles.size() != currentFiles.size()) return true;

        for (Map.Entry<String, UploadStatus> entry : currentFiles.entrySet()) {
            String uploadId = entry.getKey();
            UploadStatus currentFile = entry.getValue();
            UploadStatus lastFile = lastFiles.get(uploadId);

            if (lastFile == null || hasStatusChanged(lastFile, currentFile)) {
                return true;
            }
        }
        return false;
    }

    private void scheduleBatchCleanup(String batchId) {
        CompletableFuture.runAsync(() -> {
            try {
                TimeUnit.MINUTES.sleep(5); // Wait 5 minutes for batch cleanup
                fileService.cleanupBatchUploadStatus(batchId);
                log.info("Cleaned up batch status for: {}", batchId);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Batch cleanup interrupted for: {}", batchId);
            } catch (Exception e) {
                log.error("Error during batch cleanup for {}: {}", batchId, e.getMessage());
            }
        });
    }

    private void monitorUploadStatus(String uploadId, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        UploadStatus lastStatus = null;
        int consecutiveErrors = 0;

        try {
            // Send initial status if available
            UploadStatus initialStatus = fileService.getUploadStatus(uploadId);
            if (initialStatus != null) {
                sendProgressUpdate(emitter, initialStatus);
                lastStatus = initialStatus;

                // If already completed, send completion and exit
                if (isFinalStatus(initialStatus)) {
                    sendCompletion(emitter, initialStatus);
                    emitter.complete();
                    scheduleCleanup(uploadId);
                    return;
                }
            } else {
                sendError(emitter, "Upload not found");
                emitter.complete();
                return;
            }

            // Main monitoring loop with safety limits
            while (!Thread.currentThread().isInterrupted()) {
                // Check time limit
                long elapsedTime = System.currentTimeMillis() - startTime;
                if (elapsedTime > MAX_MONITORING_TIME) {
                    log.warn("SSE monitoring exceeded time limit for upload: {}", uploadId);
                    sendError(emitter, "Monitoring time limit exceeded");
                    break;
                }

                try {
                    UploadStatus currentStatus = fileService.getUploadStatus(uploadId);

                    if (currentStatus == null) {
                        consecutiveErrors++;
                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            sendError(emitter, "Upload status unavailable");
                            break;
                        }
                        log.warn("Upload status not found for: {} (attempt {})", uploadId, consecutiveErrors);
                        TimeUnit.SECONDS.sleep(POLL_INTERVAL_SECONDS);
                        continue;
                    }

                    // Reset error counter on successful fetch
                    consecutiveErrors = 0;

                    if (shouldSendUpdate(lastStatus, currentStatus)) {
                        sendProgressUpdate(emitter, currentStatus);
                        lastStatus = currentStatus;
                    }

                    if (isFinalStatus(currentStatus)) {
                        sendCompletion(emitter, currentStatus);
                        break;
                    }

                    TimeUnit.SECONDS.sleep(POLL_INTERVAL_SECONDS);

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.info("SSE monitoring interrupted for upload: {}", uploadId);
                    break;
                } catch (Exception e) {
                    consecutiveErrors++;
                    log.error("Error monitoring upload {} (attempt {}): {}", uploadId, consecutiveErrors, e.getMessage());

                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        sendError(emitter, "Too many errors occurred");
                        break;
                    }

                    try {
                        TimeUnit.SECONDS.sleep(POLL_INTERVAL_SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

        } catch (Exception e) {
            handleMonitorError(uploadId, emitter, e);
        } finally {
            try {
                emitter.complete();
                scheduleCleanup(uploadId);
            } catch (Exception e) {
                log.error("Error completing SSE emitter for upload {}: {}", uploadId, e.getMessage());
            }
        }
    }

    // Helper methods with improved null safety
    private boolean shouldSendUpdate(UploadStatus last, UploadStatus current) {
        if (last == null) return true;
        return hasStatusChanged(last, current);
    }

    private boolean isFinalStatus(UploadStatus status) {
        return status != null && (status.isCompleted() || status.isFailed());
    }

    private void sendProgressUpdate(SseEmitter emitter, UploadStatus status) throws IOException {
        emitter.send(SseEmitter.event().name("progress").data(status));
        log.debug("Sent progress update for upload: {} - {}%",
                status.getStage(), status.getProgress());
    }

    private void sendError(SseEmitter emitter, String message) throws IOException {
        Map<String, String> errorData = Map.of("error", message);
        emitter.send(SseEmitter.event().name("error").data(errorData));
        log.warn("Sent error event: {}", message);
    }

    private void sendCompletion(SseEmitter emitter, UploadStatus status) throws IOException {
        emitter.send(SseEmitter.event().name("complete").data(status));
        log.info("Upload completed: {}", status.getStage());
    }

    private void handleMonitorError(String uploadId, SseEmitter emitter, Exception e) {
        log.error("SSE monitoring error for upload {}: {}", uploadId, e.getMessage());
        try {
            sendError(emitter, "Internal monitoring error");
        } catch (Exception sendError) {
            log.error("Failed to send error event: {}", sendError.getMessage());
        }
        emitter.completeWithError(e);
    }

    // Improved null-safe status comparison
    private boolean hasStatusChanged(UploadStatus last, UploadStatus current) {
        if (last == null || current == null) return true;

        return !safeEquals(last.getStage(), current.getStage()) ||
                last.getProgress() != current.getProgress() ||
                !safeEquals(last.getMessage(), current.getMessage());
    }

    private boolean safeEquals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private void scheduleCleanup(String uploadId) {
        CompletableFuture.runAsync(() -> {
            try {
                TimeUnit.MINUTES.sleep(1); // Wait 1 minute
                fileService.cleanupUploadStatus(uploadId);
                log.info("Cleaned up upload status for: {}", uploadId);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Cleanup interrupted for upload: {}", uploadId);
            } catch (Exception e) {
                log.error("Error during cleanup for upload {}: {}", uploadId, e.getMessage());
            }
        });
    }

    private AccountEntity extractAccount(Authentication authentication) throws ItemNotFoundException {
        if (authentication != null && authentication.isAuthenticated()) {
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String userName = userDetails.getUsername();
            return accountRepo.findByUserName(userName)
                    .orElseThrow(() -> new ItemNotFoundException("User given username does not exist"));
        }
        throw new ItemNotFoundException("User is not authenticated");
    }


}