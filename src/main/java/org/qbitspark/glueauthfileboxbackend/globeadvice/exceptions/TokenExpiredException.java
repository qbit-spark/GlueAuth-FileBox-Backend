package org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions;

public class TokenExpiredException extends Exception{
    public TokenExpiredException(String message){
        super(message);
    }
}
