package com.muse.payments.identity.web;

import com.muse.payments.identity.application.AuthRateLimitPort;
import com.muse.payments.identity.application.AuthTokensResponse;
import com.muse.payments.identity.application.LoginCommand;
import com.muse.payments.identity.application.LoginCommandHandler;
import com.muse.payments.identity.application.LoginRequest;
import com.muse.payments.identity.application.LogoutCommand;
import com.muse.payments.identity.application.LogoutCommandHandler;
import com.muse.payments.identity.application.RefreshTokenCommand;
import com.muse.payments.identity.application.RefreshTokenCommandHandler;
import com.muse.payments.identity.application.RefreshTokenRequest;
import com.muse.payments.identity.application.RegisterCommand;
import com.muse.payments.identity.application.RegisterCommandHandler;
import com.muse.payments.identity.application.RegisterRequest;
import com.muse.payments.identity.application.UserResponse;
import com.muse.payments.shared.domain.RateLimitExceededException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "User registration, login, refresh token rotation, and session logout")
public class AuthController {

    private final RegisterCommandHandler registerHandler;
    private final LoginCommandHandler loginHandler;
    private final RefreshTokenCommandHandler refreshTokenHandler;
    private final LogoutCommandHandler logoutHandler;
    private final AuthRateLimitPort rateLimiter;

    public AuthController(
            RegisterCommandHandler registerHandler,
            LoginCommandHandler loginHandler,
            RefreshTokenCommandHandler refreshTokenHandler,
            LogoutCommandHandler logoutHandler,
            AuthRateLimitPort rateLimiter
    ) {
        this.registerHandler = Objects.requireNonNull(registerHandler);
        this.loginHandler = Objects.requireNonNull(loginHandler);
        this.refreshTokenHandler = Objects.requireNonNull(refreshTokenHandler);
        this.logoutHandler = Objects.requireNonNull(logoutHandler);
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user account")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        checkIpRateLimit(httpRequest);

        RegisterCommand command = new RegisterCommand(request.email(), request.password(), request.displayName());
        UserResponse response = registerHandler.handle(command);
        URI location = URI.create("/api/v1/users/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate user and issue access and refresh tokens")
    public ResponseEntity<AuthTokensResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        checkIpRateLimit(httpRequest);
        if (!rateLimiter.tryAcquireAccount(request.email())) {
            throw new RateLimitExceededException("Too many authentication attempts. Please retry later.", 60);
        }

        LoginCommand command = new LoginCommand(request.email(), request.password());
        AuthTokensResponse response = loginHandler.handle(command);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate refresh token and issue new access token")
    public ResponseEntity<AuthTokensResponse> refresh(@Valid @RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest) {
        checkIpRateLimit(httpRequest);

        RefreshTokenCommand command = new RefreshTokenCommand(request.refreshToken());
        AuthTokensResponse response = refreshTokenHandler.handle(command);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Invalidate refresh token family and logout")
    public void logout(@RequestBody(required = false) RefreshTokenRequest request) {
        String token = request != null ? request.refreshToken() : null;
        logoutHandler.handle(new LogoutCommand(token));
    }

    private void checkIpRateLimit(HttpServletRequest request) {
        String clientIp = request.getRemoteAddr();
        if (clientIp == null || clientIp.isBlank()) {
            clientIp = "anonymous";
        }
        if (!rateLimiter.tryAcquire(clientIp)) {
            throw new RateLimitExceededException("Too many authentication attempts. Please retry later.", 60);
        }
    }
}
