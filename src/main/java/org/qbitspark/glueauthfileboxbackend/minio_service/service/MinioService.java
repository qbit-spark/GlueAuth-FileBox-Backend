package org.qbitspark.glueauthfileboxbackend.minio_service.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

public interface MinioService {

    // Bucket operations
    void createUserBucket(UUID userId);
    void deleteUserBucket(UUID userId);
    boolean bucketExists(UUID userId);

    // Object operations
    String uploadFile(UUID userId, String folderPath, String fileName, MultipartFile file);
    String uploadFile(UUID userId, String folderPath, String fileName, InputStream inputStream, long size, String contentType);

    InputStream downloadFile(UUID userId, String objectKey);
    void deleteFile(UUID userId, String objectKey);
    boolean fileExists(UUID userId, String objectKey);

    // Folder operations (for MinIO structure)
    void createFolderStructure(UUID userId, String folderPath);
    void deleteFolderStructure(UUID userId, String folderPath);
    List<String> listFolderContents(UUID userId, String folderPath);

    // Utility methods
    String generateObjectKey(String folderPath, String fileName);
    String getBucketName(UUID userId);
    long getFileSize(UUID userId, String objectKey);
    String getFileContentType(UUID userId, String objectKey);

    // Presigned URLs (for future direct access)
    String generatePresignedDownloadUrl(UUID userId, String objectKey, int expirationInMinutes);
    String generatePresignedUploadUrl(UUID userId, String objectKey, int expirationInMinutes);

    void renameFile(UUID userId, String oldKey, String newKey);
}