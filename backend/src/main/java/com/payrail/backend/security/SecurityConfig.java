
package com.payrail.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Environment environment
    ) throws Exception {

        boolean publicDemo =
                environment.acceptsProfiles(Profiles.of("public-demo"));

        http
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        "/api/settlement/callbacks"
                ))
                .authorizeHttpRequests(auth -> {

                    auth.requestMatchers(
                            "/actuator/health",
                            "/actuator/info"
                    ).permitAll();

                    // Callback authentication is handled separately by HMAC.
                    auth.requestMatchers(
                            "/api/settlement/callbacks"
                    ).permitAll();

                    if (publicDemo) {

                        // Public visitors cannot access OPS endpoints.
                        auth.requestMatchers(
                                "/api/ops/**",
                                "/actuator/**"
                        ).denyAll();

                    } else {

                        // OPS access is restricted to the OPS role.
                        auth.requestMatchers(
                                "/api/ops/**",
                                "/actuator/**"
                        ).hasRole("OPS");
                    }

                    // Clients can access their own API endpoints.
                    auth.requestMatchers(
                            "/api/client/**"
                    ).hasRole("CLIENT");

                    auth.anyRequest().authenticated();
                })
                .httpBasic(Customizer.withDefaults());

        return http.build();
    }
}
