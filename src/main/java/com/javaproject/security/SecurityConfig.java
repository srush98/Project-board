package com.javaproject.security;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;

@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    private LoggingAccessDeniedHandler accessDeniedHandler;

    @Autowired
    public void setAccessDeniedHandler(LoggingAccessDeniedHandler accessDeniedHandler) {
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Autowired
    @Lazy
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private DataSource dataSource;

    /**
     * Creates a bean of type JdbcUserDetailsManager that will be used in
     * HomeController
     * 
     * @return an instance configured to use our datasource
     * @throws Exception
     */
    @Bean
    @DependsOnDatabaseInitialization
    public JdbcUserDetailsManager jdbcUserDetailsManager() throws Exception {
        // provides crud operations for users
        JdbcUserDetailsManager jdbcUserDetailsManager = new JdbcUserDetailsManager();

        // Link up with our datasource
        jdbcUserDetailsManager.setDataSource(dataSource);
        return jdbcUserDetailsManager;
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/user/**").hasAnyRole("USER", "MANAGER") // sets up authorization
                .requestMatchers("/secured/**").hasAnyRole("USER", "MANAGER")
                .requestMatchers("/manager/**").hasRole("MANAGER")
                .requestMatchers("/h2-console/**").permitAll()
                .requestMatchers("/", "/**").permitAll()) // allows us to chain
                .formLogin(login -> login.loginPage("/login")
                        .defaultSuccessUrl("/secured"))
                .logout(logout -> logout.invalidateHttpSession(true)
                        .clearAuthentication(true))
                .exceptionHandling(handling -> handling
                        .accessDeniedHandler(accessDeniedHandler));

        http.csrf(csrf -> csrf.disable());
        http.headers(headers -> headers.frameOptions(options -> options.disable()));
    }

    /*~~(Migrate manually based on https://spring.io/blog/2022/02/21/spring-security-without-the-websecurityconfigureradapter)~~>*/@Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {

        auth.jdbcAuthentication()
                .dataSource(dataSource)
                .withDefaultSchema()
                .passwordEncoder(passwordEncoder)
                .withUser("bugs")
                .password(passwordEncoder.encode("bunny")).roles("USER")
                .and()
                .withUser("daffy")
                .password(passwordEncoder.encode("duck")).roles("USER", "MANAGER");
    }
}