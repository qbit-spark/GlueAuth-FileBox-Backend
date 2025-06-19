package org.qbitspark.glueauthfileboxbackend.files_mng_service.service;

import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.FileData;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.FileUploadResponse;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.UploadStatus;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface FileService {
   FileUploadResponse uploadFile(UUID folderId, MultipartFile file) throws ItemNotFoundException;

   CompletableFuture<FileUploadResponse> uploadFileAsync(UUID folderId, FileData fileData, String uploadId, UUID userId, String folderPath) throws ItemNotFoundException;

    UploadStatus getUploadStatus(String uploadId);
    void cleanupUploadStatus(String uploadId);
}