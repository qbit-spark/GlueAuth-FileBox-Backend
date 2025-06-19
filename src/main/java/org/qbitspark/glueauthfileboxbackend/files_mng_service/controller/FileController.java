package org.qbitspark.glueauthfileboxbackend.files_mng_service.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.AccountRepo;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.AccountEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.entity.FolderEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.FileData;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.FileUploadResponse;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.UploadStatus;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.repo.FolderRepository;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.service.FileService;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    public ResponseEntity<UploadStatus> getUploadStatus(@PathVariable String uploadId) {
        UploadStatus status = fileService.getUploadStatus(uploadId);

        if (status == null) {
            return ResponseEntity.notFound().build();
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