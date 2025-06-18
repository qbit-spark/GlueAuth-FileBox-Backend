package org.qbitspark.glueauthfileboxbackend.authentication_service.Service.IMPL;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.AccountRepo;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.PasswordResetOTPRepo;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.UserOTPRepository;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Service.EmailOTPService;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Service.PasswordResetOTPService;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.AccountEntity;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.PasswordResetOTPEntity;
import org.qbitspark.glueauthfileboxbackend.authentication_service.globevalidationutils.CustomValidationUtils;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.RandomExceptions;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.VerificationException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@RequiredArgsConstructor
@Service
public class PasswordResetOTPServiceIMPL implements PasswordResetOTPService {

    @Value("${otp.expire_time.minutes}")
    private String EXPIRE_TIME;

   private final AccountRepo accountRepo;
    private final CustomValidationUtils validationUtils;
    private final PasswordEncoder passwordEncoder;
    private final EmailOTPService emailOTPService;
    private final UserOTPRepository userOTPRepository;
    private final PasswordResetOTPRepo passwordResetOTPRepo;

    @Override
    public String generateAndSendPSWDResetOTP(String email) throws RandomExceptions, JsonProcessingException, ItemNotFoundException {

            AccountEntity accountEntity = accountRepo.findByEmail(email)
                .orElseThrow(()-> new ItemNotFoundException("No such user with given email"));

            if (accountEntity.getIsVerified().equals(false)){
                throw new RandomExceptions("You need to verify your account first before reset password");
            }


        //Todo: Send the OTP via SMS
        //sendBulkSMS(email, newOtpCode, USERNAME, PASSWORD);

        //Todo: Send the OTP via Email
        String emailHeader = "Password Reset Request";
        String instructionText = "Please use the following OTP to reset your password:";
        emailOTPService.generateAndSendPasswordResetEmail(accountEntity, emailHeader, instructionText);


        return email;
    }

    @Override
    public boolean verifyOTPAndResetPassword(String email, String otpCode, String newPassword) throws RandomExceptions, ItemNotFoundException, VerificationException {

        // Fetch the user by phone number
        AccountEntity account = accountRepo.findByEmail(email)
                .orElseThrow(() -> new ItemNotFoundException("No such user with given phone number"));

        PasswordResetOTPEntity existingOTP = passwordResetOTPRepo.findPasswordResetOTPEntitiesByAccount(account);
                if (existingOTP == null) {
            throw new VerificationException("OTP is invalid");
        }


        // Check if OTP is expired
        LocalDateTime createdTime = existingOTP.getSentTime();
        if (validationUtils.isOTPExpired(createdTime)) {
            throw new RandomExceptions("OTP expired");
        }

        // Verify the OTP code
        if (existingOTP.getOtpCode().equals(otpCode)) {
            LocalDateTime currentTime = LocalDateTime.now();
            LocalDateTime expirationTime = existingOTP.getSentTime().plusMinutes(Long.parseLong(EXPIRE_TIME));

            // Check if OTP is not expired
            if (currentTime.isBefore(expirationTime)) {

                // Reset the password
                account.setPassword(passwordEncoder.encode(newPassword));
                accountRepo.save(account);

                // Make the OTP expire after a successful password reset
                LocalDateTime expiration = existingOTP.getSentTime().minusHours(20);
                existingOTP.setSentTime(expiration);

                passwordResetOTPRepo.save(existingOTP);


                return true;
            }else {
                throw new VerificationException("OTP expired");
            }
        }

        throw new VerificationException("OTP or phone number provided is incorrect");
    }
}
