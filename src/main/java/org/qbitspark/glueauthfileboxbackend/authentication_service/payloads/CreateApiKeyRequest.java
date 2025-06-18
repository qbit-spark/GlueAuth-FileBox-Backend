package org.qbitspark.glueauthfileboxbackend.authentication_service.payloads;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.qbitspark.glueauthfileboxbackend.authentication_service.enums.ApiKeyEnvironment;
import org.qbitspark.glueauthfileboxbackend.authentication_service.enums.ApiPermissionType;


import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateApiKeyRequest {

    @NotBlank(message = "Name is required")
    @Size(min = 3, max = 100, message = "API key name must be between 3 and 100 characters")
    private String name;

    @NotEmpty(message = "At least one permission is required")
    private List<ApiPermissionType> permissions;

    @NotBlank(message = "Environment is required")
    @Pattern(regexp = "^(PRODUCTION|TESTING)$", message = "Environment must be TESTING or PRODUCTION")
    private String environment;

    private String description;
}