package com.example.gradox2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.gradox2.persistence.entities.User;
import com.example.gradox2.persistence.repository.DegreeRepository;
import com.example.gradox2.persistence.repository.UserRepository;
import com.example.gradox2.security.InMemoryRateLimiter;
import com.example.gradox2.support.TestDatabaseCleaner;
import com.example.gradox2.support.TestDegrees;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * WAVE2 S2: «el registro sigue, el mail se reintenta/loguea».
 *
 * <p>Con el SMTP caído, el alta de usuario tiene que completarse igual (queda pendiente de
 * verificación) y el fallo se reintenta una vez antes de registrarse.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MailFailureToleranceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DegreeRepository degreeRepository;

    @Autowired
    private InMemoryRateLimiter rateLimiter;

    @Autowired
    private TestDatabaseCleaner databaseCleaner;

    @MockitoBean
    private JavaMailSender mailSender;

    @BeforeEach
    void setUp() {
        rateLimiter.clear();
        databaseCleaner.clean();
        doThrow(new MailSendException("SMTP caído simulado"))
                .when(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void registrationSucceedsWhenSmtpIsDown() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "smtpdownuser",
                                "email", "smtpdown@rai.usc.es",
                                "password", "SecurePass1!",
                                "degreeId", TestDegrees.informatica(degreeRepository).getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("smtpdownuser"));

        User created = userRepository.findByUsername("smtpdownuser").orElseThrow();
        assertThat(created.isEnabled())
                .as("sin mail no hay verificación: la cuenta queda pendiente, pero creada")
                .isFalse();

        // Un intento fallido + el reintento de WAVE2 S2.
        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
    }
}
