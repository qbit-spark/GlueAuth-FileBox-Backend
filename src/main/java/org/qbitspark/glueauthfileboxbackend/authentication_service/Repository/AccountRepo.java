package org.qbitspark.glueauthfileboxbackend.authentication_service.Repository;


import org.qbitspark.glueauthfileboxbackend.authentication_service.entity.AccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepo extends JpaRepository<AccountEntity, UUID> {
    Optional<AccountEntity> findAccountEntitiesByEmailOrPhoneNumberOrUserName(String email, String phoneNumber, String userName);
    Optional<AccountEntity> findAccountEntitiesByUserName(String userName);
    Optional<AccountEntity> findByEmail(String email);
    Optional<AccountEntity> findAccountEntitiesByPhoneNumber(String phoneNumber);
    Optional<AccountEntity> findByUserName(String username);
    Boolean existsByPhoneNumberOrEmailOrUserName(String phoneNumber, String email, String userName);
    Boolean existsByPhoneNumber(String phoneNumber);
    Boolean existsByEmail(String email);
    Boolean existsByUserName(String userName);

}
