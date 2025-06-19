package org.qbitspark.glueauthfileboxbackend.minio_service.config;

import io.minio.MinioClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "minio")
@Data
public class MinioConfig {

    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucketPrefix = "tenant-";
    private boolean secure = false;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}