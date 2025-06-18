package org.qbitspark.glueauthfileboxbackend.authentication_service.Service;


import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.AccountEntity;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.RandomExceptions;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.VerificationException;
import org.qbitspark.glueauthfileboxbackend.globeresponsebody.GlobeSuccessResponseBuilder;

public interface EmailOTPService {
    void generateAndSendEmailOTP(AccountEntity userAuthEntity, String emailHeader, String instructionText) throws RandomExceptions, ItemNotFoundException;

    void generateAndSendPasswordResetEmail(AccountEntity userAuthEntity, String emailHeader, String instructionText) throws RandomExceptions, ItemNotFoundException;

    //Only this method should return GlobalJsonResponseBody direct from the service
    GlobeSuccessResponseBuilder verifyEmailOTP(String email, String otpCode) throws RandomExceptions, VerificationException, ItemNotFoundException;
}
