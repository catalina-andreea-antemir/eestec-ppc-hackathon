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
 * HTTP security (C4: login; C10: per-endpoint roles, matrix 4.4).
 *
 * <ul>
 *   <li>Public: {@code GET /api/v1/health}, {@code POST /api/v1/auth/login}, {@code POST /api/v1/auth/refresh} and
 *       Actuator health / prometheus (nginx does not proxy {@code /actuator}, so those are internal-network only).
 *       On public routes an {@code Authorization} header (e.g. an expired token sent by the UI interceptor) is
 *       ignored so it does not cause a 401.</li>
 *   <li>Everything else requires a valid {@code Authorization: Bearer <accessToken>} (HS256 signature, {@code iss},
 *       {@code exp}, {@code typ = "access"}); otherwise a JSON 401 with {@code WWW-Authenticate: Bearer}.</li>
 *   <li>Authorities come from the {@code roles} claim: {@code ROLE_DISPATCH}, ..., so {@code hasRole(...)} works.</li>
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
            // Signed download link (C8): authorization is the token in the path, not the Authorization header.
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
                        // Role matrix 4.4 (C10); the per-category role check happens in FileAccess.
                        .requestMatchers(PATHS.matcher(HttpMethod.POST, "/api/v1/meter-readings"))
                                .hasAnyRole("METER_SERVICE", "ADMIN")
                        .requestMatchers(PATHS.matcher("/api/v1/stats/**")).hasAnyRole("DISPATCH", "ADMIN")
                        .requestMatchers(PATHS.matcher("/api/v1/admin/**")).hasRole("ADMIN")
                        .requestMatchers(PATHS.matcher("/api/v1/uploads/**")).hasAnyRole("FIELD", "ADMIN")
                        .requestMatchers(PATHS.matcher("/api/v1/files/**"))
                                .hasAnyRole("FIELD", "DISPATCH", "CONNECTIONS", "ADMIN")
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

    /** The token from {@code Authorization: Bearer}, ignored on public routes. */
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
