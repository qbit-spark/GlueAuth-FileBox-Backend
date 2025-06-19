package org.qbitspark.glueauthfileboxbackend.files_mng_service.payload;

import lombok.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FileData {
    private byte[] content;
    private String originalFileName;
    private String contentType;
    private long size;

    public InputStream getInputStream() {
        return new ByteArrayInputStream(content);
    }

    public boolean isEmpty() {
        return content == null || content.length == 0;
    }
}