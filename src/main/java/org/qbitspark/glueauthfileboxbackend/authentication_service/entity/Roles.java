package org.qbitspark.glueauthfileboxbackend.authentication_service.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;


@Entity

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter

@Table(name = "roles_table")
public class Roles {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @JsonIgnore
    private UUID roleId;
    private String roleName;

    @Override
    public String toString() {
        return roleName;
    }
}
