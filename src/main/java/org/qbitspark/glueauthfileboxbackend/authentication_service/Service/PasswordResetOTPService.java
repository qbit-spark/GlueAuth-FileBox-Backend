package org.qbitspark.glueauthfileboxbackend.authentication_service.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemNotFoundException;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.ItemReadyExistException;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.RandomExceptions;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.VerificationException;


public interface PasswordResetOTPService {
    String generateAndSendPSWDResetOTP(String email) throws ItemReadyExistException, RandomExceptions, JsonProcessingException, ItemNotFoundException;
    boolean verifyOTPAndResetPassword(String email, String otpCode, String newPassword) throws ItemReadyExistException, RandomExceptions, ItemNotFoundException, VerificationException;
}
