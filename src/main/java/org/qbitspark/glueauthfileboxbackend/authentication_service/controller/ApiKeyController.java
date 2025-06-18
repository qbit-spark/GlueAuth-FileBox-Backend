package org.qbitspark.glueauthfileboxbackend.authentication_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Service.ApiKeyService;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.ApiKeyEntity;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.ApiKeyResponse;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.CreateApiKeyRequest;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.GenerateAndSaveApiKeyResponse;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.qbitspark.glueauthfileboxbackend.globeresponsebody.GlobeSuccessResponseBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("api/v1/api-key")
public class ApiKeyController {
    private final ApiKeyService apiKeyService;

    @PostMapping("/generate")
    public ResponseEntity<GlobeSuccessResponseBuilder> generateApiKey(@Valid @RequestBody CreateApiKeyRequest request) throws ItemNotFoundException {

        GenerateAndSaveApiKeyResponse response = apiKeyService.generateAndSaveApiKey(request);

        return ResponseEntity.ok(GlobeSuccessResponseBuilder.success("Api key generated successfully", response));
    }

    @GetMapping("/{apiKeyId}")
    public ResponseEntity<GlobeSuccessResponseBuilder> getApiKeyById(@PathVariable UUID apiKeyId) throws ItemNotFoundException {
        
        ApiKeyEntity apiKey = apiKeyService.getApiKeyById(apiKeyId);

        ApiKeyResponse apiKeyResponse = new ApiKeyResponse();
        apiKeyResponse.setApiKeyId(apiKey.getId());
        apiKeyResponse.setApiKeyPrefix(apiKey.getApiKeyPrefix());
        apiKeyResponse.setActive(apiKey.getActive());
        apiKeyResponse.setName(apiKey.getName());
        apiKeyResponse.setDescription(apiKey.getDescription());
        apiKeyResponse.setUsageCount(apiKey.getUsageCount());
        apiKeyResponse.setLastUsed(apiKey.getLastUsedAt());
        apiKeyResponse.setEnvironment(apiKey.getEnvironment());
        
        return ResponseEntity.ok(GlobeSuccessResponseBuilder.success("Api key retrieved successfully", apiKeyResponse));
    }
    
    @GetMapping("/my-keys")
    public ResponseEntity<GlobeSuccessResponseBuilder> getApiKeysForTenant() throws ItemNotFoundException {

        List<ApiKeyEntity> apiKeyEntities = apiKeyService.getApiKeysForTenant();

        List<ApiKeyResponse> apiKeyResponses = apiKeyEntities.stream()
                .map(apiKey -> {
                    ApiKeyResponse response = new ApiKeyResponse();
                    response.setApiKeyId(apiKey.getId());
                    response.setApiKeyPrefix(apiKey.getApiKeyPrefix());
                    response.setActive(apiKey.getActive());
                    response.setName(apiKey.getName());
                    response.setDescription(apiKey.getDescription());
                    response.setUsageCount(apiKey.getUsageCount());
                    response.setLastUsed(apiKey.getLastUsedAt());
                    response.setEnvironment(apiKey.getEnvironment());
                    return response;
                })
                .toList();

        return ResponseEntity.ok(GlobeSuccessResponseBuilder.success("My Api keys retrieved successfully", apiKeyResponses));
    }
}
