package com.example.gradox2.service.interfaces;

import com.example.gradox2.presentation.dto.auth.AuthResponse;
import com.example.gradox2.presentation.dto.auth.PasswordResetConfirmRequest;
import com.example.gradox2.presentation.dto.auth.PasswordResetRequest;
import com.example.gradox2.presentation.dto.auth.LoginRequest;
import com.example.gradox2.presentation.dto.auth.RegisterRequest;
import com.example.gradox2.presentation.dto.auth.TokenRequest;

public interface IAuthService {
    AuthResponse login(LoginRequest request);
    AuthResponse register(RegisterRequest request);
    AuthResponse refreshToken(TokenRequest request);
    void logout(TokenRequest request);
    void requestPasswordReset(PasswordResetRequest request);
    void resetPassword(PasswordResetConfirmRequest request);
    /** Resultado de intentar confirmar una cuenta con un token de verificación. */
    enum VerificationOutcome { VERIFIED, ALREADY_VERIFIED, INVALID }

    VerificationOutcome confirmVerification(String token);

    /** Solo lectura: dice si el token serviría, sin habilitar a nadie. */
    boolean isVerificationTokenUsable(String token);

    void resendVerification(String email);
}
