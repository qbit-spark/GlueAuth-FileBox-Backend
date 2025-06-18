package org.qbitspark.glueauthfileboxbackend.authentication_service.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.AccountEntity;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.AccountLoginRequest;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.CreateAccountRequest;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.LoginResponse;
import org.qbitspark.glueauthfileboxbackend.authentication_service.payloads.RefreshTokenResponse;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.*;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.*;


import java.util.List;
import java.util.UUID;

public interface AccountService {

    AccountEntity registerAccount(CreateAccountRequest createAccountRequest) throws JsonProcessingException,
            ItemReadyExistException, RandomExceptions, ItemNotFoundException;

    LoginResponse loginAccount(AccountLoginRequest accountLoginRequest) throws VerificationException, ItemNotFoundException;

    RefreshTokenResponse refreshToken(String refreshToken) throws TokenInvalidException;

    List<AccountEntity> getAllAccounts();

    AccountEntity getAccountByID(UUID uuid) throws ItemNotFoundException;

}
