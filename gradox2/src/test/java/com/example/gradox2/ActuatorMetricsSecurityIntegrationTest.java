package com.example.gradox2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.gradox2.persistence.entities.User;
import com.example.gradox2.persistence.entities.enums.UserRole;
import com.example.gradox2.persistence.repository.DegreeRepository;
import com.example.gradox2.persistence.repository.UserRepository;
import com.example.gradox2.security.InMemoryRateLimiter;
import com.example.gradox2.support.TestDatabaseCleaner;
import com.example.gradox2.support.TestDegrees;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * WAVE2 S1: las métricas de Actuator no son públicas y el health de producción deja de publicar
 * detalles a cualquiera.
 *
 * <p>Las métricas describen el estado interno del servicio (carga, logins fallidos, pool de
 * conexiones). El health sigue abierto porque Render lo usa como sonda, pero sin detalles.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorMetricsSecurityIntegrationTest {

    private static final String PASSWORD = "SecurePass1!";
    private static final AtomicInteger IP_COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DegreeRepository degreeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private InMemoryRateLimiter rateLimiter;

    @Autowired
    private TestDatabaseCleaner databaseCleaner;

    @BeforeEach
    void setUp() {
        rateLimiter.clear();
        databaseCleaner.clean();
    }

    @Test
    void anonymousRequestToMetricsIsRejected() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void studentTokenCannotReadMetrics() throws Exception {
        createUser("studentmetrics", UserRole.USER);
        String token = loginAndGetToken("studentmetrics");

        mockMvc.perform(get("/actuator/metrics").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminTokenCanReadMetrics() throws Exception {
        createUser("adminmetrics", UserRole.ADMIN);
        String token = loginAndGetToken("adminmetrics");

        mockMvc.perform(get("/actuator/metrics").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
    }

    @Test
    void healthStaysPublicForTheRenderProbe() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * El perfil de test no es el de producción: se lee el archivo de propiedades tal como se despliega
     * para fijar que las métricas estén expuestas y los detalles de health ya no sean públicos.
     */
    @Test
    void productionProfileExposesMetricsWithoutPublishingHealthDetails() throws Exception {
        Properties prod = new Properties();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("application-prod.properties")) {
            assertThat(in).as("application-prod.properties debe estar en el classpath").isNotNull();
            prod.load(in);
        }

        assertThat(prod.getProperty("management.endpoints.web.exposure.include"))
                .as("prod expone metrics además de health e info")
                .isEqualTo("health,info,metrics");
        assertThat(prod.getProperty("management.endpoint.health.show-details"))
                .as("los detalles de health ya no se publican sin autorización")
                .isEqualTo("when-authorized");
    }

    private void createUser(String username, UserRole role) {
        userRepository.save(User.builder()
                .username(username)
                .email(username + "@rai.usc.es")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .role(role)
                .degree(TestDegrees.informatica(degreeRepository))
                .enabled(true)
                .build());
    }

    private String loginAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100." + IP_COUNTER.incrementAndGet());
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
