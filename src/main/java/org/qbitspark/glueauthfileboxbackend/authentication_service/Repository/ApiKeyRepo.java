package org.qbitspark.glueauthfileboxbackend.authentication_service.Repository;

import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.ApiKeyEntity;
import org.qbitspark.glueauthfileboxbackend.authentication_service.enums.TokenValidity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepo extends JpaRepository<ApiKeyEntity, UUID> {
    ApiKeyEntity findByApiKeyHash(String apiKeyHash);
    Optional<ApiKeyEntity> findApiKeyEntitiesByUserIdAndId(UUID userId, UUID id);
    List<ApiKeyEntity> findApiKeyEntitiesByTenantIdAndUserIdAndActiveAndTokenValidityNot(
            UUID tenantId,
            UUID userId,
            Boolean active,
            TokenValidity tokenValidity
    );
    boolean existsApiKeyEntityByUserIdAndId(UUID userId, UUID id);

    Optional<ApiKeyEntity> findByApiKeyHashAndActiveAndTokenValidity( String apiKeyHash, Boolean active, TokenValidity tokenValidity);
}
