package com.javaproject.security;

import javax.sql.DataSource;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.jdbc.JdbcDaoImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * User store used for login and by HomeController for registration.
     * Replaces the old configure(AuthenticationManagerBuilder) method.
     */
    @Bean
    @DependsOnDatabaseInitialization
    public JdbcUserDetailsManager jdbcUserDetailsManager(DataSource dataSource,
            BCryptPasswordEncoder passwordEncoder) {
        // 1. Create Spring Security's standard users/authorities tables
        // (same script the old .withDefaultSchema() ran)
        new ResourceDatabasePopulator(
                new ClassPathResource(JdbcDaoImpl.DEFAULT_USER_SCHEMA_DDL_LOCATION))
                .execute(dataSource);

        JdbcUserDetailsManager manager = new JdbcUserDetailsManager(dataSource);

        // 2. Demo users (same as the old .withUser(...) calls)
        if (!manager.userExists("bugs")) {
            manager.createUser(User.withUsername("bugs")
                    .password(passwordEncoder.encode("bunny"))
                    .roles("USER")
                    .build());
        }
        if (!manager.userExists("daffy")) {
            manager.createUser(User.withUsername("daffy")
                    .password(passwordEncoder.encode("duck"))
                    .roles("USER", "MANAGER")
                    .build());
        }
        return manager;
    }

    /**
     * Web security rules. Replaces the old configure(HttpSecurity) method.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            LoggingAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/user/**").hasAnyRole("USER", "MANAGER")
                        .requestMatchers("/secured/**").hasAnyRole("USER", "MANAGER")
                        .requestMatchers("/manager/**").hasRole("MANAGER")
                        .requestMatchers("/", "/**").permitAll())
                .formLogin(login -> login
                        .loginPage("/login")
                        .defaultSuccessUrl("/secured"))
                .logout(logout -> logout
                        .invalidateHttpSession(true)
                        .clearAuthentication(true))
                .exceptionHandling(ex -> ex
                        .accessDeniedHandler(accessDeniedHandler))
                // FINDING: CSRF disabled (kept as-is; see KAN-5)
                .csrf(csrf -> csrf.disable())
                // FINDING: frame options disabled for H2 console (kept as-is; see KAN-5)
                .headers(headers -> headers.frameOptions(frame -> frame.disable()));

        return http.build();
    }
}