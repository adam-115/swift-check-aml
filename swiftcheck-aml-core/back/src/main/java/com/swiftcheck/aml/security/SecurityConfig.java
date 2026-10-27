package com.swiftcheck.aml.security;

import com.swiftcheck.aml.controllers.SpaForwardController;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final KeycloakJwtAuthenticationConverter jwtAuthenticationConverter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Fichiers statiques et assets frontend
                .requestMatchers(
                    "/",
                    "/index.html",
                    "/*.js",
                    "/*.css",
                    "/*.ico",
                    "/*.png",
                    "/*.svg",
                    "/assets/**",
                    "/images/**",
                    "/reports/**",
                    "/error",
                    "/actuator/**"
                ).permitAll()
                // Routes Angular : la page vide de l'application (index.html), pour que le rafraîchissement
                // du navigateur fonctionne ; les données passent par /api/**, protégé ci-dessous
                .requestMatchers(HttpMethod.GET, SpaForwardController.SPA_ROUTES).permitAll()
                // Configuration système réservée à l'administrateur
                .requestMatchers("/api/config/**").hasRole("ADMIN")
                // Référentiel PEP (pays, catégories, fonctions) : lecture pour tous, modification par l'administrateur
                .requestMatchers(HttpMethod.POST, "/api/pep/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/pep/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/pep/**").hasRole("ADMIN")
                // Endpoints accessibles par les testeurs et administrateurs
                .requestMatchers("/api/**").hasAnyRole("ADMIN", "TESTER")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
            );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With", "Accept", "Origin"));
        configuration.setExposedHeaders(List.of("Authorization", "Content-Disposition"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
