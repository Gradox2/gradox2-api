package com.example.gradox2.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.example.gradox2.service.implementation.S3FileUrlSigner;
import com.example.gradox2.service.interfaces.FileUrlSigner;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
public class S3Config {

    @Value("${s3.endpoint}")
    private String endpoint;

    @Value("${s3.public-endpoint}")
    private String publicEndpoint;

    @Value("${s3.access-key}")
    private String accessKey;

    @Value("${s3.secret-key}")
    private String secretKey;

    @Value("${s3.region:auto}")
    private String region;

    @Value("${s3.bucket-name}")
    private String bucketName;

    @Value("${s3.presign.ttl-seconds}")
    private long presignTtlSeconds;

    /** WAVE2 S2: sin esto, un R2/MinIO que no responde deja el request esperando al SDK. */
    @Value("${s3.timeouts.api-call-ms:10000}")
    private long apiCallTimeoutMs;

    @Value("${s3.timeouts.api-call-attempt-ms:5000}")
    private long apiCallAttemptTimeoutMs;

    @Bean
    @Profile("!test")
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofMillis(apiCallTimeoutMs))
                        .apiCallAttemptTimeout(Duration.ofMillis(apiCallAttemptTimeoutMs))
                        .build())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    @Bean
    @Profile("!test")
    public FileUrlSigner fileUrlSigner() {
        return new S3FileUrlSigner(publicEndpoint, accessKey, secretKey, region,
                bucketName, Duration.ofSeconds(presignTtlSeconds));
    }
}
