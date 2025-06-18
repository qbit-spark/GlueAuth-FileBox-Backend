package org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions;

public class TokenInvalidSignatureException extends Exception{
    public TokenInvalidSignatureException(String message){
        super(message);
    }
}
