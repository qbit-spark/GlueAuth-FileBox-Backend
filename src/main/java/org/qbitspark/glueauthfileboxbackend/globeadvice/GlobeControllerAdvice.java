package org.qbitspark.glueauthfileboxbackend.globeadvice;


import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.*;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.*;
import org.qbitspark.glueauthfileboxbackend.globeresponsebody.GlobeFailureResponseBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobeControllerAdvice {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<GlobeFailureResponseBuilder> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        GlobeFailureResponseBuilder response = GlobeFailureResponseBuilder.unprocessableEntity(
                "Validation failed",
                errors
        );

        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }

    @ExceptionHandler(TokenEmptyException.class)
    public ResponseEntity<GlobeFailureResponseBuilder> generateTokenEmptyException(Exception exception) {
        GlobeFailureResponseBuilder response = GlobeFailureResponseBuilder.unauthorized(exception.getMessage());
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }

    @ExceptionHandler(TokenInvalidException.class)
    public ResponseEntity<GlobeFailureResponseBuilder> generateInvalidTokenException(Exception exception) {
        GlobeFailureResponseBuilder response = GlobeFailureResponseBuilder.unauthorized(exception.getMessage());
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }

    @ExceptionHandler(TokenExpiredException.class)
    public ResponseEntity<GlobeFailureResponseBuilder> generateTokenExpirationException(Exception exception) {
        GlobeFailureResponseBuilder response = GlobeFailureResponseBuilder.unauthorized(exception.getMessage());
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }

    @ExceptionHandler(TokenInvalidSignatureException.class)
    public ResponseEntity<GlobeFailureResponseBuilder> generateTokenInvalidSignatureException(Exception exception) {
        GlobeFailureResponseBuilder response = GlobeFailureResponseBuilder.unauthorized(exception.getMessage());
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }

    @ExceptionHandler(TokenUnsupportedException.class)
    public ResponseEntity<GlobeFailureResponseBuilder> generateTokenUnsupportedException(Exception exception) {
        GlobeFailureResponseBuilder response = GlobeFailureResponseBuilder.unauthorized(exception.getMessage());
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }

    @ExceptionHandler(VerificationException.class)
    public ResponseEntity<GlobeFailureResponseBuilder> getVerificationException(Exception exception) {
        GlobeFailureResponseBuilder response = GlobeFailureResponseBuilder.forbidden(exception.getMessage());
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }

    @ExceptionHandler(RandomExceptions.class)
    public ResponseEntity<GlobeFailureResponseBuilder> getRandomExceptions(Exception exception) {
        GlobeFailureResponseBuilder response = GlobeFailureResponseBuilder.badRequest(exception.getMessage());
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }

    @ExceptionHandler(ItemNotFoundException.class)
    public ResponseEntity<GlobeFailureResponseBuilder> getItemNotFoundExceptions(Exception exception) {
        GlobeFailureResponseBuilder response = GlobeFailureResponseBuilder.notFound(exception.getMessage());
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }

    @ExceptionHandler(ItemReadyExistException.class)
    public ResponseEntity<GlobeFailureResponseBuilder> itemReadyExist(Exception exception) {
        GlobeFailureResponseBuilder response = GlobeFailureResponseBuilder.badRequest(exception.getMessage());
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<GlobeFailureResponseBuilder> generateAccessDeniedExceptionException(Exception exception) {
        GlobeFailureResponseBuilder response = GlobeFailureResponseBuilder.forbidden(exception.getMessage());
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<GlobeFailureResponseBuilder> generatePermissionDeniedException(Exception exception) {
        GlobeFailureResponseBuilder response = GlobeFailureResponseBuilder.forbidden(exception.getMessage());
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }

    @ExceptionHandler(InvitationExpiredException.class)
    public ResponseEntity<GlobeFailureResponseBuilder> generateInvitationExpiredException(Exception exception) {
        GlobeFailureResponseBuilder response = GlobeFailureResponseBuilder.forbidden(exception.getMessage());
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }

    @ExceptionHandler(InvitationAlreadyProcessedException.class)
    public ResponseEntity<GlobeFailureResponseBuilder> generateInvitationAlreadyProcessedException(Exception exception) {
        GlobeFailureResponseBuilder response = GlobeFailureResponseBuilder.forbidden(exception.getMessage());
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }


    @ExceptionHandler(Exception.class)
    public ResponseEntity<GlobeFailureResponseBuilder> handleAllExceptions(Exception exception) {
        // Handle the message trimming logic
        String trimmedMessage = trimExceptionMessage(exception.getMessage());

        GlobeFailureResponseBuilder response = GlobeFailureResponseBuilder.badRequest(trimmedMessage);
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }

    /**
     * Helper method to trim exception messages
     */
    private String trimExceptionMessage(String message) {
        if (message == null) return "An error occurred";

        // Handle specific cases with detailed error messages
        if (message.contains("is not available yet")) {
            // For daily special visibility errors, retain the full message
            return message;
        } else if (message.contains(":")) {
            // For other cases with colons, trim at the colon
            return message.split(":")[0].trim();
        } else {
            // Use the full message if no special handling is required
            return message;
        }
    }
}