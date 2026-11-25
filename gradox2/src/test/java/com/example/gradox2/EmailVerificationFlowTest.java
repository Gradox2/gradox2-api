package com.example.gradox2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.gradox2.persistence.entities.User;
import com.example.gradox2.persistence.entities.VerificationToken;
import com.example.gradox2.persistence.entities.enums.UserRole;
import com.example.gradox2.persistence.repository.DegreeRepository;
import com.example.gradox2.persistence.repository.UserRepository;
import com.example.gradox2.persistence.repository.VerificationTokenRepository;
import com.example.gradox2.security.InMemoryRateLimiter;
import com.example.gradox2.support.TestDatabaseCleaner;
import com.example.gradox2.support.TestDegrees;
import com.example.gradox2.utils.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verificación de cuenta: el enlace del correo no puede verificar por sí solo.
 *
 * <p>Un escáner o el tracking del proveedor abren el enlace antes que la persona. Con la verificación
 * pasiva anterior, ese GET habilitaba la cuenta y borraba el token: la persona quedaba verificada sin
 * haber verificado nada y su clic encontraba "Token inválido o expirado".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailVerificationFlowTest {

    private static final String PASSWORD = "SecurePass1!";
    private static final AtomicInteger IP_COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationTokenRepository verificationTokenRepository;

    @Autowired
    private DegreeRepository degreeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private InMemoryRateLimiter rateLimiter;

    @Autowired
    private TestDatabaseCleaner databaseCleaner;

    @MockitoBean
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        rateLimiter.clear();
        databaseCleaner.clean();
    }

    @Test
    void openingTheLinkDoesNotVerifyTheAccount() throws Exception {
        User user = createUser("prefetchuser", false);
        String token = tokenFor(user);

        mockMvc.perform(get("/api/auth/verify").param("token", token)
                        .with(remoteAddr()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/auth/verify/confirm")));

        assertThat(userRepository.findByUsername("prefetchuser").orElseThrow().isEnabled())
                .as("el GET no puede habilitar la cuenta: un prefetch no es una persona")
                .isFalse();
        assertThat(verificationTokenRepository.findByToken(token))
                .as("el token sigue disponible para el clic real")
                .isPresent();
    }

    @Test
    void confirmingEnablesTheAccountAndKeepsTheToken() throws Exception {
        User user = createUser("confirmuser", false);
        String token = tokenFor(user);

        mockMvc.perform(post("/api/auth/verify/confirm").param("token", token).with(remoteAddr()))
                .andExpect(status().isOk())
                .andExpect(content().string("Cuenta verificada con éxito"));

        assertThat(userRepository.findByUsername("confirmuser").orElseThrow().isEnabled()).isTrue();
        assertThat(verificationTokenRepository.findByToken(token))
                .as("el token no se borra al usarlo: así el doble clic no rompe nada")
                .isPresent();
    }

    @Test
    void confirmingTwiceIsIdempotent() throws Exception {
        User user = createUser("twiceuser", false);
        String token = tokenFor(user);

        mockMvc.perform(post("/api/auth/verify/confirm").param("token", token).with(remoteAddr()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/verify/confirm").param("token", token).with(remoteAddr()))
                .andExpect(status().isOk())
                .andExpect(content().string("La cuenta ya estaba verificada"));
    }

    @Test
    void expiredTokenIsRejectedWithoutEnabling() throws Exception {
        User user = createUser("expireduser", false);
        VerificationToken expired = new VerificationToken(UUID.randomUUID().toString(), user);
        expired.setExpiryDate(LocalDateTime.now().minusMinutes(1));
        verificationTokenRepository.save(expired);

        mockMvc.perform(get("/api/auth/verify").param("token", expired.getToken()).with(remoteAddr()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/verify/confirm").param("token", expired.getToken()).with(remoteAddr()))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findByUsername("expireduser").orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void unknownTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/auth/verify").param("token", "no-existe").with(remoteAddr()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/verify/confirm").param("token", "no-existe").with(remoteAddr()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resendReplacesTheSingleTokenAndSendsOneMail() throws Exception {
        User user = createUser("resenduser", false);
        String oldToken = tokenFor(user);

        mockMvc.perform(post("/api/auth/verify/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", user.getEmail())))
                        .with(remoteAddr()))
                .andExpect(status().isOk());

        verify(emailService, times(1)).sendEmail(eq(user.getEmail()), anyString(), anyString());
        assertThat(verificationTokenRepository.findAll())
                .as("user_id es única: el reenvío actualiza la fila, no crea otra")
                .hasSize(1);
        String newToken = verificationTokenRepository.findByUser(user).orElseThrow().getToken();
        assertThat(newToken).isNotEqualTo(oldToken);
    }

    @Test
    void resendForAnAlreadyVerifiedAccountSendsNothing() throws Exception {
        User user = createUser("verifieduser", true);

        mockMvc.perform(post("/api/auth/verify/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", user.getEmail())))
                        .with(remoteAddr()))
                .andExpect(status().isOk());

        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void resendForAnUnknownEmailAnswersTheSame() throws Exception {
        mockMvc.perform(post("/api/auth/verify/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "nadie@rai.usc.es")))
                        .with(remoteAddr()))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        "Si la cuenta existe y está pendiente de verificación, te enviamos un correo nuevo."));

        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void resendIsRateLimitedPerIp() throws Exception {
        User user = createUser("ratelimituser", false);
        String body = objectMapper.writeValueAsString(Map.of("email", user.getEmail()));

        // Misma IP en los cuatro: el bucket es por IP, no por petición.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/verify/resend")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .with(fixedIp("203.0.113.77")))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/auth/verify/resend")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(fixedIp("203.0.113.77")))
                .andExpect(status().isTooManyRequests());
    }

    private User createUser(String username, boolean enabled) {
        return userRepository.save(User.builder()
                .username(username)
                .email(username + "@rai.usc.es")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .role(UserRole.USER)
                .degree(TestDegrees.informatica(degreeRepository))
                .enabled(enabled)
                .build());
    }

    private String tokenFor(User user) {
        VerificationToken token = new VerificationToken(UUID.randomUUID().toString(), user);
        verificationTokenRepository.save(token);
        return token.getToken();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor fixedIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteAddr() {
        return request -> {
            request.setRemoteAddr("198.51.100." + IP_COUNTER.incrementAndGet());
            return request;
        };
    }
}
