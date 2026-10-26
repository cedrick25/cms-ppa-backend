package ppa.cms.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ppa.cms.persistence.ApiTokenRepository;
import ppa.cms.persistence.UserAccountRepository;
import ppa.cms.security.AttemptWindow;
import ppa.cms.security.RateLimitFilter;
import ppa.cms.security.TokenAuthenticationFilter;
import ppa.cms.support.JsonCodec;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder(CmsProperties properties) {
        int rounds = Math.max(4, Math.min(properties.getBcryptRounds(), 31));
        return new BCryptPasswordEncoder(rounds);
    }

    @Bean
    AttemptWindow attemptWindow() {
        return new AttemptWindow();
    }

    @Bean
    TokenAuthenticationFilter tokenAuthenticationFilter(ApiTokenRepository tokens, UserAccountRepository users) {
        return new TokenAuthenticationFilter(tokens, users);
    }

    @Bean
    RateLimitFilter rateLimitFilter(AttemptWindow attempts) {
        return new RateLimitFilter(attempts);
    }

    @Bean
    @ConditionalOnWebApplication
    FilterRegistrationBean<TokenAuthenticationFilter> tokenRegistration(TokenAuthenticationFilter filter) {
        FilterRegistrationBean<TokenAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @ConditionalOnWebApplication
    FilterRegistrationBean<RateLimitFilter> rateRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @ConditionalOnWebApplication
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TokenAuthenticationFilter tokenFilter,
            RateLimitFilter rateLimitFilter,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource cors
    ) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(corsConfig -> corsConfig.configurationSource(cors))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write(JsonCodec.write(java.util.Map.of("message", "Unauthenticated.")));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            response.getWriter().write(JsonCodec.write(java.util.Map.of("message", "This action is unauthorized.")));
                        }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/", "/up", "/api/sign-in").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, TokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @ConditionalOnWebApplication
    CorsConfigurationSource corsConfigurationSource(CmsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        String origins = properties.getCorsAllowedOrigins() == null ? "" : properties.getCorsAllowedOrigins();
        List<String> allowed = Arrays.stream(origins.split(",")).map(String::trim).filter(origin -> !origin.isEmpty()).toList();
        if (allowed.isEmpty()) {
            configuration.setAllowedOriginPatterns(List.of("*"));
        } else {
            configuration.setAllowedOrigins(allowed);
        }
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("ETag"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
