package org.qbitspark.glueauthfileboxbackend.authentication_service.Service.IMPL;

import lombok.RequiredArgsConstructor;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.ApiKeyRepo;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.ApiKeyEntity;
import org.qbitspark.glueauthfileboxbackend.authentication_service.enums.TokenValidity;
import org.qbitspark.glueauthfileboxbackend.globe_utils.ApiKeyHashingService;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.TokenExpiredException;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.TokenInvalidException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ApiKeyUsageService {

    private final ApiKeyRepo apiKeyRepo;
    private final ApiKeyHashingService hashingService;

    @Value("${app.filebox-api.sliding-window-days:7}")
    private Integer slidingWindowDays;

    @Value("${app.filebox-api.renewal-threshold-days:3}")
    private Integer renewalThresholdDays;

    @Transactional
    public boolean validateAndUpdateToken(String token) throws Exception {
        String hashedToken = hashingService.hashApiKey(token);

        Optional<ApiKeyEntity> apiKeyOpt = apiKeyRepo.findByApiKeyHashAndActiveAndTokenValidity(
                hashedToken, true, TokenValidity.VALID);

        if (apiKeyOpt.isEmpty()) {
            throw new TokenInvalidException("API key not found or inactive");
        }

        ApiKeyEntity apiKey = apiKeyOpt.get();

        // Check if the token is expired
        if (apiKey.isExpired()) {
            throw new TokenExpiredException("API key has expired");
        }

        apiKey.recordUsage();

        // Check if the token needs extension (within renewal threshold)
        if (shouldExtendToken(apiKey)) {
            apiKey.extend(slidingWindowDays);
        }

        apiKeyRepo.save(apiKey);

        return true;
    }


    private boolean shouldExtendToken(ApiKeyEntity apiKey) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime renewalThreshold = apiKey.getEffectiveExpiresAt().minusDays(renewalThresholdDays);

        // Extension criteria:
        // 1. Current time is past renewal threshold
        // 2. Token can still be extended (within max lifetime)
        // 3. Token is being actively used
        return now.isAfter(renewalThreshold) &&
                apiKey.canExtend() &&
                isActivelyUsed(apiKey);
    }


    //Check if a token is actively used (simple heuristic)
    private boolean isActivelyUsed(ApiKeyEntity apiKey) {
        if (apiKey.getLastUsedAt() == null) {
            return false;
        }

        // Consider active if used within the last 5 days
        LocalDateTime fiveDaysAgo = LocalDateTime.now().minusDays(5);
        return apiKey.getLastUsedAt().isAfter(fiveDaysAgo);
    }


    //Check if an API key exists and is active (for basic validation)
    public boolean isApiKeyActive(String token) {
        try {
            String hashedToken = hashingService.hashApiKey(token);
            Optional<ApiKeyEntity> apiKeyOpt = apiKeyRepo.findByApiKeyHashAndActiveAndTokenValidity(
                    hashedToken, true, TokenValidity.VALID);

            if (apiKeyOpt.isEmpty()) {
                return false;
            }

            // Check if effectively expired
            return !apiKeyOpt.get().isExpired();
        } catch (Exception e) {
            return false;
        }
    }


}