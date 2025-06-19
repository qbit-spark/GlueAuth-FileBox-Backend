package org.qbitspark.glueauthfileboxbackend.authentication_service.Service.IMPL;

import lombok.RequiredArgsConstructor;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.AccountRepo;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.ApiKeyRepo;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Service.ApiKeyService;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.AccountEntity;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.ApiKeyEntity;
import org.qbitspark.glueauthfileboxbackend.authentication_service.enums.ApiKeyEnvironment;
import org.qbitspark.glueauthfileboxbackend.authentication_service.enums.ApiPermissionType;
import org.qbitspark.glueauthfileboxbackend.authentication_service.enums.TokenValidity;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.CreateApiKeyRequest;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.GenerateAndSaveApiKeyResponse;
import org.qbitspark.glueauthfileboxbackend.globe_utils.ApiKeyHashingService;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.RandomExceptions;
import org.qbitspark.glueauthfileboxbackend.globesecurity.JWTProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApiKeyServiceIMPL implements ApiKeyService {

    private final AccountRepo accountRepo;
    private final JWTProvider jwtProvider;
    private final ApiKeyHashingService hashingService;
    private final ApiKeyRepo apiKeyRepo;

    @Transactional
    @Override
    public GenerateAndSaveApiKeyResponse generateAndSaveApiKey(CreateApiKeyRequest request) throws ItemNotFoundException {

        AccountEntity account = getAuthenticatedAccount();

        List<String> permissionStrings = request.getPermissions().stream()
                .map(ApiPermissionType::getPermission)
                .toList();

        String apiKey = jwtProvider.generateFileBoxApiToken(
                account.getId(),
                account.getId(),
                permissionStrings,
                request.getName(),
                request.getEnvironment()
        );

        String prefix = createApiKeyPrefix(apiKey);
        String hashedApiKey = hashApiKey(apiKey);

        ApiKeyEntity apiKeyEntity = ApiKeyEntity.builder()
                .name(request.getName())
                .apiKeyHash(hashedApiKey)
                .apiKeyPrefix(prefix)
                .permissions(request.getPermissions())
                .environment(ApiKeyEnvironment.valueOf(request.getEnvironment()))
                .tenantId(account.getId())
                .userId(account.getId())
                .tokenValidity(TokenValidity.VALID)
                .description(request.getDescription())
                .active(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .usageCount(0L)
                .build();

        ApiKeyEntity savedEntity = apiKeyRepo.save(apiKeyEntity);

        return GenerateAndSaveApiKeyResponse.builder()
                .id(savedEntity.getId())
                .name(savedEntity.getName())
                .apiKey(apiKey)
                .apiKeyPrefix(savedEntity.getApiKeyPrefix())
                .permissions(savedEntity.getPermissions())
                .environment(savedEntity.getEnvironment())
                .description(savedEntity.getDescription())
                .active(savedEntity.getActive())
                .createdAt(savedEntity.getCreatedAt())
                .build();
    }

    @Override
    public ApiKeyEntity getApiKeyById(UUID apiKeyId) throws ItemNotFoundException {

        // Get an authenticated user
        AccountEntity account = getAuthenticatedAccount();

        // Get by user I'd;
        return apiKeyRepo.findApiKeyEntitiesByUserIdAndId(account.getId(), apiKeyId).orElseThrow(()-> new ItemNotFoundException("No such API key associated with authenticated user"));
    }

    @Override
    public List<ApiKeyEntity> getApiKeysForTenant() throws ItemNotFoundException {

        // Get an authenticated user
        AccountEntity account = getAuthenticatedAccount();

        //This is done intentionally
        return apiKeyRepo.findApiKeyEntitiesByTenantIdAndUserIdAndActiveAndTokenValidityNot(account.getId(), account.getId(),true, TokenValidity.REVOKED);
    }

    @Transactional
    @Override
    public boolean revokeApiKey(String apiKeyId) throws RandomExceptions, ItemNotFoundException {

        // Get an authenticated user
        AccountEntity account = getAuthenticatedAccount();

        ApiKeyEntity apiKeyEntity = apiKeyRepo.findApiKeyEntitiesByUserIdAndId(account.getId(), account.getId()).orElseThrow(()-> new RandomExceptions("APi key does not belongs to this authenticated user"));

        apiKeyEntity.setActive(false);
        apiKeyEntity.setTokenValidity(TokenValidity.REVOKED);
        apiKeyEntity.setUsageCount(apiKeyEntity.getUsageCount() + 1);
        apiKeyRepo.save(apiKeyEntity);

        return true;
    }


    private AccountEntity getAuthenticatedAccount() throws ItemNotFoundException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return extractAccount(authentication);
    }

    private AccountEntity extractAccount(Authentication authentication) throws ItemNotFoundException {
        if (authentication != null && authentication.isAuthenticated()) {
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String userName = userDetails.getUsername();

            Optional<AccountEntity> userOptional = accountRepo.findByUserName(userName);
            if (userOptional.isPresent()) {
                return userOptional.get();
            } else {
                throw new ItemNotFoundException("User with given userName does not exist");
            }
        } else {
            throw new ItemNotFoundException("User is not authenticated");
        }
    }


    private String createApiKeyPrefix(String apiKey) {
        if (apiKey.length() < 8) {
            return apiKey;
        }
        String start = apiKey.substring(0, 4);
        String end = apiKey.substring(apiKey.length() - 4);
        return start + "***************************" + end;
    }

    private String hashApiKey(String apiKey) {
        return hashingService.hashApiKey(apiKey);
    }
}
