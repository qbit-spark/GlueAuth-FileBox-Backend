package org.qbitspark.glueauthfileboxbackend.authentication_service.payloads;

import org.qbitspark.glueauthfileboxbackend.authentication_service.enums.VerificationChannels;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CreateAccountRequest {
    // Phone number validation for any country using E.164 format
    @NotBlank(message = "Phone number is mandatory")
    @Pattern(
            regexp = "^\\+[1-9]\\d{1,14}$",
            message = "Phone number must be in valid international format (e.g., +1234567890)"
    )

    private String phoneNumber;

    // Password validation for strong password
    @NotBlank(message = "Password is mandatory")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&#])[A-Za-z\\d@$!%*?&#]{8,}$",
            message = "Password must be at least 8 characters long, contain at least one uppercase letter, one lowercase letter, one digit, and one special character"
    )
    private String password;

    @Email(message = "Email should be valid")
    private String email;

    @NotNull(message = "Verification channel is mandatory")
    private VerificationChannels verificationChannel;

}
