package com.example.gradox2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.example.gradox2.utils.EmailService;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = EmailServiceAsyncTest.Config.class)
class EmailServiceAsyncTest {

    @Configuration
    @EnableAsync
    static class Config {
        @Bean(name = "taskExecutor")
        Executor taskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(2);
            executor.setMaxPoolSize(4);
            executor.setThreadNamePrefix("verify-async-");
            executor.initialize();
            return executor;
        }

        @Bean
        JavaMailSender mailSender() {
            return mock(JavaMailSender.class);
        }

        @Bean
        EmailService emailService(JavaMailSender mailSender,
                @org.springframework.beans.factory.annotation.Qualifier("taskExecutor") Executor taskExecutor) {
            return new EmailService(mailSender, taskExecutor);
        }
    }

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private EmailService emailService;

    @Test
    void sendEmailRunsOnBackgroundThread() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        AtomicReference<Thread> senderThread = new AtomicReference<>();
        doAnswer(inv -> {
            senderThread.set(Thread.currentThread());
            sent.countDown();
            return null;
        }).when(mailSender).send(any(SimpleMailMessage.class));

        Thread caller = Thread.currentThread();
        emailService.sendEmail("to@example.com", "Subject", "Body");

        assertTrue(sent.await(3, TimeUnit.SECONDS), "el envío debió ejecutarse en background");
        assertNotEquals(caller, senderThread.get(),
                "el envío debe correr en un hilo distinto al llamador (executor de correo)");
    }

    @Test
    void sendEmailFailureDoesNotPropagateToCaller() {
        doThrow(new IllegalStateException("SMTP caído simulada"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        // WAVE2 S2: el fallo del SMTP nunca debe escapar al llamador (registro, reset).
        assertDoesNotThrow(() -> emailService.sendEmail("to@example.com", "Subject", "Body"));
    }

    @Test
    void sendEmailRetriesOnceBeforeGivingUp() {
        JavaMailSender flaky = mock(JavaMailSender.class);
        doThrow(new IllegalStateException("primer intento falla"))
                .doNothing()
                .when(flaky).send(any(SimpleMailMessage.class));

        // Executor síncrono: el reintento ocurre en el mismo hilo y el test es determinista.
        new EmailService(flaky, Runnable::run).sendEmail("to@example.com", "Subject", "Body");

        verify(flaky, times(2)).send(any(SimpleMailMessage.class));
    }

    @Test
    void rejectedTaskDoesNotPropagateToCaller() {
        JavaMailSender mailSenderMock = mock(JavaMailSender.class);
        Executor fullPool = task -> {
            throw new java.util.concurrent.RejectedExecutionException("pool lleno");
        };

        // WAVE2 S2: con AbortPolicy el pool rechaza; el alta de usuario no puede fallar por eso.
        assertDoesNotThrow(() -> new EmailService(mailSenderMock, fullPool)
                .sendEmail("to@example.com", "Subject", "Body"));
        verify(mailSenderMock, times(0)).send(any(SimpleMailMessage.class));
    }
}
