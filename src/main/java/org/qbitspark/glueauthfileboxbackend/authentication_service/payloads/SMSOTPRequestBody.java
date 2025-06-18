package org.qbitspark.glueauthfileboxbackend.authentication_service.payloads;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class SMSOTPRequestBody {
    @NotBlank(message = "Phone number is mandatory")
    @Pattern(
            regexp = "^\\+[1-9]\\d{1,14}$",
            message = "Phone number must be in valid international format (e.g., +1234567890)"
    )
    private String phoneNumber;
    @NotBlank(message = "OTP code is mandatory")
    private String code;
}
