package org.qbitspark.glueauthfileboxbackend.authentication_service.enums;

import lombok.Getter;

@Getter
public enum ApiPermissionType {
    FILES_READ("files:read"),
    FILES_WRITE("files:write"),
    FILES_DELETE("files:delete"),
    FOLDERS_READ("folders:read"),
    FOLDERS_WRITE("folders:write"),
    FOLDERS_DELETE("folders:delete"),
    ADMIN("admin");

    private final String permission;

    ApiPermissionType(String permission) {
        this.permission = permission;
    }

    public static ApiPermissionType fromString(String permission) {
        for (ApiPermissionType type : ApiPermissionType.values()) {
            if (type.permission.equals(permission)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid permission: " + permission);
    }

    @Override
    public String toString() {
        return permission;
    }
}