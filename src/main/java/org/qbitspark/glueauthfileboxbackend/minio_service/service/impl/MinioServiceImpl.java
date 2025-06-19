package org.qbitspark.glueauthfileboxbackend.minio_service.service.impl;

import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qbitspark.glueauthfileboxbackend.minio_service.config.MinioConfig;
import org.qbitspark.glueauthfileboxbackend.minio_service.service.MinioService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class MinioServiceImpl implements MinioService {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    @Override
    public void createUserBucket(UUID userId) {
        try {
            String bucketName = getBucketName(userId);
            if (!bucketExists(userId)) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder()
                                .bucket(bucketName)
                                .build()
                );
                log.info("Created bucket: {}", bucketName);
            }
        } catch (Exception e) {
            log.error("Error creating bucket for user: {}", userId, e);
            throw new RuntimeException("Failed to create user bucket", e);
        }
    }

    @Override
    public boolean bucketExists(UUID userId) {
        try {
            String bucketName = getBucketName(userId);
            return minioClient.bucketExists(
                    BucketExistsArgs.builder()
                            .bucket(bucketName)
                            .build()
            );
        } catch (Exception e) {
            log.error("Error checking bucket existence for user: {}", userId, e);
            return false;
        }
    }

    @Override
    public void deleteUserBucket(UUID userId) {
        try {
            String bucketName = getBucketName(userId);
            // First remove all objects in bucket
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucketName)
                            .recursive(true)
                            .build()
            );

            List<DeleteObject> objectsToDelete = new ArrayList<>();
            for (Result<Item> result : results) {
                objectsToDelete.add(new DeleteObject(result.get().objectName()));
            }

            if (!objectsToDelete.isEmpty()) {
                minioClient.removeObjects(
                        RemoveObjectsArgs.builder()
                                .bucket(bucketName)
                                .objects(objectsToDelete)
                                .build()
                );
            }

            // Remove bucket
            minioClient.removeBucket(
                    RemoveBucketArgs.builder()
                            .bucket(bucketName)
                            .build()
            );

            log.info("Deleted bucket: {}", bucketName);
        } catch (Exception e) {
            log.error("Error deleting bucket for user: {}", userId, e);
            throw new RuntimeException("Failed to delete user bucket", e);
        }
    }

    @Override
    public String uploadFile(UUID userId, String folderPath, String fileName, MultipartFile file) {
        try {
            return uploadFile(userId, folderPath, fileName, file.getInputStream(),
                    file.getSize(), file.getContentType());
        } catch (IOException e) {
            log.error("Error reading multipart file", e);
            throw new RuntimeException("Failed to read uploaded file", e);
        }
    }

    @Override
    public String uploadFile(UUID userId, String folderPath, String fileName,
                             InputStream inputStream, long size, String contentType) {
        try {
            // Ensure bucket exists
            createUserBucket(userId);

            String objectKey = generateObjectKey(folderPath, fileName);

            String bucketName = getBucketName(userId);

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(inputStream, size, -1)
                            .contentType(contentType != null ? contentType : "application/octet-stream")
                            .build()
            );

            log.info("Uploaded file: {} to bucket: {}", objectKey, bucketName);
            return objectKey;

        } catch (Exception e) {
            log.error("Error uploading file for user: {}", userId, e);
            throw new RuntimeException("Failed to upload file to MinIO", e);
        }
    }

    @Override
    public InputStream downloadFile(UUID userId, String objectKey) {
        try {
            String bucketName = getBucketName(userId);
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );
        } catch (Exception e) {
            log.error("Error downloading file: {} for user: {}", objectKey, userId, e);
            throw new RuntimeException("Failed to download file from MinIO", e);
        }
    }

    @Override
    public void deleteFile(UUID userId, String objectKey) {
        try {
            String bucketName = getBucketName(userId);
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );
            log.info("Deleted file: {} from bucket: {}", objectKey, bucketName);
        } catch (Exception e) {
            log.error("Error deleting file: {} for user: {}", objectKey, userId, e);
            throw new RuntimeException("Failed to delete file from MinIO", e);
        }
    }

    @Override
    public boolean fileExists(UUID userId, String objectKey) {
        try {
            String bucketName = getBucketName(userId);
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void createFolderStructure(UUID userId, String folderPath) {
        try {
            // Create an empty object with folder path + "/" to simulate folder
            String objectKey = folderPath.endsWith("/") ? folderPath + ".keep" : folderPath + "/.keep";
            uploadFile(userId, "", objectKey, new ByteArrayInputStream(new byte[0]), 0, "text/plain");
        } catch (Exception e) {
            log.error("Error creating folder structure: {} for user: {}", folderPath, userId, e);
            throw new RuntimeException("Failed to create folder structure", e);
        }
    }

    @Override
    public void deleteFolderStructure(UUID userId, String folderPath) {
        try {
            String bucketName = getBucketName(userId);
            String prefix = folderPath.endsWith("/") ? folderPath : folderPath + "/";

            // List all objects with this prefix
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucketName)
                            .prefix(prefix)
                            .recursive(true)
                            .build()
            );

            List<DeleteObject> objectsToDelete = new ArrayList<>();
            for (Result<Item> result : results) {
                objectsToDelete.add(new DeleteObject(result.get().objectName()));
            }

            if (!objectsToDelete.isEmpty()) {
                minioClient.removeObjects(
                        RemoveObjectsArgs.builder()
                                .bucket(bucketName)
                                .objects(objectsToDelete)
                                .build()
                );
            }

            log.info("Deleted folder structure: {} from bucket: {}", folderPath, bucketName);
        } catch (Exception e) {
            log.error("Error deleting folder structure: {} for user: {}", folderPath, userId, e);
            throw new RuntimeException("Failed to delete folder structure", e);
        }
    }

    @Override
    public List<String> listFolderContents(UUID userId, String folderPath) {
        try {
            String bucketName = getBucketName(userId);
            String prefix = folderPath.isEmpty() ? "" : (folderPath.endsWith("/") ? folderPath : folderPath + "/");

            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucketName)
                            .prefix(prefix)
                            .build()
            );

            List<String> contents = new ArrayList<>();
            for (Result<Item> result : results) {
                contents.add(result.get().objectName());
            }

            return contents;
        } catch (Exception e) {
            log.error("Error listing folder contents: {} for user: {}", folderPath, userId, e);
            throw new RuntimeException("Failed to list folder contents", e);
        }
    }

    @Override
    public String generateObjectKey(String folderPath, String fileName) {
        if (folderPath == null || folderPath.trim().isEmpty()) {
            return fileName;
        }

        String cleanFolderPath = folderPath.trim();
        if (cleanFolderPath.endsWith("/")) {
            return cleanFolderPath + fileName;
        } else {
            return cleanFolderPath + "/" + fileName;
        }
    }

    @Override
    public String getBucketName(UUID userId) {
        return minioConfig.getBucketPrefix() + userId.toString();
    }

    @Override
    public long getFileSize(UUID userId, String objectKey) {
        try {
            String bucketName = getBucketName(userId);
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );
            return stat.size();
        } catch (Exception e) {
            log.error("Error getting file size: {} for user: {}", objectKey, userId, e);
            throw new RuntimeException("Failed to get file size", e);
        }
    }

    @Override
    public String getFileContentType(UUID userId, String objectKey) {
        try {
            String bucketName = getBucketName(userId);
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );
            return stat.contentType();
        } catch (Exception e) {
            log.error("Error getting file content type: {} for user: {}", objectKey, userId, e);
            return "application/octet-stream";
        }
    }

    @Override
    public String generatePresignedDownloadUrl(UUID userId, String objectKey, int expirationInMinutes) {
        try {
            String bucketName = getBucketName(userId);
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(objectKey)
                            .expiry(expirationInMinutes, TimeUnit.MINUTES)
                            .build()
            );
        } catch (Exception e) {
            log.error("Error generating presigned download URL: {} for user: {}", objectKey, userId, e);
            throw new RuntimeException("Failed to generate presigned URL", e);
        }
    }

    @Override
    public String generatePresignedUploadUrl(UUID userId, String objectKey, int expirationInMinutes) {
        try {
            String bucketName = getBucketName(userId);
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(bucketName)
                            .object(objectKey)
                            .expiry(expirationInMinutes, TimeUnit.MINUTES)
                            .build()
            );
        } catch (Exception e) {
            log.error("Error generating presigned upload URL: {} for user: {}", objectKey, userId, e);
            throw new RuntimeException("Failed to generate presigned URL", e);
        }
    }
}