package org.qbitspark.glueauthfileboxbackend;

import org.qbitspark.glueauthfileboxbackend.authentication_service.Repository.RolesRepository;
import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.Roles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GlueAuthFileBoxBackendApplication implements CommandLineRunner {

    @Autowired
    private RolesRepository roleRepository;

    public static void main(String[] args) {
        SpringApplication.run(GlueAuthFileBoxBackendApplication.class, args);
    }


    @Override
    public void run(String... args) throws Exception {
        createRoleIfNotExists("ROLE_SUPER_ADMIN");
        createRoleIfNotExists("ROLE_NORMAL_USER");
    }


    private void createRoleIfNotExists(String roleName) {
        Roles existingRole = roleRepository.findByRoleName(roleName).orElse(null);

        if (existingRole == null) {
            Roles newRole = new Roles();
            newRole.setRoleName(roleName);
            roleRepository.save(newRole);
        }
    }
}
