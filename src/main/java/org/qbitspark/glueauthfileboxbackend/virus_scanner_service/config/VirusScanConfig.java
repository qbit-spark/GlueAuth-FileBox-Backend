package org.qbitspark.glueauthfileboxbackend.virus_scanner_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Configuration
@Data
@ConfigurationProperties(prefix = "app.virus-scan")
@EnableConfigurationProperties
@Component
public class VirusScanConfig {

    private boolean enabled;
    private String clamavHost;
    private int clamavPort;
    private int timeoutMs;
    private boolean deleteInfectedFiles;

    // Add these missing properties
    private boolean failOnUnavailable = false;  // Allow uploads when ClamAV fails
    private boolean retryOnFailure = true;      // Retry on connection failure
    private int maxRetries = 3;                 // Number of retry attempts
    private int retryDelayMs = 2000;           // Delay between retries
}