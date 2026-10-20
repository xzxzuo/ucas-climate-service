package cn.zuoxu.climate.platform;

import cn.zuoxu.climate.security.ApiKeyFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import java.util.Map;

@Configuration
public class PlatformSecurity {
    @Bean PasswordEncoder platformPasswordEncoder(){return new BCryptPasswordEncoder(12);}
    @Bean @Order(1) @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain platformChain(HttpSecurity http,PlatformUsers users,PasswordEncoder encoder,ObjectMapper json) throws Exception {
        DaoAuthenticationProvider provider=new DaoAuthenticationProvider(users);provider.setPasswordEncoder(encoder);
        return http.securityMatcher("/platform/**","/platform.html","/platform.js","/platform.css")
            .authenticationProvider(provider)
            .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .requestCache(cache->cache.disable())
            // Default CSRF protection stays ON for cookie-authenticated state changes, including login.
            .authorizeHttpRequests(auth->auth
                .requestMatchers("/platform.html","/platform.js","/platform.css","/platform/api/csrf","/platform/api/login").permitAll()
                .anyRequest().authenticated())
            .formLogin(form->form.loginPage("/platform.html").loginProcessingUrl("/platform/api/login")
                .successHandler((req,res,authentication)->{users.loginSucceeded(authentication.getName());res.setContentType("application/json;charset=UTF-8");res.setHeader("Cache-Control","no-store");json.writeValue(res.getWriter(),Map.of("authenticated",true));})
                .failureHandler((req,res,error)->{users.loginFailed(req.getParameter("username"));ApiKeyFilter.writeError(res,json,401,"LOGIN_FAILED","Invalid credentials, disabled account or temporary account lock");}))
            .logout(logout->logout.logoutUrl("/platform/api/logout").deleteCookies("JSESSIONID")
                .logoutSuccessHandler((req,res,authentication)->{res.setContentType("application/json;charset=UTF-8");json.writeValue(res.getWriter(),Map.of("authenticated",false));}))
            .exceptionHandling(errors->errors
                .authenticationEntryPoint((req,res,error)->ApiKeyFilter.writeError(res,json,401,"LOGIN_REQUIRED","Platform login required"))
                .accessDeniedHandler((req,res,error)->ApiKeyFilter.writeError(res,json,403,"FORBIDDEN","Invalid CSRF token or permission denied")))
            .headers(headers->headers.contentSecurityPolicy(csp->csp.policyDirectives("default-src 'self'; style-src 'self'; script-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'")))
            .build();
    }
}
