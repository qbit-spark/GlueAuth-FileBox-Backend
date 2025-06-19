package org.qbitspark.glueauthfileboxbackend.virus_scanner_service.service;

import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.VirusScanException;
import org.qbitspark.glueauthfileboxbackend.virus_scanner_service.payload.VirusScanResult;
import org.springframework.web.multipart.MultipartFile;

// VirusScanService.java - Service Interface
public interface VirusScanService {

    /**
     * Scans a file for viruses
     * @param file The multipart file to scan
     * @return VirusScanResult containing scan status and details
     * @throws VirusScanException if scanning fails
     */
    VirusScanResult scanFile(MultipartFile file) throws VirusScanException;

    /**
     * Scans file content from byte array
     * @param fileContent The file content as byte array
     * @param fileName The original filename for logging
     * @return VirusScanResult containing scan status and details
     * @throws VirusScanException if scanning fails
     */
    VirusScanResult scanFileContent(byte[] fileContent, String fileName) throws VirusScanException;

    /**
     * Checks if ClamAV daemon is available
     * @return true if ClamAV is available, false otherwise
     */
    boolean isClamAvAvailable();
}