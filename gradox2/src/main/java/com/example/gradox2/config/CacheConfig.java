package com.example.gradox2.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * WAVE2 S3: caché del catálogo acotada.
 *
 * <p>El {@code ConcurrentMapCache} por defecto no tiene techo ni expiración: cada clave nueva quedaba
 * viva hasta el reinicio y un cambio de catálogo no se veía nunca. Caffeine acota el tamaño y expira
 * por escritura.
 *
 * <p>El catálogo (grados, cursos, asignaturas) solo se escribe por migraciones Flyway, no hay caminos
 * de escritura en runtime, así que no hay {@code @CacheEvict} que colgar. Si en el futuro aparece un
 * CRUD administrativo del catálogo, ese endpoint debe evacuar estas cachés.
 */
@Configuration
public class CacheConfig {

    public static final String DEGREES = "degrees";
    public static final String COURSES = "courses";
    public static final String SUBJECTS = "subjects";

    private final long ttlSeconds;
    private final long maxSize;

    public CacheConfig(@Value("${app.cache.ttl-seconds:600}") long ttlSeconds,
            @Value("${app.cache.max-size:1000}") long maxSize) {
        this.ttlSeconds = ttlSeconds;
        this.maxSize = maxSize;
    }

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(DEGREES, COURSES, SUBJECTS);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .recordStats());
        return manager;
    }
}
