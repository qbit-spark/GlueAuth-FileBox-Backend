package org.qbitspark.glueauthfileboxbackend.authentication_service.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Service.AccountService;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.AccountEntity;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.*;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.*;
import org.qbitspark.glueauthfileboxbackend.globeresponsebody.GlobeSuccessResponseBuilder;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@RestController
@RequestMapping("api/v1/auth")
public class GlobeAuthMngController {

    private final AccountService accountService;

    @PostMapping("/register")
    public ResponseEntity<GlobeSuccessResponseBuilder> accountRegistration(@Valid @RequestBody CreateAccountRequest createAccountRequest) throws RandomExceptions, JsonProcessingException, ItemReadyExistException, ItemNotFoundException {

        accountService.registerAccount(createAccountRequest);

        GlobeSuccessResponseBuilder response = GlobeSuccessResponseBuilder.success(
                "User account created successful, please verify your email"
        );

        return ResponseEntity.status(201).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<GlobeSuccessResponseBuilder> accountLogin(@Valid @RequestBody AccountLoginRequest accountLoginRequest) throws VerificationException, ItemNotFoundException {

        LoginResponse loginResponse = accountService.loginAccount(accountLoginRequest);

        GlobeSuccessResponseBuilder response = GlobeSuccessResponseBuilder.success(
                "Account login successful",
                loginResponse
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/refreshToken")
    public ResponseEntity<GlobeSuccessResponseBuilder> refreshToken(@Valid @RequestBody RefreshTokenRequest refreshTokenRequest) throws RandomExceptions, TokenInvalidException {

        RefreshTokenResponse refreshTokenResponse = accountService.refreshToken(refreshTokenRequest.getRefreshToken());

        GlobeSuccessResponseBuilder response = GlobeSuccessResponseBuilder.success(
                "Token refreshed successful",
                refreshTokenResponse
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/all-users")
    public ResponseEntity<GlobeSuccessResponseBuilder> getAllUsers() {

        List<AccountEntity> userList = accountService.getAllAccounts();

        GlobeSuccessResponseBuilder response = GlobeSuccessResponseBuilder.success(
                "All users retrieved successfully",
                userList
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/single-user/{userId}")
    public ResponseEntity<GlobeSuccessResponseBuilder> getSingleUser(@PathVariable UUID userId) throws ItemNotFoundException {

        AccountEntity user = accountService.getAccountByID(userId);

        GlobeSuccessResponseBuilder response = GlobeSuccessResponseBuilder.success(
                "User details retrieved successfully",
                user
        );

        return ResponseEntity.ok(response);
    }
}