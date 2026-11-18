package com.x.backendvoip.infrastructure;

import com.x.backendvoip.auth.AuthProperties;
import com.x.backendvoip.auth.SessionTokenValidator;
import com.x.backendvoip.common.ApiErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.*;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.*;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;

@Configuration
@EnableConfigurationProperties({AuthProperties.class, CorsProperties.class})
public class SecurityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    SecretKey jwtSecretKey(AuthProperties properties) {
        byte[] key;
        if (properties.jwtSecret() == null || properties.jwtSecret().isBlank()) {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
            log.warn("JWT_SECRET is unset: using a random development key; tokens become invalid after restart.");
        } else {
            key = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
            if (key.length < 32) throw new IllegalArgumentException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSecretKey).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, AuthProperties properties, SessionTokenValidator sessions) {
        var decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), sessions));
        return decoder;
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper mapper) {
        return (request, response, error) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"alohub\"");
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ApiErrors.problem(HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED", "A valid active bearer token is required.", request.getRequestURI()));
        };
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler(ObjectMapper mapper) {
        return (request, response, error) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ApiErrors.problem(HttpStatus.FORBIDDEN,
                    "FORBIDDEN", "Access is denied.", request.getRequestURI()));
        };
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, AuthenticationEntryPoint entryPoint,
                                    AccessDeniedHandler deniedHandler) throws Exception {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        return http
                .cors(Customizer.withDefaults())
                // Authentication is exclusively the explicit Authorization header; no auth cookies.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS", "PUT"));
        config.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
