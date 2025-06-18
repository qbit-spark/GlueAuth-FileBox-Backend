package org.qbitspark.glueauthfileboxbackend.authentication_service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.qbitspark.glueauthfileboxbackend.authentication_service.enums.ApiKeyEnvironment;
import org.qbitspark.glueauthfileboxbackend.authentication_service.enums.ApiPermissionType;
import org.qbitspark.glueauthfileboxbackend.authentication_service.enums.TokenValidity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "api_keys_tb", indexes = {
        @Index(name = "idx_api_key_hash", columnList = "apiKeyHash"),
        @Index(name = "idx_tenant_id", columnList = "tenantId"),
        @Index(name = "idx_tenant_environment", columnList = "tenantId, environment"),
        @Index(name = "idx_active", columnList = "active"),
        @Index(name = "idx_created_at", columnList = "createdAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String apiKeyHash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String apiKeyPrefix;

    @ElementCollection(targetClass = ApiPermissionType.class)
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "api_key_permissions", joinColumns = @JoinColumn(name = "api_key_id"))
    @Column(name = "permission")
    private List<ApiPermissionType> permissions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApiKeyEnvironment environment;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID userId;

    @Column(length = 500)
    private String description;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime lastUsedAt;

    private TokenValidity tokenValidity;

    // Sliding Window Fields
    @Column(name = "effective_expires_at")
    private LocalDateTime effectiveExpiresAt;

    @Column(name = "last_extended_at")
    private LocalDateTime lastExtendedAt;

    @Builder.Default
    @Column(name = "extension_count")
    private Integer extensionCount = 0;

    @Builder.Default
    @Column(name = "usage_count")
    private Long usageCount = 0L;

    @Column(name = "max_allowed_expiration")
    private LocalDateTime maxAllowedExpiration;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (effectiveExpiresAt == null) {
            effectiveExpiresAt = now.plusDays(15); // Default 15 days
        }
        if (maxAllowedExpiration == null) {
            maxAllowedExpiration = now.plusDays(90); // Max 90 days
        }
    }

    // Simple helper methods
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(effectiveExpiresAt);
    }

    public boolean canExtend() {
        return LocalDateTime.now().isBefore(maxAllowedExpiration);
    }

    public void extend(int days) {
        if (canExtend()) {
            LocalDateTime newExpiration = LocalDateTime.now().plusDays(days);
            if (newExpiration.isAfter(maxAllowedExpiration)) {
                newExpiration = maxAllowedExpiration;
            }
            this.effectiveExpiresAt = newExpiration;
            this.lastExtendedAt = LocalDateTime.now();
            this.extensionCount++;
        }
    }

    public void recordUsage() {
        this.lastUsedAt = LocalDateTime.now();
        this.usageCount++;
    }
}