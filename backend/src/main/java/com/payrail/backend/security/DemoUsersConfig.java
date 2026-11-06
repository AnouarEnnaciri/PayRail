
package com.payrail.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@Configuration
@Profile("demo")
public class DemoUsersConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(
            PasswordEncoder encoder,
            @Value("${PAYRAIL_DEMO_CLIENT_ALPHA_PASSWORD}") String alphaPassword,
            @Value("${PAYRAIL_DEMO_CLIENT_BETA_PASSWORD}") String betaPassword,
            @Value("${PAYRAIL_DEMO_OPS_PASSWORD}") String opsPassword
    ) {
        return new InMemoryUserDetailsManager(
                User.withUsername("client.alpha@example.test")
                        .password(encoder.encode(alphaPassword))
                        .roles("CLIENT")
                        .build(),

                User.withUsername("client.beta@example.test")
                        .password(encoder.encode(betaPassword))
                        .roles("CLIENT")
                        .build(),

                User.withUsername("ops@example.test")
                        .password(encoder.encode(opsPassword))
                        .roles("OPS")
                        .build()
        );
    }
}
