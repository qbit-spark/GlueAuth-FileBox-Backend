package org.qbitspark.glueauthfileboxbackend.authentication_service.emails_service;

public interface GlobeMailService {
    boolean sendOTPEmail(String email, String otp, String userName, String header, String instructions) throws Exception;
    boolean sendOrganisationInvitationEmail(String email, String organisationName, String inviterName,
                                            String role, String invitationLInk) throws Exception;
    void sendProjectTeamMemberAddedEmail(String email, String userName, String projectName, String role, String projectLink) throws Exception;

}
