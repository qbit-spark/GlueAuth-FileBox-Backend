package org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
