package org.qbitspark.glueauthfileboxbackend.files_mng_service.service;

import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.*;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface FileService {
   FileUploadResponse uploadFile(UUID folderId, MultipartFile file) throws ItemNotFoundException;

   CompletableFuture<FileUploadResponse> uploadFileAsync(UUID folderId, FileData fileData, String uploadId, UUID userId, String folderPath) throws ItemNotFoundException;

    UploadStatus getUploadStatus(String uploadId);
    void cleanupUploadStatus(String uploadId);

    CompletableFuture<String> uploadFilesBatch(UUID folderId, List<MultipartFile> files, BatchUploadOptions options, UUID userId, String folderPath);
    BatchUploadStatus getBatchUploadStatus(String batchId);
    void cleanupBatchUploadStatus(String batchId);

    FileInfoResponse getFileInfo(UUID fileId) throws ItemNotFoundException;

    ResponseEntity<InputStreamResource> downloadFile(UUID fileId) throws ItemNotFoundException;

    ResponseEntity<InputStreamResource> previewFile(UUID fileId) throws ItemNotFoundException;
}