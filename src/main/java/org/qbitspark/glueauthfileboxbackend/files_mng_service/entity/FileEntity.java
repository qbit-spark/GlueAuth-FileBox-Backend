package org.qbitspark.glueauthfileboxbackend.files_mng_service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.qbitspark.glueauthfileboxbackend.files_mng_service.enums.VirusScanStatus;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "file_table")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class FileEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID fileId;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String minioKey;

    @Column(nullable = false)
    private UUID userId;

    // Many files can belong to one folder
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    private FolderEntity folder;  // null means root level

    @Column(nullable = false)
    private Long fileSize;

    @Column
    private String mimeType;

    @Enumerated(EnumType.STRING)
    private VirusScanStatus scanStatus = VirusScanStatus.PENDING;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column(name = "is_deleted")
    private Boolean isDeleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

}