package org.qbitspark.glueauthfileboxbackend.authentication_service.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.qbitspark.glueauthfileboxbackend.authentication_service.enums.ApiKeyEnvironment;
import org.qbitspark.glueauthfileboxbackend.authentication_service.enums.ApiPermissionType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateAndSaveApiKeyResponse {
    private UUID id;
    private String name;
    private String apiKey;
    private String apiKeyPrefix;
    private List<ApiPermissionType> permissions;
    private ApiKeyEnvironment environment;
    private String description;
    private Boolean active;
    private LocalDateTime createdAt;
}
