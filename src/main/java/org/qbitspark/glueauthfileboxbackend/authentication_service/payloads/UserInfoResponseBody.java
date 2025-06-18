package org.qbitspark.glueauthfileboxbackend.authentication_service.payloads;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.Date;
import java.util.UUID;

@Data
public class UserInfoResponseBody {
    private UUID userId;
    private String phoneNumber;
    private String userName;
    @JsonIgnore
    private String password;
    private String email;
    private Date createdAt;
    private Date editedAt;
    private String roles;
    private Boolean isVerified;

}
