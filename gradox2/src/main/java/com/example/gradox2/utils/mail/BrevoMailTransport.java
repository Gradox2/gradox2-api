package com.example.gradox2.utils.mail;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Transporte por API HTTP de Brevo (puerto 443).
 *
 * <p>Render bloquea la salida SMTP en 25, 465 y 587, así que el correo de producción no puede salir por
 * SMTP de Gmail: se sale por HTTPS. Brevo acepta un remitente verificado ({@code MAIL_FROM}) y entrega a
 * cualquier destinatario, incluidos los correos institucionales.
 *
 * <p>Si el transporte está activo y falta la API key, el arranque falla: es preferible un deploy que no
 * levanta a un servicio que acepta registros y nunca manda el correo de verificación.
 */
@Component
@ConditionalOnProperty(name = "app.mail.transport", havingValue = "brevo")
public class BrevoMailTransport implements MailTransport {

    private static final Logger logger = LoggerFactory.getLogger(BrevoMailTransport.class);

    /** Endpoint fijo de Brevo; se puede sobreescribir solo para pruebas. */
    static final String DEFAULT_API_URL = "https://api.brevo.com";

    private final RestClient client;
    private final String from;
    private final String fromName;

    @Autowired
    public BrevoMailTransport(
            @Value("${app.mail.api-key:}") String apiKey,
            @Value("${app.mail.from:}") String from,
            @Value("${app.mail.from-name:Gradox2}") String fromName,
            @Value("${app.mail.api-url:" + DEFAULT_API_URL + "}") String apiUrl) {
        this(validatedBuilder(apiKey, from, apiUrl), apiKey, from, fromName);
    }

    /**
     * El builder llega con su base URL ya fijada por {@link #validatedBuilder}. No se vuelve a fijar
     * acá: `app.mail.api-url` es parte del contrato, y un mock o un proxy configurado por entorno no
     * puede terminar enviando —con la API key— al endpoint real de Brevo.
     */
    BrevoMailTransport(RestClient.Builder builder, String apiKey, String from, String fromName) {
        this.from = from;
        this.fromName = fromName;
        this.client = builder
                .defaultHeader("api-key", apiKey == null ? "" : apiKey)
                .build();
    }

    private static RestClient.Builder validatedBuilder(String apiKey, String from, String apiUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "app.mail.transport=brevo requiere BREVO_API_KEY; sin ella el correo de verificación no sale.");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalStateException(
                    "app.mail.transport=brevo requiere MAIL_FROM (remitente verificado en Brevo).");
        }
        return baseBuilder(apiUrl);
    }

    private static RestClient.Builder baseBuilder(String apiUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder()
                .baseUrl(apiUrl)
                .requestFactory(factory);
    }

    @Override
    public void send(String to, String subject, String body) {
        Map<String, Object> payload = Map.of(
                "sender", Map.of("email", from, "name", fromName),
                "to", List.of(Map.of("email", to)),
                "subject", subject,
                "textContent", body);

        try {
            client.post()
                    .uri("/v3/smtp/email")
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            logger.warn("Brevo rechazó el mensaje para {}: {}", to, e.getMessage());
            throw new MailDeliveryException("Brevo no aceptó el mensaje", e);
        }
    }
}
