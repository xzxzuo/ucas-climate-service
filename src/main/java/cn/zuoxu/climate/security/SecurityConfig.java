package cn.zuoxu.climate.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json,
            @Value("${app.auth.reader-key}") String readerKey,
            @Value("${app.auth.admin-key}") String adminKey) throws Exception {
        // Credentials are sent explicitly in a header, never via cookies or form login.
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/admin", "/api/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/**").hasRole("READER")
                .requestMatchers(HttpMethod.HEAD, "/api/**").hasRole("READER")
                .requestMatchers("/api/**").denyAll()
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.ico", "/actuator/health").permitAll()
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> ApiKeyFilter.writeError(response, json, 401, "API_KEY_REQUIRED", "X-API-Key authentication is required"))
                .accessDeniedHandler((request, response, exception) -> ApiKeyFilter.writeError(response, json, 403, "FORBIDDEN", "This API key does not have the required role")))
            // Instantiate here, not as a @Component: avoid double servlet-filter registration.
            .addFilterBefore(new ApiKeyFilter(readerKey, adminKey, json), AnonymousAuthenticationFilter.class)
            .build();
    }
}
