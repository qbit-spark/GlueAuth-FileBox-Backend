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
public class GlobeSuccessResponseBuilder {

    @Builder.Default
    private Boolean success = true;

    @Builder.Default
    private HttpStatus httpStatus = HttpStatus.OK;

    private String message;

    @Builder.Default
    private LocalDateTime action_time = LocalDateTime.now();

    private Object data;

    // Success methods only
    public static GlobeSuccessResponseBuilder success(String message, Object data) {
        return GlobeSuccessResponseBuilder.builder()
                .message(message)
                .data(data)
                .build();
    }

    public static GlobeSuccessResponseBuilder success(String message) {
        return GlobeSuccessResponseBuilder.builder()
                .message(message)
                .build();
    }
}