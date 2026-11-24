package com.example.gradox2.utils.mail;

/**
 * Transporte de correo. Existe para que el envío no dependa de SMTP: Render bloquea la salida en los
 * puertos 25, 465 y 587, así que en producción se sale por HTTPS con una API (Brevo) y en local con
 * SMTP contra MailHog o consola.
 */
public interface MailTransport {

    /**
     * Envía un mensaje de texto plano.
     *
     * @throws MailDeliveryException si el proveedor rechaza el envío o no se puede alcanzar.
     */
    void send(String to, String subject, String body);
}
