package org.qbitspark.glueauthfileboxbackend.authentication_service.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.AccountRepo;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Service.EmailOTPService;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.AccountEntity;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.EmailOTPRequestBody;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.RequestEmailOTPBody;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.RandomExceptions;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.VerificationException;
import org.qbitspark.glueauthfileboxbackend.globeresponsebody.GlobeSuccessResponseBuilder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("api/v1/auth")
public class OTPController {

    private final AccountRepo accountRepo;
    private final EmailOTPService emailOTPService;

    @PostMapping("/request-otp")
    public ResponseEntity<GlobeSuccessResponseBuilder> requestEmailOTP(@Valid @RequestBody RequestEmailOTPBody requestEmailOTPBody) throws RandomExceptions, JsonProcessingException, ItemNotFoundException {

        AccountEntity userAuthEntity = accountRepo.findByEmail(requestEmailOTPBody.getEmail())
                .orElseThrow(() -> new ItemNotFoundException("User with provided email does not exist"));

        // Send the OTP via Email for password reset
        String emailHeader = "Welcome to Kitchen Support!";
        String instructionText = "Please use the following OTP to complete your registration:";
        emailOTPService.generateAndSendEmailOTP(userAuthEntity, emailHeader, instructionText);

        GlobeSuccessResponseBuilder response = GlobeSuccessResponseBuilder.success(
                "New OTP code sent successful",
                "New OTP code set successful to " + userAuthEntity.getEmail()
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<GlobeSuccessResponseBuilder> verifyEmailOTP(@Valid @RequestBody EmailOTPRequestBody emailOTPRequestBody) throws RandomExceptions, VerificationException, ItemNotFoundException {

        Object result = emailOTPService.verifyEmailOTP(emailOTPRequestBody.getEmail(), emailOTPRequestBody.getCode());

        GlobeSuccessResponseBuilder response = GlobeSuccessResponseBuilder.success(
                "OTP verification completed",
                result
        );

        return ResponseEntity.ok(response);
    }
}