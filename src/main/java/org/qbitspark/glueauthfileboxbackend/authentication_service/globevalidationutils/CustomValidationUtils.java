package org.qbitspark.glueauthfileboxbackend.authentication_service.globevalidationutils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class CustomValidationUtils {
    @Value("${otp.expire_time.minutes}")
    private Long OTP_EXPIRE_TIME;

    public boolean isOTPExpired(LocalDateTime createdTime) {
        LocalDateTime currentTime = LocalDateTime.now();
        LocalDateTime expirationTime = createdTime.plusMinutes(OTP_EXPIRE_TIME);

        return currentTime.isAfter(expirationTime);
    }
}
