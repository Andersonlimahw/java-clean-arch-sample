package com.muse.payments.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.shared.web.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] DOCS_AND_METRICS_PATHS = {
            "/actuator/prometheus",
            "/actuator/metrics",
            "/actuator/metrics/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${app.security.expose-public-docs-and-metrics:true}") boolean exposePublicDocsAndMetrics
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                            "/error",
                            "/api/v1/auth/**",
                            "/actuator/health/**",
                            "/actuator/info"
                    ).permitAll();
                    if (exposePublicDocsAndMetrics) {
                        auth.requestMatchers(DOCS_AND_METRICS_PATHS).permitAll();
                    } else {
                        auth.requestMatchers(DOCS_AND_METRICS_PATHS).hasRole("ADMIN");
                    }
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/webhooks/**").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/payments/*/review").hasAnyRole("ADMIN", "FRAUD_REVIEWER")
                            .requestMatchers(HttpMethod.GET, "/api/v1/payments/under-review").hasAnyRole("ADMIN", "FRAUD_REVIEWER")
                            .requestMatchers(HttpMethod.GET, "/api/v1/payments/*/history").hasAnyRole("USER", "ADMIN", "FRAUD_REVIEWER")
                            .requestMatchers(HttpMethod.GET, "/api/v1/payments/*").hasAnyRole("USER", "ADMIN", "FRAUD_REVIEWER")
                            .requestMatchers("/api/v1/payments", "/api/v1/payments/**").hasAnyRole("USER", "ADMIN")
                            .anyRequest().authenticated();
                })
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problemAuthenticationEntryPoint())
                        .accessDeniedHandler(problemAccessDeniedHandler())
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problemAuthenticationEntryPoint())
                        .accessDeniedHandler(problemAccessDeniedHandler())
                );

        return http.build();
    }

    /**
     * BCrypt hashed off the virtual-thread carriers. {@code app.security.bcrypt-cost} defaults to 10
     * (override via {@code BCRYPT_COST}). {@code app.security.password-hashing-threads} of 0 means
     * {@code availableProcessors() - 1}, at least one.
     */
    @Bean(destroyMethod = "close")
    public PasswordEncoder passwordEncoder(
            @Value("${app.security.bcrypt-cost:10}") int bcryptCost,
            @Value("${app.security.password-hashing-threads:0}") int hashingThreads) {
        if (bcryptCost < 4 || bcryptCost > 31) {
            throw new IllegalArgumentException("app.security.bcrypt-cost must be between 4 and 31, got: " + bcryptCost);
        }
        int threads = hashingThreads > 0 ? hashingThreads : OffloadedPasswordEncoder.defaultThreads();
        return new OffloadedPasswordEncoder(new BCryptPasswordEncoder(bcryptCost), threads);
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter defaultAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        Converter<Jwt, Collection<GrantedAuthority>> customConverter = jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(defaultAuthoritiesConverter.convert(jwt));
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                for (String role : roles) {
                    String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
                    authorities.add(new SimpleGrantedAuthority(authority));
                }
            }
            return authorities;
        };

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(customConverter);
        return converter;
    }

    @Bean
    public AuthenticationEntryPoint problemAuthenticationEntryPoint() {
        return (request, response, authException) -> writeProblemDetail(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                "Full authentication is required to access this resource."
        );
    }

    @Bean
    public AccessDeniedHandler problemAccessDeniedHandler() {
        return (request, response, accessDeniedException) -> writeProblemDetail(
                request,
                response,
                HttpStatus.FORBIDDEN,
                "Forbidden",
                "Access is denied."
        );
    }

    private void writeProblemDetail(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String title,
            String detail
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        ProblemDetail problem = GlobalExceptionHandler.createProblemDetail(status, title, detail, request);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
