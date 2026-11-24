package com.example.gradox2.utils;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.example.gradox2.utils.mail.MailTransport;

@Service
public class EmailService {
    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    /** Un solo reintento: los proveedores fallan por ráfagas, no por caídas largas. */
    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 200L;

    private final MailTransport transport;
    private final Executor executor;

    public EmailService(MailTransport transport, @Qualifier("taskExecutor") Executor executor) {
        this.transport = transport;
        this.executor = executor;
    }

    /**
     * Encola el envío en el executor de correo.
     *
     * <p>WAVE2 S2: el alta de usuario y el reset de contraseña no pueden depender del proveedor de
     * correo. Ni el rechazo de la tarea (pool lleno) ni el fallo de envío salen de este método: se
     * registran con el destinatario y, en el caso del envío, se reintentan una vez.
     */
    public void sendEmail(String to, String subject, String body) {
        try {
            executor.execute(() -> deliverWithRetry(to, subject, body));
        } catch (RejectedExecutionException e) {
            logger.error("Cola de correo llena: se descarta el envío a {} ({}).", to, subject, e);
        }
    }

    private void deliverWithRetry(String to, String subject, String body) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                transport.send(to, subject, body);
                logger.info("Correo enviado a: {}", to);
                return;
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) {
                    logger.error("No se pudo enviar el correo a {} tras {} intentos: {}",
                            to, MAX_ATTEMPTS, e.getMessage(), e);
                    return;
                }
                logger.warn("Fallo al enviar el correo a {} (intento {} de {}): {}",
                        to, attempt, MAX_ATTEMPTS, e.getMessage());
                sleepBeforeRetry();
            }
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
