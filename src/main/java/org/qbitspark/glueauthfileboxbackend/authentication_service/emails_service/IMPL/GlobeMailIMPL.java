package org.qbitspark.glueauthfileboxbackend.authentication_service.emails_service.IMPL;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qbitspark.glueauthfileboxbackend.authentication_service.emails_service.GlobeMailService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;


@RequiredArgsConstructor
@Service
@Slf4j
public class GlobeMailIMPL implements GlobeMailService {

    private final EmailsHelperMethodsIMPL emailsHelperMethodsIMPL;

    @Override
    public boolean sendOTPEmail(String email, String otp, String userName, String textHeader, String instructions) throws Exception {
        try {
            log.info("Sending OTP email to: {} for user: {}", email, userName);

            Map<String, Object> templateVariables = new HashMap<>();
            templateVariables.put("emailHeader", textHeader);
            templateVariables.put("userName", userName != null ? userName : "User");
            templateVariables.put("instructionText", instructions);
            templateVariables.put("otpCode", otp);

            // Send email using template
            String subject = "Account Verification - Your OTP Code";
            emailsHelperMethodsIMPL.sendTemplateEmail(
                    email,
                    subject,
                    "verification_email",
                    templateVariables
            );

            log.info("OTP email sent successfully to: {}", email);
            return true;

        } catch (Exception e) {
            log.error("Failed to send OTP email to: {}", email, e);
            throw new Exception("Failed to send OTP email: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean sendOrganisationInvitationEmail(String email, String organisationName, String inviterName,
                                                   String role, String invitationLink) throws Exception {
        try {
            log.info("Sending organisation invitation email to: {} for organisation: {}", email, organisationName);

            Map<String, Object> templateVariables = new HashMap<>();
            templateVariables.put("organisationName", organisationName);
            templateVariables.put("inviterName", inviterName);
            templateVariables.put("role", role);
            templateVariables.put("invitationLink", invitationLink);


            // Send email using template
            String subject = "You're invited to join " + organisationName;
            emailsHelperMethodsIMPL.sendTemplateEmail(
                    email,
                    subject,
                    "organisation_invitation_email",
                    templateVariables
            );

            log.info("Organisation invitation email sent successfully to: {}", email);
            return true;

        } catch (Exception e) {
            log.error("Failed to send organisation invitation email to: {}", email, e);
            throw new Exception("Failed to send invitation email: " + e.getMessage(), e);
        }
    }

    @Override
    public void sendProjectTeamMemberAddedEmail(String email, String userName, String projectName, String role, String projectLink) throws Exception {
        try {
            log.info("Sending project team member added email to: {} for project: {}", email, projectName);

            Map<String, Object> templateVariables = new HashMap<>();
            templateVariables.put("userName", userName != null ? userName : "Team Member");
            templateVariables.put("projectName", projectName);
            templateVariables.put("role", role);
            templateVariables.put("projectLink", projectLink);

            // Add some additional context for the email
            templateVariables.put("emailHeader", "Welcome to the Team!");
            templateVariables.put("welcomeMessage", "You have been added to a new project");

            // Send email using template
            String subject = userName+" , welcome to Project! " + projectName;
            emailsHelperMethodsIMPL.sendTemplateEmail(
                    email,
                    subject,
                    "project_team_member_added_email",
                    templateVariables
            );

            log.info("Project team member added email sent successfully to: {} for project: {}", email, projectName);

        } catch (Exception e) {
            log.error("Failed to send project team member added email to: {} for project: {}", email, projectName, e);
            throw new Exception("Failed to send project team member added email: " + e.getMessage(), e);
        }
    }

}
