package org.qbitspark.glueauthfileboxbackend.files_mng_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.*;
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

    @PostMapping("/create-by-path")
    public ResponseEntity<GlobeSuccessResponseBuilder> createFolderByPath(
            @Valid @RequestBody CreateFolderByPathRequest request) throws ItemNotFoundException {

        log.info("Creating folder by path: '{}' with parent: {}",
                request.getPath(), request.getParentFolderId());

            CreateFolderByPathResponse response = folderService.createFolderByPath(request);

            log.info("Folder path created successfully: {} - Final folder ID: {}",
                    response.getFullPath(), response.getFinalFolderId());

            return ResponseEntity.ok(
                    GlobeSuccessResponseBuilder.success("Folder path created successfully", response)
            );

    }

    @PostMapping("/create-batch")
    public ResponseEntity<GlobeSuccessResponseBuilder> createMultipleFolders(
            @Valid @RequestBody BatchCreateFoldersRequest request) throws ItemNotFoundException {

        log.info("Creating {} folders with parent: {}",
                request.getDistinctFolderNames().size(), request.getParentFolderId());

            BatchCreateFoldersResponse response = folderService.createMultipleFolders(request);

            log.info("Batch folder creation completed - Created: {}, Existing: {}, Failed: {}",
                    response.getSuccessfullyCreated(), response.getAlreadyExisted(), response.getFailed());

            return ResponseEntity.ok(
                    GlobeSuccessResponseBuilder.success("Batch folder creation completed", response)
            );


    }

    /***
     * *
     * * Absolute path from root
     * GET /api/v1/folders/by-path?path=Documents/Projects/Backend
     * # Result: Root → Documents → Projects → Backend
     *
     * # Relative path from specific parent
     * GET /api/v1/folders/by-path?path=Projects/Backend&parentId=f5594cbc-76b2-4f10-bfc1-a3f3159496cb
     * # Result: Given Folder → Projects → Backend
     *
     * # Just folder info without contents
     * GET /api/v1/folders/by-path?path=Documents/Projects&includeContents=false
     *
     * # With contents included (default)
     * GET /api/v1/folders/by-path?path=Documents/Projects&includeContents=true
     *
     */
    @GetMapping("/by-path")
    public ResponseEntity<GlobeSuccessResponseBuilder> getFolderByPath(
            @Valid @ModelAttribute GetFolderByPathRequest request) throws ItemNotFoundException {

        log.info("Getting folder by path: '{}' with parent: {}, includeContents: {}",
                request.getPath(), request.getParentId(), request.isIncludeContents());

            GetFolderByPathResponse response = folderService.getFolderByPath(request);

            log.info("Folder found by path: {} - Target folder: {}",
                    request.getPath(), response.getTargetFolder().getName());

            return ResponseEntity.ok(
                    GlobeSuccessResponseBuilder.success("Folder retrieved successfully", response)
            );

    }

    /***
     * *
     # Create project structure in specific location
     POST /api/v1/folders/create-batch-at-path
     {
     "targetPath": "Projects/WebApp/src",
     "folderNames": ["components", "pages", "utils", "assets"],
     "parentId": null
     }

     # Create monthly folders in specific year
     POST /api/v1/folders/create-batch-at-path
     {
     "targetPath": "Documents/Reports/2024",
     "folderNames": ["January", "February", "March", "April"],
     "parentId": "work-uuid"
     }

     # Organize photos by event
     POST /api/v1/folders/create-batch-at-path
     {
     "targetPath": "Photos/2024/Vacation",
     "folderNames": ["Beach", "Mountains", "City"],
     "parentId": null
     }


     */

    @PostMapping("/create-batch-at-path")
    public ResponseEntity<GlobeSuccessResponseBuilder> batchCreateFoldersAtPath(
            @Valid @RequestBody BatchCreateFoldersAtPathRequest request) throws ItemNotFoundException {

        log.info("Creating {} folders at path: '{}' with parent: {}",
                request.getFolderNames().size(), request.getTargetPath(), request.getParentId());

        try {
            BatchCreateFoldersAtPathResponse response = folderService.batchCreateFoldersAtPath(request);

            log.info("Batch folder creation at path completed - Created: {}, Existing: {}, Failed: {} at location: {}",
                    response.getBatchResults().getSuccessfullyCreated(),
                    response.getBatchResults().getAlreadyExisted(),
                    response.getBatchResults().getFailed(),
                    response.getTargetLocation().getFullPath());

            return ResponseEntity.ok(
                    GlobeSuccessResponseBuilder.success("Batch folder creation at path completed", response)
            );

        } catch (Exception e) {
            log.error("Failed to create batch folders at path '{}': {}", request.getTargetPath(), e.getMessage());
            throw e; // Let global exception handler deal with it
        }
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