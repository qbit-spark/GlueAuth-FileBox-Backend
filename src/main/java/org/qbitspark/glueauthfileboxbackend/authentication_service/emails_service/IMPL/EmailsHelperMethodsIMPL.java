package org.qbitspark.glueauthfileboxbackend.authentication_service.emails_service.IMPL;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailsHelperMethodsIMPL {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${app.name:BuildWise}")
    private String appName;

    // ==================== BASIC EMAIL METHODS ====================

    /**
     * Send simple text email to single recipient
     */
    public void sendSimpleTextEmail(String email, String subject, String textContent) throws Exception {
        try {
            log.info("Sending simple text email to: {}", email);

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(email);
            message.setSubject(subject);
            message.setText(textContent);

            mailSender.send(message);
            log.info("Simple text email sent successfully to: {}", email);

        } catch (Exception e) {
            log.error("Failed to send simple text email to: {}", email, e);
            throw new Exception("Failed to send simple text email: " + e.getMessage(), e);
        }
    }

    /**
     * Send simple text email to multiple recipients
     */
    public void sendSimpleTextEmailToMultiple(List<String> emails, String subject, String textContent) throws Exception {
        try {
            log.info("Sending simple text email to {} recipients", emails.size());

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(emails.toArray(new String[0]));
            message.setSubject(subject);
            message.setText(textContent);

            mailSender.send(message);
            log.info("Simple text email sent successfully to {} recipients", emails.size());

        } catch (Exception e) {
            log.error("Failed to send simple text email to multiple recipients", e);
            throw new Exception("Failed to send simple text email to multiple recipients: " + e.getMessage(), e);
        }
    }

    // ==================== HTML EMAIL METHODS ====================

    /**
     * Send HTML email to single recipient
     */
    public void emilWithHtmlTemplate(String email, String subject, String htmlContent) throws Exception {
        try {
            log.info("Sending HTML email to: {}", email);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail, appName);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(mimeMessage);
            log.info("HTML email sent successfully to: {}", email);

        } catch (MessagingException e) {
            log.error("Failed to send HTML email to: {}", email, e);
            throw new Exception("Failed to send HTML email: " + e.getMessage(), e);
        }
    }

    /**
     * Send HTML email to multiple recipients
     */
    public void sendHtmlEmailToMultiple(List<String> emails, String subject, String htmlContent) throws Exception {
        try {
            log.info("Sending HTML email to {} recipients", emails.size());

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail, appName);
            helper.setTo(emails.toArray(new String[0]));
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(mimeMessage);
            log.info("HTML email sent successfully to {} recipients", emails.size());

        } catch (MessagingException e) {
            log.error("Failed to send HTML email to multiple recipients", e);
            throw new Exception("Failed to send HTML email to multiple recipients: " + e.getMessage(), e);
        }
    }

    // ==================== EMAIL WITH CC/BCC METHODS ====================

    /**
     * Send email with CC and BCC
     */
    public void sendEmailWithCcBcc(String to, List<String> cc, List<String> bcc, String subject, String content, boolean isHtml) throws Exception {
        try {
            log.info("Sending email with CC/BCC to: {}", to);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail, appName);
            helper.setTo(to);

            if (cc != null && !cc.isEmpty()) {
                helper.setCc(cc.toArray(new String[0]));
            }

            if (bcc != null && !bcc.isEmpty()) {
                helper.setBcc(bcc.toArray(new String[0]));
            }

            helper.setSubject(subject);
            helper.setText(content, isHtml);

            mailSender.send(mimeMessage);
            log.info("Email with CC/BCC sent successfully to: {}", to);

        } catch (MessagingException e) {
            log.error("Failed to send email with CC/BCC to: {}", to, e);
            throw new Exception("Failed to send email with CC/BCC: " + e.getMessage(), e);
        }
    }

    // ==================== ATTACHMENT METHODS ====================

    /**
     * Send email with single attachment (file path)
     */
    public void emilWithAttachment(String email, String subject, String content, String filePath) throws Exception {
        try {
            log.info("Sending email with attachment to: {}", email);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail, appName);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(content, false);

            // Add attachment from file path
            FileSystemResource file = new FileSystemResource(new File(filePath));
            helper.addAttachment(file.getFilename(), file);

            mailSender.send(mimeMessage);
            log.info("Email with attachment sent successfully to: {}", email);

        } catch (MessagingException e) {
            log.error("Failed to send email with attachment to: {}", email, e);
            throw new Exception("Failed to send email with attachment: " + e.getMessage(), e);
        }
    }

    /**
     * Send email with single attachment (MultipartFile)
     */
    public void sendEmailWithMultipartAttachment(String email, String subject, String content, MultipartFile file) throws Exception {
        try {
            log.info("Sending email with multipart attachment to: {}", email);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail, appName);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(content, false);

            // Add attachment from MultipartFile
            helper.addAttachment(file.getOriginalFilename(), new ByteArrayResource(file.getBytes()));

            mailSender.send(mimeMessage);
            log.info("Email with multipart attachment sent successfully to: {}", email);

        } catch (MessagingException | IOException e) {
            log.error("Failed to send email with multipart attachment to: {}", email, e);
            throw new Exception("Failed to send email with multipart attachment: " + e.getMessage(), e);
        }
    }

    /**
     * Send email with multiple attachments (file paths)
     */
    public void sendEmailWithMultipleAttachments(String email, String subject, String content, List<String> filePaths) throws Exception {
        try {
            log.info("Sending email with {} attachments to: {}", filePaths.size(), email);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail, appName);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(content, false);

            // Add multiple attachments
            for (String filePath : filePaths) {
                FileSystemResource file = new FileSystemResource(new File(filePath));
                helper.addAttachment(file.getFilename(), file);
            }

            mailSender.send(mimeMessage);
            log.info("Email with {} attachments sent successfully to: {}", filePaths.size(), email);

        } catch (MessagingException e) {
            log.error("Failed to send email with multiple attachments to: {}", email, e);
            throw new Exception("Failed to send email with multiple attachments: " + e.getMessage(), e);
        }
    }

    /**
     * Send email with multiple MultipartFile attachments
     */
    public void sendEmailWithMultipleMultipartAttachments(String email, String subject, String content, List<MultipartFile> files) throws Exception {
        try {
            log.info("Sending email with {} multipart attachments to: {}", files.size(), email);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail, appName);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(content, false);

            // Add multiple MultipartFile attachments
            for (MultipartFile file : files) {
                helper.addAttachment(file.getOriginalFilename(), new ByteArrayResource(file.getBytes()));
            }

            mailSender.send(mimeMessage);
            log.info("Email with {} multipart attachments sent successfully to: {}", files.size(), email);

        } catch (MessagingException | IOException e) {
            log.error("Failed to send email with multiple multipart attachments to: {}", email, e);
            throw new Exception("Failed to send email with multiple multipart attachments: " + e.getMessage(), e);
        }
    }

    // ==================== HTML + ATTACHMENT METHODS ====================

    /**
     * Send HTML email with single attachment
     */
    public void sendHtmlEmailWithAttachment(String email, String subject, String htmlContent, String filePath) throws Exception {
        try {
            log.info("Sending HTML email with attachment to: {}", email);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail, appName);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            // Add attachment
            FileSystemResource file = new FileSystemResource(new File(filePath));
            helper.addAttachment(file.getFilename(), file);

            mailSender.send(mimeMessage);
            log.info("HTML email with attachment sent successfully to: {}", email);

        } catch (MessagingException e) {
            log.error("Failed to send HTML email with attachment to: {}", email, e);
            throw new Exception("Failed to send HTML email with attachment: " + e.getMessage(), e);
        }
    }

    /**
     * Send HTML email with multiple attachments
     */
    public void sendHtmlEmailWithMultipleAttachments(String email, String subject, String htmlContent, List<String> filePaths) throws Exception {
        try {
            log.info("Sending HTML email with {} attachments to: {}", filePaths.size(), email);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail, appName);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            // Add multiple attachments
            for (String filePath : filePaths) {
                FileSystemResource file = new FileSystemResource(new File(filePath));
                helper.addAttachment(file.getFilename(), file);
            }

            mailSender.send(mimeMessage);
            log.info("HTML email with {} attachments sent successfully to: {}", filePaths.size(), email);

        } catch (MessagingException e) {
            log.error("Failed to send HTML email with multiple attachments to: {}", email, e);
            throw new Exception("Failed to send HTML email with multiple attachments: " + e.getMessage(), e);
        }
    }

    // ==================== TEMPLATE-BASED EMAIL METHODS ====================

    /**
     * Send email using Thymeleaf template
     */
    public void sendTemplateEmail(String email, String subject, String templateName, Map<String, Object> variables) throws Exception {
        try {
            log.info("Sending template email to: {} using template: {}", email, templateName);

            // Process the template with variables
            Context context = new Context();
            context.setVariables(variables);
            String htmlContent = templateEngine.process(templateName, context);

            // Send the processed template as an HTML email
            emilWithHtmlTemplate(email, subject, htmlContent);

            log.info("Template email sent successfully to: {}", email);

        } catch (Exception e) {
            log.error("Failed to send template email to: {}", email, e);
            throw new Exception("Failed to send template email: " + e.getMessage(), e);
        }
    }

    /**
     * Send template email to multiple recipients
     */
    public void sendTemplateEmailToMultiple(List<String> emails, String subject, String templateName, Map<String, Object> variables) throws Exception {
        try {
            log.info("Sending template email to {} recipients using template: {}", emails.size(), templateName);

            // Process the template with variables
            Context context = new Context();
            context.setVariables(variables);
            String htmlContent = templateEngine.process(templateName, context);

            // Send the processed template as HTML email to multiple recipients
            sendHtmlEmailToMultiple(emails, subject, htmlContent);

            log.info("Template email sent successfully to {} recipients", emails.size());

        } catch (Exception e) {
            log.error("Failed to send template email to multiple recipients", e);
            throw new Exception("Failed to send template email to multiple recipients: " + e.getMessage(), e);
        }
    }

    // ==================== ADVANCED EMAIL METHODS ====================

    /**
     * Send high priority email
     */
    public void sendHighPriorityEmail(String email, String subject, String content, boolean isHtml) throws Exception {
        try {
            log.info("Sending high priority email to: {}", email);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail, appName);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(content, isHtml);

            // Set high priority
            mimeMessage.setHeader("X-Priority", "1");
            mimeMessage.setHeader("X-MSMail-Priority", "High");
            mimeMessage.setHeader("Importance", "High");

            mailSender.send(mimeMessage);
            log.info("High priority email sent successfully to: {}", email);

        } catch (MessagingException e) {
            log.error("Failed to send high priority email to: {}", email, e);
            throw new Exception("Failed to send high priority email: " + e.getMessage(), e);
        }
    }

    /**
     * Send email with embedded images
     */
    public void sendHtmlEmailWithEmbeddedImages(String email, String subject, String htmlContent, Map<String, String> embeddedImages) throws Exception {
        try {
            log.info("Sending HTML email with embedded images to: {}", email);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail, appName);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            // Add embedded images
            for (Map.Entry<String, String> entry : embeddedImages.entrySet()) {
                String cid = entry.getKey();
                String imagePath = entry.getValue();
                FileSystemResource image = new FileSystemResource(new File(imagePath));
                helper.addInline(cid, image);
            }

            mailSender.send(mimeMessage);
            log.info("HTML email with embedded images sent successfully to: {}", email);

        } catch (MessagingException e) {
            log.error("Failed to send HTML email with embedded images to: {}", email, e);
            throw new Exception("Failed to send HTML email with embedded images: " + e.getMessage(), e);
        }
    }

    /**
     * Send bulk email to large list of recipients
     */
    public void sendBulkEmail(List<String> emails, String subject, String content, boolean isHtml) throws Exception {
        try {
            log.info("Sending bulk email to {} recipients", emails.size());

            // Split into batches to avoid overwhelming mail server
            int batchSize = 50; // Adjust based on your mail server limits
            for (int i = 0; i < emails.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, emails.size());
                List<String> batch = emails.subList(i, endIndex);

                if (isHtml) {
                    sendHtmlEmailToMultiple(batch, subject, content);
                } else {
                    sendSimpleTextEmailToMultiple(batch, subject, content);
                }

                // Small delay between batches to avoid being marked as spam
                Thread.sleep(1000);
            }

            log.info("Bulk email sent successfully to {} recipients", emails.size());

        } catch (Exception e) {
            log.error("Failed to send bulk email", e);
            throw new Exception("Failed to send bulk email: " + e.getMessage(), e);
        }
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Validate email format
     */
    public boolean isValidEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            return false;
        }

        String emailRegex = "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$";
        return email.matches(emailRegex);
    }

    /**
     * Validate list of emails
     */
    public List<String> validateEmailList(List<String> emails) {
        return emails.stream()
                .filter(this::isValidEmail)
                .toList();
    }

    /**
     * Legacy method for backward compatibility
     */
    public void emilWithTextTemplate(String email, String subject, String textContent) throws Exception {
        sendSimpleTextEmail(email, subject, textContent);
    }

    /**
     * Legacy method for backward compatibility
     */
    public void emilWithAttachment(String email, String subject, String filePath) throws Exception {
        emilWithAttachment(email, subject, "Please find the attached file.", filePath);
    }
}