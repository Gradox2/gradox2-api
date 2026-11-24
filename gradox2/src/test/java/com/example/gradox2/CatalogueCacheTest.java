package com.example.gradox2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.example.gradox2.persistence.repository.DegreeRepository;
import com.example.gradox2.service.interfaces.IDegreeService;
import com.github.benmanes.caffeine.cache.Policy;

/**
 * WAVE2 S3: la caché del catálogo es Caffeine, con techo y expiración, en lugar del mapa sin límite
 * del {@code ConcurrentMapCache} por defecto.
 *
 * <p>El catálogo solo se escribe por migraciones Flyway: no hay camino de escritura en runtime, así que
 * lo que se fija acá es que la caché sirva de verdad y que no pueda crecer sin techo.
 */
@SpringBootTest
@ActiveProfiles("test")
class CatalogueCacheTest {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private IDegreeService degreeService;

    @MockitoSpyBean
    private DegreeRepository degreeRepository;

    @BeforeEach
    void resetCache() {
        cacheManager.getCache("degrees").clear();
        clearInvocations(degreeRepository);
    }

    @Test
    void listingDegreesIsServedFromCache() {
        degreeService.listAll();
        degreeService.listAll();

        verify(degreeRepository, times(1)).findAllWithCoursesOrderedByCode();
    }

    @Test
    void clearingCacheForcesAFreshRead() {
        degreeService.listAll();
        cacheManager.getCache("degrees").clear();
        degreeService.listAll();

        verify(degreeRepository, times(2)).findAllWithCoursesOrderedByCode();
    }

    @Test
    void cacheIsBoundedAndExpiresByWrite() {
        Cache cache = cacheManager.getCache("degrees");

        assertThat(cache.getNativeCache())
                .as("el catálogo debe usar Caffeine, no el ConcurrentMapCache sin límite")
                .isInstanceOf(com.github.benmanes.caffeine.cache.Cache.class);

        @SuppressWarnings("unchecked")
        com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache =
                (com.github.benmanes.caffeine.cache.Cache<Object, Object>) cache.getNativeCache();

        assertThat(nativeCache.policy().eviction())
                .as("la caché debe tener techo de tamaño")
                .isPresent();
        assertThat(nativeCache.policy().eviction().orElseThrow().getMaximum())
                .as("app.cache.max-size por defecto")
                .isEqualTo(1000L);

        Policy.FixedExpiration<Object, Object> expiration = ((Policy<Object, Object>) nativeCache.policy())
                .expireAfterWrite().orElseThrow();
        assertThat(expiration.getExpiresAfter())
                .as("app.cache.ttl-seconds por defecto: 10 minutos")
                .isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void allCatalogueCachesUseTheSameBoundedManager() {
        for (String name : new String[] { "degrees", "courses", "subjects" }) {
            assertThat(cacheManager.getCache(name))
                    .as("la caché %s debe existir en el CacheManager acotado", name)
                    .isNotNull();
            assertThat(cacheManager.getCache(name).getNativeCache())
                    .as("la caché %s también debe ser Caffeine", name)
                    .isInstanceOf(com.github.benmanes.caffeine.cache.Cache.class);
        }
    }
}
