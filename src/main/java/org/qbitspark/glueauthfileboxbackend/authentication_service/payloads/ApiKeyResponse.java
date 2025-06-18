package org.qbitspark.glueauthfileboxbackend.authentication_service.payloads;

import lombok.Data;
import org.qbitspark.glueauthfileboxbackend.authentication_service.enums.ApiKeyEnvironment;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class ApiKeyResponse {
    private UUID apiKeyId;
    private String apiKeyPrefix;
    private String description;
    private Boolean active;
    private String name;
    private ApiKeyEnvironment environment;
    private LocalDateTime lastUsed;
    private Long usageCount;
}
