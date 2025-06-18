package org.qbitspark.glueauthfileboxbackend.authentication_service.Service;

import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.ApiKeyEntity;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.CreateApiKeyRequest;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.GenerateAndSaveApiKeyResponse;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.RandomExceptions;

import java.util.List;
import java.util.UUID;

public interface ApiKeyService {

    GenerateAndSaveApiKeyResponse generateAndSaveApiKey(CreateApiKeyRequest request) throws ItemNotFoundException;

    ApiKeyEntity getApiKeyById(UUID apiKeyId) throws ItemNotFoundException;

    List<ApiKeyEntity> getApiKeysForTenant() throws ItemNotFoundException;

    boolean revokeApiKey(String apiKeyId) throws RandomExceptions, ItemNotFoundException;

}
