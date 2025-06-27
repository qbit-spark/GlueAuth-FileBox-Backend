package org.qbitspark.glueauthfileboxbackend.files_mng_service.service;

import org.qbitspark.glueauthfileboxbackend.files_mng_service.payload.*;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface FolderService {
    CreateFolderResponse createFolder(CreateFolderRequest createFolderRequest) throws ItemNotFoundException;
    CreateFolderByPathResponse createFolderByPath(CreateFolderByPathRequest request) throws ItemNotFoundException;
    BatchCreateFoldersAtPathResponse batchCreateFoldersAtPath(BatchCreateFoldersAtPathRequest request) throws ItemNotFoundException;
    BatchCreateFoldersResponse createMultipleFolders(BatchCreateFoldersRequest request) throws ItemNotFoundException;
    GetFolderByPathResponse getFolderByPath(GetFolderByPathRequest request) throws ItemNotFoundException;
    List<FolderListResponse> getRootFolders() throws ItemNotFoundException;
    List<FolderListResponse> getSubFolders(UUID parentFolderId) throws ItemNotFoundException;
    FolderContentsResponse getFolderContents(UUID folderId, Pageable pageable) throws ItemNotFoundException;
}
