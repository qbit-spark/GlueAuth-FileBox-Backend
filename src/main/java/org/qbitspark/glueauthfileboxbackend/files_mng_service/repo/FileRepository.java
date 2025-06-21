package org.qbitspark.glueauthfileboxbackend.files_mng_service.repo;

import org.qbitspark.glueauthfileboxbackend.files_mng_service.entity.FileEntity;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.enums.VirusScanStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FileRepository extends JpaRepository<FileEntity, UUID> {

    // Find all files in a specific folder
    List<FileEntity> findByUserIdAndFolder_FolderId(UUID userId, UUID folderId);

    // Find all root level files (no folder)
    List<FileEntity> findByUserIdAndFolderIsNull(UUID userId);

    // Find all files for a user (across all folders)
    Page<FileEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    // Search files by name
    List<FileEntity> findByUserIdAndFileNameContainingIgnoreCase(UUID userId, String searchTerm);

    // Find files by MIME type
    List<FileEntity> findByUserIdAndMimeTypeStartingWith(UUID userId, String mimeTypePrefix);

    // Check if the user owns the file
    boolean existsByFileIdAndUserId(UUID fileId, UUID userId);

    // Find a file by name in a specific folder (to prevent duplicates)
    Optional<FileEntity> findByUserIdAndFileNameAndFolder_FolderId(
            UUID userId, String fileName, UUID folderId);

    // Find a file by name in the root folder
    Optional<FileEntity> findByUserIdAndFileNameAndFolderIsNull(
            UUID userId, String fileName);

    // Get files by scan status
    List<FileEntity> findByUserIdAndScanStatus(UUID userId, VirusScanStatus scanStatus);

    // Get files pending virus scan (for background processing)
    List<FileEntity> findByScanStatus(VirusScanStatus scanStatus);


    // Get file count by user
    long countByUserId(UUID userId);


    // Delete a file by ID and user (security check)
    void deleteByFileIdAndUserId(UUID fileId, UUID userId);

    long countByUserIdAndFolderIsNull(UUID userId);
    long countByUserIdAndFolder_FolderId(UUID userId, UUID folderId);
}