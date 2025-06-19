package org.qbitspark.glueauthfileboxbackend.virus_scanner_service.service.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import jakarta.annotation.PostConstruct;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.enums.VirusScanStatus;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.VirusScanException;
import org.qbitspark.glueauthfileboxbackend.virus_scanner_service.config.VirusScanConfig;
import org.qbitspark.glueauthfileboxbackend.virus_scanner_service.payload.VirusScanResult;
import org.qbitspark.glueauthfileboxbackend.virus_scanner_service.service.VirusScanService;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;
import xyz.capybara.clamav.ClamavClient;
import xyz.capybara.clamav.commands.scan.result.ScanResult;



@Service
@Slf4j
@RequiredArgsConstructor
public class ClamAvVirusScanService implements VirusScanService {

    private final VirusScanConfig config;
    private ClamavClient clamavClient;

    @PostConstruct
    public void init() {
        this.clamavClient = new ClamavClient(config.getClamavHost(), config.getClamavPort());
    }

    @Override
    public VirusScanResult scanFile(MultipartFile file) throws VirusScanException {
        if (!config.isEnabled()) {
            return VirusScanResult.builder()
                    .status(VirusScanStatus.CLEAN)
                    .message("Virus scanning is disabled")
                    .fileName(file.getOriginalFilename())
                    .scanDurationMs(0)
                    .build();
        }

        try {
            byte[] fileContent = file.getBytes();
            return scanFileContent(fileContent, file.getOriginalFilename());
        } catch (IOException e) {
            throw new VirusScanException("Failed to read file content: " + e.getMessage());
        }
    }

    @Override
    public VirusScanResult scanFileContent(byte[] fileContent, String fileName) throws VirusScanException {
        if (!config.isEnabled()) {
            return VirusScanResult.builder()
                    .status(VirusScanStatus.CLEAN)
                    .message("Virus scanning is disabled")
                    .fileName(fileName)
                    .scanDurationMs(0)
                    .build();
        }

        long startTime = System.currentTimeMillis();

        try {
            log.info("Starting virus scan for file: {}", fileName);

            if (!isClamAvAvailable()) {
                throw new VirusScanException("ClamAV daemon is not available");
            }

            ScanResult scanResult = clamavClient.scan(new ByteArrayInputStream(fileContent));
            long scanDuration = System.currentTimeMillis() - startTime;

            if (scanResult instanceof ScanResult.OK) {
                log.info("File scan completed - CLEAN: {} (took {}ms)", fileName, scanDuration);
                return VirusScanResult.builder()
                        .status(VirusScanStatus.CLEAN)
                        .message("File is clean")
                        .fileName(fileName)
                        .scanDurationMs(scanDuration)
                        .build();

            } else if (scanResult instanceof ScanResult.VirusFound) {
                ScanResult.VirusFound virusFound = (ScanResult.VirusFound) scanResult;
                String virusName = virusFound.getFoundViruses().isEmpty()
                        ? "Unknown virus"
                        : virusFound.getFoundViruses().keySet().iterator().next();
                log.warn("VIRUS DETECTED in file: {} - Virus: {}", fileName, virusName);
                return VirusScanResult.builder()
                        .status(VirusScanStatus.INFECTED)
                        .virusName(virusName)
                        .message("Virus detected: " + virusName)
                        .fileName(fileName)
                        .scanDurationMs(scanDuration)
                        .build();

            } else {
                throw new VirusScanException("Unknown scan result type: " + scanResult.getClass().getSimpleName());
            }

        } catch (Exception e) {
            if (e instanceof VirusScanException) {
                throw e;
            }
            throw new VirusScanException("Virus scan failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isClamAvAvailable() {
        try {
            clamavClient.ping();
            return true;
        } catch (Exception e) {
            log.warn("ClamAV daemon is not available: {}", e.getMessage());
            return false;
        }
    }
}