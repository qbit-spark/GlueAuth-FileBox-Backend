package org.qbitspark.glueauthfileboxbackend.authentication_service.Service.IMPL;


import lombok.RequiredArgsConstructor;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.AccountRepo;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.RolesRepository;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Service.AccountService;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Service.EmailOTPService;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.AccountEntity;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.Roles;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.AccountLoginRequest;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.CreateAccountRequest;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.LoginResponse;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.RefreshTokenResponse;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.*;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.*;
import org.qbitspark.glueauthfileboxbackend.globesecurity.JWTProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.qbitspark.glueauthfileboxbackend.authentication_service.enums.VerificationChannels.*;

@RequiredArgsConstructor
@Service
public class AccountServiceIMPL implements AccountService {

    private final AccountRepo accountRepo;
    private final RolesRepository rolesRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JWTProvider tokenProvider;
    private final EmailOTPService emailOTPService;


    @Override
    public AccountEntity registerAccount(CreateAccountRequest createAccountRequest) throws ItemReadyExistException, RandomExceptions, ItemNotFoundException {

        //check the existence of user
        if (accountRepo.existsByPhoneNumberOrEmailOrUserName(createAccountRequest.getPhoneNumber(),
                createAccountRequest.getEmail(),
                generateUserName(createAccountRequest.getEmail()))) {
            throw new ItemReadyExistException("User with provided credentials already exist, please login");
        }

        AccountEntity account = new AccountEntity();
        account.setUserName(generateUserName(createAccountRequest.getEmail()));
        account.setCreatedAt(LocalDateTime.now());
        account.setEditedAt(LocalDateTime.now());
        account.setIsVerified(false);
        account.setEmail(createAccountRequest.getEmail());
        account.setPhoneNumber(createAccountRequest.getPhoneNumber());
        account.setPassword(passwordEncoder.encode(createAccountRequest.getPassword()));
        //set the user role
        Set<Roles> roles = new HashSet<>();
        Roles userRoles = rolesRepository.findByRoleName("ROLE_NORMAL_USER").get();
        roles.add(userRoles);
        account.setRoles(roles);

        AccountEntity savedAccount = accountRepo.save(account);

        //Check a selected verification channel
        switch (createAccountRequest.getVerificationChannel()) {
            case EMAIL -> //Send the OTP via email
                    emailOTPService.generateAndSendEmailOTP(savedAccount, "Account Verification", "Please use the following OTP to complete your registration: ");
            case SMS -> {
                System.out.println("SMS verification is not implemented yet.");
            }
            case EMAIL_AND_SMS -> {
                System.out.println("Email and SMS verification is not implemented yet.");
            }

            case SMS_AND_WHATSAPP -> {
                System.out.println("SMS and WhatsApp verification is not implemented yet.");
            }
            case WHATSAPP -> {
                System.out.println("WhatsApp verification is not implemented yet.");
            }
            case VOICE_CALL -> {
                System.out.println("Voice call verification is not implemented yet.");
            }
            case PUSH_NOTIFICATION -> {
                System.out.println("Push notification verification is not implemented yet.");
            }
            case ALL_CHANNELS -> {
                System.out.println("All channels verification is not implemented yet.");
            }
            default -> {
                //Send the OTP via Email
                emailOTPService.generateAndSendEmailOTP(savedAccount,
                        "Welcome to BuildWise Books Support!", "Please use the following OTP to complete your registration: ");
            }

        }

        return savedAccount;
    }

    @Override
    public LoginResponse loginAccount(AccountLoginRequest accountLoginRequest) throws VerificationException, ItemNotFoundException {

            String input = accountLoginRequest.getPhoneEmailOrUserName();
            String password = accountLoginRequest.getPassword();

            // Determine the type of input (phone number, email, or username)
            AccountEntity user = null;
            if (isEmail(input)) {
                user = accountRepo.findByEmail(input).orElseThrow(
                        () -> new ItemNotFoundException("User with provided email does not exist")
                );
            } else if (isPhoneNumber(input)) {
                user = accountRepo.findAccountEntitiesByPhoneNumber(input).orElseThrow(() -> new ItemNotFoundException("phone number do not exist"));
            } else {
                user = accountRepo.findByUserName(input).orElseThrow(
                        () -> new ItemNotFoundException("User with provided username does not exist")
                );
            }
            if (user != null) {

                Authentication authentication = authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(
                                user.getUserName(),
                                password));

                if (!user.getIsVerified()) {
                    throw new VerificationException("Account not verified, please verify");
                }

                SecurityContextHolder.getContext().setAuthentication(authentication);
                String accessToken = tokenProvider.generateAccessToken(authentication);
                String refreshToken = tokenProvider.generateRefreshToken(authentication);

                LoginResponse loginResponse = new LoginResponse();
                loginResponse.setAccessToken(accessToken);
                loginResponse.setRefreshToken(refreshToken);
                loginResponse.setUserData(user);


                return loginResponse;

            } else {
                throw new ItemNotFoundException("User with provided details does not exist, register");
            }

    }

    @Override
    public RefreshTokenResponse refreshToken(String refreshToken) throws TokenInvalidException {
        try {
            // First, validate that this is specifically a refresh token
            if (!tokenProvider.validToken(refreshToken, "REFRESH")) {
                throw new TokenInvalidException("Invalid token");
            }

            // Get username from a token
            String userName = tokenProvider.getUserName(refreshToken);

            // Retrieve user from database
            AccountEntity user = accountRepo.findByUserName(userName)
                    .orElseThrow(() -> new ItemNotFoundException("User not found"));

            // Create authentication with user authorities
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    user.getUserName(),
                    null,
                    mapRolesToAuthorities(user.getRoles())
            );

            // Generate only a new access token, not a new refresh token
            String newAccessToken = tokenProvider.generateAccessToken(authentication);

            // Build response
            RefreshTokenResponse refreshTokenResponse = new RefreshTokenResponse();
            refreshTokenResponse.setNewToken(newAccessToken);

            return refreshTokenResponse;

        } catch (TokenExpiredException e) {
            throw new TokenInvalidException("Refresh token has expired. Please login again");
        } catch (Exception e) {
            throw new TokenInvalidException("Failed to refresh token: " + e.getMessage());
        } finally {
            // Clear security context after token refresh
            SecurityContextHolder.clearContext();
        }
    }

    @Override
    public List<AccountEntity> getAllAccounts() {
        return accountRepo.findAll();
    }

    @Override
    public AccountEntity getAccountByID(UUID userId) throws ItemNotFoundException {
        return accountRepo.findById(userId).orElseThrow(() -> new ItemNotFoundException("No such user"));
    }

    private String generateUserName(String email) {

        StringBuilder username = new StringBuilder();
        for (int i = 0; i < email.length(); i++) {
            char c = email.charAt(i);
            if (c != '@') {
                username.append(c);
            } else {
                break;
            }
        }
        return username.toString();
    }


    private boolean isPhoneNumber(String input) {
        // Regular expression pattern for validating phone numbers
        String phoneRegex = "^\\+(?:[0-9] ?){6,14}[0-9]$";
        // Compile the pattern into a regex pattern object
        Pattern pattern = Pattern.compile(phoneRegex);
        // Use the pattern matcher to test if the input matches the pattern
        return input != null && pattern.matcher(input).matches();
    }

    private boolean isEmail(String input) {
        // Regular expression pattern for validating email addresses
        String emailRegex = "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$";
        // Compile the pattern into a regex pattern object
        Pattern pattern = Pattern.compile(emailRegex);
        // Use the pattern matcher to test if the input matches the pattern
        return input != null && pattern.matcher(input).matches();
    }

    private Collection<? extends GrantedAuthority> mapRolesToAuthorities(Set<Roles> roles) {
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority(role.getRoleName()))
                .collect(Collectors.toList());
    }


}
