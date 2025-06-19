package org.qbitspark.glueauthfileboxbackend.files_mng_service.enums;

public enum VirusScanStatus {
    PENDING,    // Just uploaded, not scanned yet
    SCANNING,   // Currently being scanned
    CLEAN,      // Safe to download
    INFECTED,   // Virus found, quarantined
    FAILED,   // Scan failed, treat as suspicious
    SKIPPED
}
