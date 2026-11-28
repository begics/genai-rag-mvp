package com.begics.rag.security;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
  // CSP hashes authorize only the inline UI blocks; refresh them when those blocks change.
  private static final String CONTENT_SECURITY_POLICY =
      "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; "
          + "form-action 'self'; script-src 'self' 'sha256-rZ3yej6GU+Jca04mqsY6hdhf2fbgto3BEvpUFMyrZ6w='; "
          + "style-src 'self' 'sha256-7v9J8q4Y2afrKLrZBHEfg5lNQ2IkafVPUrJLoHU1iyU='; "
          + "img-src 'self' data:; connect-src 'self'";

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, RateLimitFilter rateLimitFilter, ClientRegistrationRepository registrations,
      JwtDecoder jwtDecoder)
      throws Exception {
    CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrfTokenRepository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Strict"));
    CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
    OidcClientInitiatedLogoutSuccessHandler logoutSuccessHandler =
        new OidcClientInitiatedLogoutSuccessHandler(registrations);
    logoutSuccessHandler.setPostLogoutRedirectUri("{baseUrl}/");
    return http
        .csrf(csrf -> csrf
            .csrfTokenRepository(csrfTokenRepository)
            .csrfTokenRequestHandler(csrfRequestHandler)
            .ignoringRequestMatchers(SecurityConfiguration::isBearerRequest))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/", "/index.html", "/demo.html", "/demo.css", "/demo.js", "/rag-flow.css", "/favicon.ico",
                "/actuator/health", "/actuator/health/**", "/oauth2/**", "/login/**").permitAll()
            .anyRequest().authenticated())
        .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
            request -> request.getServletPath().startsWith("/api/")))
        .headers(headers -> headers
            .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
            .frameOptions(frame -> frame.deny())
            .contentTypeOptions(Customizer.withDefaults())
            .referrerPolicy(referrer -> referrer.policy(
                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            .permissionsPolicy(permissions -> permissions.policy(
                "camera=(), microphone=(), geolocation=()")))
        .oauth2Login(oauth2 -> oauth2
            .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService(jwtDecoder))))
        .oauth2Client(Customizer.withDefaults())
        .oauth2ResourceServer(resource -> resource.jwt(jwt ->
            jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
        .logout(logout -> logout
            .logoutSuccessHandler(logoutSuccessHandler)
            .invalidateHttpSession(true).clearAuthentication(true))
        .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  ClientRegistrationRepository clientRegistrationRepository(
      @Value("${spring.security.oauth2.client.registration.keycloak.client-id}") String clientId,
      @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}") String issuer,
      @Value("${spring.security.oauth2.client.provider.keycloak.authorization-uri}") String authorizationUri,
      @Value("${spring.security.oauth2.client.provider.keycloak.token-uri}") String tokenUri,
      @Value("${spring.security.oauth2.client.provider.keycloak.user-info-uri}") String userInfoUri,
      @Value("${spring.security.oauth2.client.provider.keycloak.jwk-set-uri}") String jwkSetUri,
      @Value("${spring.security.oauth2.client.provider.keycloak.end-session-uri}") String endSessionUri) {
    ClientRegistration registration = ClientRegistration.withRegistrationId("keycloak")
        .clientId(clientId)
        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .scope("openid", "profile", "email")
        .authorizationUri(authorizationUri)
        .tokenUri(tokenUri)
        .userInfoUri(userInfoUri)
        .userNameAttributeName("sub")
        .jwkSetUri(jwkSetUri)
        .issuerUri(issuer)
        .providerConfigurationMetadata(Map.of(
            "issuer", issuer,
            "end_session_endpoint", endSessionUri))
        .clientName("keycloak")
        .build();
    return new InMemoryClientRegistrationRepository(registration);
  }

  @Bean
  RateLimitFilter rateLimitFilter() {
    return new RateLimitFilter();
  }

  @Bean
  FilterRegistrationBean<Filter> rateLimitFilterRegistration(RateLimitFilter rateLimitFilter) {
    FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(rateLimitFilter);
    registration.setEnabled(false);
    return registration;
  }

  private static OidcUserService oidcUserService(JwtDecoder jwtDecoder) {
    return new OidcUserService() {
      @Override
      public OidcUser loadUser(org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest userRequest) {
        OidcUser user = super.loadUser(userRequest);
        Set<GrantedAuthority> authorities = new HashSet<>(user.getAuthorities());
        addRealmRoles(authorities, userRequest.getIdToken().getClaim("realm_access"));
        addRealmRoles(authorities, user.getClaim("realm_access"));
        Jwt accessToken = jwtDecoder.decode(userRequest.getAccessToken().getTokenValue());
        addRealmRoles(authorities, accessToken.getClaims().get("realm_access"));
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), "sub");
      }
    };
  }

  private static JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      Set<GrantedAuthority> authorities = new HashSet<>(scopes.convert(jwt));
      addRealmRoles(authorities, jwt.getClaims().get("realm_access"));
      authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
      return authorities;
    });
    return converter;
  }

  static void addRealmRoles(Set<GrantedAuthority> authorities, Object realmAccess) {
    if (realmAccess instanceof Map<?, ?> claims && claims.get("roles") instanceof Iterable<?> roles) {
      for (Object role : roles) {
        if (role instanceof String name && name.matches("[A-Za-z0-9_-]{1,64}")) {
          authorities.add(new SimpleGrantedAuthority("ROLE_" + name.toUpperCase(java.util.Locale.ROOT)));
        }
      }
    }
  }

  private static boolean isBearerRequest(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
  }
}
