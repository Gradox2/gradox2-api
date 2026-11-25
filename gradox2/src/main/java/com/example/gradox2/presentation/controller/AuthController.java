package com.example.gradox2.presentation.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import com.example.gradox2.presentation.dto.auth.AuthResponse;
import com.example.gradox2.presentation.dto.auth.PasswordResetConfirmRequest;
import com.example.gradox2.presentation.dto.auth.PasswordResetRequest;
import com.example.gradox2.presentation.dto.auth.LoginRequest;
import com.example.gradox2.presentation.dto.auth.RegisterRequest;
import com.example.gradox2.presentation.dto.auth.TokenRequest;
import com.example.gradox2.service.interfaces.IAuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/auth")
@Validated
@Tag(name = "Autenticación", description = "Registro, login, verificación, refresh, logout y recuperación de contraseña")
public class AuthController {

    private final IAuthService authService;

    public AuthController(IAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @Operation(summary = "Iniciar sesión", description = "Autentica al usuario con email y contraseña, devuelve JWT y refresh token")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Login exitoso"),
        @ApiResponse(responseCode = "401", description = "Credenciales inválidas", content = @Content),
        @ApiResponse(responseCode = "429", description = "Demasiados intentos, rate limit excedido", content = @Content)
    })
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/register")
    @Operation(summary = "Registrarse", description = "Crea una cuenta pendiente de verificación con correo institucional @rai.usc.es")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Registro exitoso, se envía código de verificación"),
        @ApiResponse(responseCode = "409", description = "El usuario o email ya existe", content = @Content)
    })
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/token/refresh")
    @Operation(summary = "Renovar token", description = "Obtiene un nuevo JWT usando el refresh token")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Token renovado"),
        @ApiResponse(responseCode = "401", description = "Refresh token inválido o expirado", content = @Content)
    })
    public ResponseEntity<AuthResponse> refreshToken(@Valid @RequestBody TokenRequest request) {
        return ResponseEntity.ok(authService.refreshToken(request));
    }

    @PostMapping("/logout")
    @Operation(summary = "Cerrar sesión", description = "Revoca el refresh token para cerrar la sesión")
    @ApiResponse(responseCode = "204", description = "Sesión cerrada")
    public ResponseEntity<Void> logout(@Valid @RequestBody TokenRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password/reset-request")
    @Operation(summary = "Solicitar reset de contraseña", description = "Envía un enlace de restablecimiento al email si existe")
    @ApiResponse(responseCode = "200", description = "Si el correo existe, se envía el enlace")
    public ResponseEntity<String> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        authService.requestPasswordReset(request);
        return ResponseEntity.ok("Si el correo existe, se enviará un enlace de restablecimiento");
    }

    @PostMapping("/password/reset")
    @Operation(summary = "Confirmar reset de contraseña", description = "Cambia la contraseña usando el token recibido por email")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Contraseña actualizada"),
        @ApiResponse(responseCode = "400", description = "Token inválido o expirado", content = @Content)
    })
    public ResponseEntity<String> resetPassword(@Valid @RequestBody PasswordResetConfirmRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok("Contraseña actualizada correctamente");
    }

    private static final java.util.regex.Pattern TOKEN_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private static final String RESEND_MESSAGE =
            "Si la cuenta existe y está pendiente de verificación, te enviamos un correo nuevo.";

    @GetMapping("/verify")
    @Operation(summary = "Confirmar la cuenta (paso 1)",
            description = "No habilita la cuenta: devuelve una página con un botón que confirma mediante POST. "
                    + "Así un escáner de correo o un prefetch del proveedor no verifica a nadie.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Página de confirmación"),
        @ApiResponse(responseCode = "400", description = "Token inválido o expirado", content = @Content)
    })
    public ResponseEntity<String> verifyPage(
            @Parameter(description = "Token de verificación") @RequestParam("token") @NotBlank String token) {
        if (!TOKEN_PATTERN.matcher(token).matches() || !authService.isVerificationTokenUsable(token)) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_HTML)
                    .header("Cache-Control", "no-store")
                    .body(page("No pudimos verificar tu cuenta",
                            "<p>El enlace no es válido o ya venció (duran 24 horas).</p>"
                                    + "<p>Pedí uno nuevo con la opción «reenviar verificación».</p>",
                            null, null));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Cache-Control", "no-store")
                .body(page("Verificá tu cuenta",
                        "<p>Confirmá que querés activar tu cuenta.</p>",
                        "/api/auth/verify/confirm", token));
    }

    @PostMapping("/verify/confirm")
    @Operation(summary = "Confirmar la cuenta (paso 2)",
            description = "Habilita la cuenta. Es idempotente: repetirlo responde que ya estaba verificada.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cuenta verificada o ya verificada"),
        @ApiResponse(responseCode = "400", description = "Token inválido o expirado", content = @Content)
    })
    public ResponseEntity<String> confirmAccount(
            @Parameter(description = "Token de verificación") @RequestParam("token") @NotBlank String token) {
        return switch (authService.confirmVerification(token)) {
            case VERIFIED -> ResponseEntity.ok("Cuenta verificada con éxito");
            case ALREADY_VERIFIED -> ResponseEntity.ok("La cuenta ya estaba verificada");
            case INVALID -> ResponseEntity.badRequest().body("Token inválido o expirado");
        };
    }

    @PostMapping("/verify/resend")
    @Operation(summary = "Reenviar el correo de verificación",
            description = "Responde siempre lo mismo para no revelar si la cuenta existe. Limitado a 3 por hora por IP.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Respuesta genérica"),
        @ApiResponse(responseCode = "429", description = "Demasiados reenvíos", content = @Content)
    })
    public ResponseEntity<String> resendVerification(
            @RequestBody(required = false) java.util.Map<String, String> body) {
        String email = body == null ? null : body.get("email");
        if (email != null && !email.isBlank()) {
            authService.resendVerification(email.trim());
        }
        return ResponseEntity.ok(RESEND_MESSAGE);
    }

    private String page(String title, String body, String action, String token) {
        StringBuilder form = new StringBuilder();
        if (action != null && token != null) {
            form.append("<form method=\"post\" action=\"").append(action).append("\">")
                    .append("<input type=\"hidden\" name=\"token\" value=\"").append(token).append("\">")
                    .append("<button type=\"submit\">Verificar mi cuenta</button>")
                    .append("</form>");
        }
        return "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex,nofollow\">"
                + "<title>Gradox2</title></head>"
                + "<body style=\"font-family:system-ui,sans-serif;max-width:32rem;margin:3rem auto;padding:0 1rem\">"
                + "<h1 style=\"font-size:1.25rem\">" + title + "</h1>"
                + body + form
                + "</body></html>";
    }

}
