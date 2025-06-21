package org.qbitspark.glueauthfileboxbackend.files_mng_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.CreateFolderRequest;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.CreateFolderResponse;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.FolderContentsResponse;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.FolderListResponse;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.service.FolderService;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.qbitspark.glueauthfileboxbackend.globeresponsebody.GlobeSuccessResponseBuilder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/folders")
@RequiredArgsConstructor
@Slf4j
public class FolderController {

    private final FolderService folderService;

    @PostMapping
    public ResponseEntity<GlobeSuccessResponseBuilder> createFolder(
            @Valid @RequestBody CreateFolderRequest request) throws ItemNotFoundException {

        log.info("Creating folder: {} with parent: {}",
                request.getFolderName(), request.getParentFolderId());


            CreateFolderResponse response = folderService.createFolder(request);

            log.info("Folder created successfully: {}", response.getFolderId());

            return ResponseEntity.ok(
                    GlobeSuccessResponseBuilder.success("Folder created successfully", response)
            );
    }
    @GetMapping
    public ResponseEntity<GlobeSuccessResponseBuilder> getRootFolders() throws ItemNotFoundException {

        log.info("Getting root folders for authenticated user");

        List<FolderListResponse> folders = folderService.getRootFolders();

        log.info("Found {} root folders", folders.size());

        return ResponseEntity.ok(
                GlobeSuccessResponseBuilder.success("Root folders retrieved successfully", folders)
        );
    }

    @GetMapping("/{parentFolderId}/children")
    public ResponseEntity<GlobeSuccessResponseBuilder> getSubFolders(
            @PathVariable UUID parentFolderId) throws ItemNotFoundException {

        log.info("Getting subfolders for parent folder: {}", parentFolderId);

        List<FolderListResponse> subFolders = folderService.getSubFolders(parentFolderId);

        log.info("Found {} subfolders", subFolders.size());

        return ResponseEntity.ok(
                GlobeSuccessResponseBuilder.success("Subfolders retrieved successfully", subFolders)
        );
    }

    @GetMapping("/{folderId}/contents")
    public ResponseEntity<GlobeSuccessResponseBuilder> getFolderContents(
            @PathVariable UUID folderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "100") int maxSize) throws ItemNotFoundException {

        log.info("Getting contents for folder: {} (page: {}, size: {})", folderId, page, size);

        // Limit page size
        int limitedSize = Math.min(size, maxSize);
        Pageable pageable = PageRequest.of(page, limitedSize);

        FolderContentsResponse response = folderService.getFolderContents(folderId, pageable);

        log.info("Retrieved {} items for folder: {}",
                response.getStatistics().getCurrentPage().getItems(), folderId);

        return ResponseEntity.ok(
                GlobeSuccessResponseBuilder.success("Folder contents retrieved successfully", response)
        );
    }

    @GetMapping("/root/contents")
    public ResponseEntity<GlobeSuccessResponseBuilder> getRootFolderContents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) throws ItemNotFoundException {

        return getFolderContents(null, page, size, 100);
    }
}