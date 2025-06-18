package org.qbitspark.glueauthfileboxbackend.authentication_service.payloads;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class AccountLoginRequest {
    @NotBlank(message = "phoneEmailOrUserName should not be empty")
    @NotEmpty(message = "phoneEmailOrUserName should not be empty")
    private String phoneEmailOrUserName;
    @NotBlank(message = "Password should not be empty")
    @NotEmpty(message = "Password should not be empty")
    private String password;

}
