package org.qbitspark.glueauthfileboxbackend.files_mng_service.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.FileUploadResponse;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.qbitspark.glueauthfileboxbackend.globeresponsebody.GlobeSuccessResponseBuilder;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.service.FileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Slf4j
public class FileController {

    private final FileService fileService;

    @PostMapping("/upload")
    public ResponseEntity<GlobeSuccessResponseBuilder> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folderId", required = false) UUID folderId) throws ItemNotFoundException {

        log.info("Uploading file: {} to folder: {}", file.getOriginalFilename(), folderId);

        FileUploadResponse response = fileService.uploadFile(folderId, file);

        log.info("File uploaded successfully: {}", response.getFileId());

        return ResponseEntity.ok(
                GlobeSuccessResponseBuilder.success("File uploaded successfully", response)
        );
    }


}