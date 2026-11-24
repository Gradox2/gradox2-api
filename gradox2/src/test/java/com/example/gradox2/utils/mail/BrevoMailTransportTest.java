package com.example.gradox2.utils.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Transporte de correo de producción: Brevo por HTTPS.
 *
 * <p>Render bloquea los puertos SMTP de salida (25/465/587), así que el correo de verificación no puede
 * salir por SMTP de Gmail. Este test fija el contrato con la API y que el perfil de producción elige
 * ese transporte.
 */
class BrevoMailTransportTest {

    private static final String API_KEY = "xkeysib-test";
    private static final String FROM = "gradox2app@gmail.com";

    @Test
    void sendsTheVerificationMailThroughTheBrevoApi() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrevoMailTransport transport = new BrevoMailTransport(builder, API_KEY, FROM, "Gradox2");

        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", API_KEY))
                .andExpect(jsonPath("$.sender.email").value(FROM))
                .andExpect(jsonPath("$.sender.name").value("Gradox2"))
                .andExpect(jsonPath("$.to[0].email").value("alumno@rai.usc.es"))
                .andExpect(jsonPath("$.subject").value("Verifica tu cuenta"))
                .andExpect(jsonPath("$.textContent").value("Haz clic aquí"))
                .andRespond(withSuccess());

        transport.send("alumno@rai.usc.es", "Verifica tu cuenta", "Haz clic aquí");

        server.verify();
    }

    @Test
    void aProviderRejectionIsReportedAsDeliveryFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrevoMailTransport transport = new BrevoMailTransport(builder, API_KEY, FROM, "Gradox2");

        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> transport.send("alumno@rai.usc.es", "Verifica tu cuenta", "cuerpo"))
                .isInstanceOf(MailDeliveryException.class);
        server.verify();
    }

    @Test
    void missingApiKeyFailsFastInsteadOfSendingNothing() {
        assertThatThrownBy(() -> new BrevoMailTransport("", FROM, "Gradox2", BrevoMailTransport.DEFAULT_API_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BREVO_API_KEY");
    }

    @Test
    void productionProfileSelectsTheHttpTransportAndRequiresASender() throws Exception {
        Properties prod = new Properties();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("application-prod.properties")) {
            assertThat(in).as("application-prod.properties debe estar en el classpath").isNotNull();
            prod.load(in);
        }

        assertThat(prod.getProperty("app.mail.transport"))
                .as("producción no puede usar SMTP: Render bloquea 25/465/587, así que no hay override")
                .isEqualTo("brevo");
        assertThat(prod.getProperty("app.mail.api-key"))
                .as("la API key de Brevo entra por variable de entorno")
                .isEqualTo("${BREVO_API_KEY:}");
        assertThat(prod.getProperty("app.mail.from"))
                .as("sin MAIL_FROM no hay remitente implícito: el arranque debe fallar")
                .isEqualTo("${MAIL_FROM:}");
    }

    @Test
    void missingSenderFailsFastInsteadOfGuessingOne() {
        assertThatThrownBy(() -> new BrevoMailTransport(API_KEY, "", "Gradox2",
                BrevoMailTransport.DEFAULT_API_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_FROM");
    }
}
