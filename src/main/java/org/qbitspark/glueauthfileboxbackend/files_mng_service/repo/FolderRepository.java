package org.qbitspark.glueauthfileboxbackend.files_mng_service.repo;

import org.qbitspark.glueauthfileboxbackend.files_mng_service.entity.FolderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FolderRepository extends JpaRepository<FolderEntity, UUID> {

    // Find all root folders for a user (parentFolder is null)
    List<FolderEntity> findByUserIdAndParentFolderIsNull(UUID userId);

    // Find all subfolders of a specific folder
    List<FolderEntity> findByUserIdAndParentFolder_FolderId(UUID userId, UUID parentFolderId);

    // Find the folder by name and parent (to prevent duplicates)
    Optional<FolderEntity> findByUserIdAndFolderNameAndParentFolder_FolderId(
            UUID userId, String folderName, UUID parentFolderId);

    // Find the root folder by name
    Optional<FolderEntity> findByUserIdAndFolderNameAndParentFolderIsNull(
            UUID userId, String folderName);

    // Get all folders for a user (flat list)
    List<FolderEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    // Check if the user owns the folder
    boolean existsByFolderIdAndUserId(UUID folderId, UUID userId);


    // Delete the folder and all its contents (cascade should handle this, but explicit method)
    void deleteByFolderIdAndUserId(UUID folderId, UUID userId);

    long countByUserIdAndParentFolder_FolderIdAndIsDeletedFalse(UUID userId, UUID parentFolderId);

    long countByUserIdAndParentFolderIsNull(UUID userId);
    long countByUserIdAndParentFolder_FolderId(UUID userId, UUID parentFolderId);

    List<FolderEntity> findByUserIdAndFolderNameContainingIgnoreCase(UUID userId, String searchTerm);

    // Add to FolderRepository.java
    List<FolderEntity> findByUserIdAndParentFolderIsNullAndFolderNameContainingIgnoreCase(
            UUID userId, String searchTerm);

    List<FolderEntity> findByUserIdAndParentFolder_FolderIdAndFolderNameContainingIgnoreCase(
            UUID userId, UUID parentFolderId, String searchTerm);

}