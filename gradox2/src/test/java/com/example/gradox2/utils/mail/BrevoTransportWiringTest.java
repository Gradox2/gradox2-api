package com.example.gradox2.utils.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * El transporte de producción tiene que poder construirse por el contenedor, no solo a mano en un test.
 *
 * <p>El resto de la suite corre con `app.mail.transport=smtp`, así que el camino `brevo` no se instanciaba
 * nunca dentro de Spring: un fallo de wiring (dos constructores, ninguno anotado) pasaba los tests y
 * rompía el arranque en producción con `No default constructor found`.
 */
@SpringBootTest(properties = {
        "app.mail.transport=brevo",
        "app.mail.api-key=test-key",
        "app.mail.from=no-reply@example.com"
})
@ActiveProfiles("test")
class BrevoTransportWiringTest {

    @Autowired
    private MailTransport mailTransport;

    @Test
    void contextStartsWithTheBrevoTransportSelected() {
        assertThat(mailTransport)
                .as("con app.mail.transport=brevo el contenedor debe construir BrevoMailTransport")
                .isInstanceOf(BrevoMailTransport.class);
    }
}
