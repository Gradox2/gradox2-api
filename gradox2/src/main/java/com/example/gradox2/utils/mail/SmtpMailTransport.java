package com.example.gradox2.utils.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Transporte SMTP. Solo para desarrollo local y tests: en Render los puertos 25/465/587 están
 * bloqueados, así que producción usa {@link BrevoMailTransport}.
 */
@Component
@ConditionalOnProperty(name = "app.mail.transport", havingValue = "smtp", matchIfMissing = true)
public class SmtpMailTransport implements MailTransport {

    private static final Logger logger = LoggerFactory.getLogger(SmtpMailTransport.class);

    private final JavaMailSender mailSender;

    public SmtpMailTransport(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void send(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (RuntimeException e) {
            logger.warn("SMTP rechazó el mensaje para {}: {}", to, e.getMessage());
            throw new MailDeliveryException("SMTP no pudo entregar el mensaje", e);
        }
    }
}
