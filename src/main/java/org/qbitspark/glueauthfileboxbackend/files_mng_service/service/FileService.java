package org.qbitspark.glueauthfileboxbackend.files_mng_service.service;

import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.FileUploadResponse;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface FileService {
    FileUploadResponse uploadFile(UUID folderId, MultipartFile file) throws ItemNotFoundException;
}