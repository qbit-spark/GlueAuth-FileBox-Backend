package org.qbitspark.glueauthfileboxbackend.globeresponsebody;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlobeFailureResponseBuilder {

    @Builder.Default
    private Boolean success = false;

    private HttpStatus httpStatus;
    private String message;

    @Builder.Default
    private LocalDateTime action_time = LocalDateTime.now();

    private Object data;

    // Error methods - ALL STATIC
    public static GlobeFailureResponseBuilder error(String message, HttpStatus httpStatus) {
        return GlobeFailureResponseBuilder.builder()
                .httpStatus(httpStatus)
                .message(message)
                .data(message)
                .build();
    }

    public static GlobeFailureResponseBuilder error(String message, HttpStatus httpStatus, Object data) {
        return GlobeFailureResponseBuilder.builder()
                .httpStatus(httpStatus)
                .message(message)
                .data(data)
                .build();
    }

    public static GlobeFailureResponseBuilder badRequest(String message) {
        return GlobeFailureResponseBuilder.builder()
                .httpStatus(HttpStatus.BAD_REQUEST)
                .message(message)
                .data(message)
                .build();
    }

    public static GlobeFailureResponseBuilder unauthorized(String message) {
        return GlobeFailureResponseBuilder.builder()
                .httpStatus(HttpStatus.UNAUTHORIZED)
                .message(message)
                .data(message)
                .build();
    }

    public static GlobeFailureResponseBuilder forbidden(String message) {
        return GlobeFailureResponseBuilder.builder()
                .httpStatus(HttpStatus.FORBIDDEN)
                .message(message)
                .data(message)
                .build();
    }

    public static GlobeFailureResponseBuilder notFound(String message) {
        return GlobeFailureResponseBuilder.builder()
                .httpStatus(HttpStatus.NOT_FOUND)
                .message(message)
                .data(message)
                .build();
    }

    public static GlobeFailureResponseBuilder unprocessableEntity(String message, Object data) {
        return GlobeFailureResponseBuilder.builder()
                .httpStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .message(message)
                .data(data)
                .build();
    }
}