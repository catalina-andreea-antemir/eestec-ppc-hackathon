package ro.filegrid.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import jakarta.servlet.DispatcherType;

/**
 * Securitatea HTTP (C4: login minim; rolurile pe endpoint-uri vin la C10, matricea 4.4).
 *
 * <ul>
 *   <li>Publice: {@code GET /api/v1/health}, {@code POST /api/v1/auth/login}, {@code POST /api/v1/auth/refresh} și
 *       Actuator health / prometheus (nginx nu proxiază {@code /actuator}, deci acestea sunt doar pe rețeaua
 *       internă). Pe rutele publice un header {@code Authorization} (de exemplu un token expirat trimis de
 *       interceptorul interfeței) se ignoră, ca să nu dea 401.</li>
 *   <li>Restul cere {@code Authorization: Bearer <accessToken>} valid (semnătură HS256, {@code iss}, {@code exp},
 *       {@code typ = "access"}); altfel 401 JSON cu {@code WWW-Authenticate: Bearer}.</li>
 *   <li>Autoritățile vin din claim-ul {@code roles}: {@code ROLE_DISPATCH}, ..., ca la C10 să meargă
 *       {@code hasRole(...)}.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    static final RequestMatcher PUBLIC_ENDPOINTS = new OrRequestMatcher(
            PATHS.matcher(HttpMethod.GET, "/api/v1/health"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/login"),
            PATHS.matcher(HttpMethod.POST, "/api/v1/auth/refresh"),
            // Link semnat de descărcare (C8): autorizarea e token-ul din cale, nu Authorization.
            PATHS.matcher(HttpMethod.GET, "/api/v1/downloads/*"),
            PATHS.matcher(HttpMethod.GET, "/actuator/health"),
            PATHS.matcher(HttpMethod.GET, "/actuator/health/**"),
            PATHS.matcher(HttpMethod.GET, "/actuator/prometheus"));

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TokenService tokens,
                                            JsonSecurityErrorHandler errors) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(bearerTokenResolver())
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors)
                        .jwt(j -> j.decoder(tokens.accessTokenDecoder())
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .headers(Customizer.withDefaults());
        return http.build();
    }

    /** Token-ul din {@code Authorization: Bearer}, ignorat pe rutele publice. */
    static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver standard = new DefaultBearerTokenResolver();
        return request -> PUBLIC_ENDPOINTS.matches(request) ? null : standard.resolve(request);
    }

    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }
}
