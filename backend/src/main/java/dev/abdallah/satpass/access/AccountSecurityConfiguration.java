package dev.abdallah.satpass.access;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableConfigurationProperties(AccountProperties.class)
public class AccountSecurityConfiguration {
    @Bean
    @ConditionalOnProperty(name = "account.enabled", havingValue = "true")
    AccountService accountService(HikariDataSource source, AccessService access, Clock clock) {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(5);
        return new AccountService(new JdbcTemplate(source), transaction, access, clock);
    }

    @Bean
    SecurityFilterChain accountSecurity(HttpSecurity http, AccountProperties properties,
            org.springframework.beans.factory.ObjectProvider<AccountService> accounts) throws Exception {
        // This chain protects sessions only. Existing prediction controllers retain API-key admission.
        http.securityMatcher("/account/**", "/oauth2/**", "/login/oauth2/**")
                .requestCache(cache -> cache.disable());
        if (!properties.enabled()) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint((req, res, ex) -> problem(res, 503))
                            .accessDeniedHandler((req, res, ex) -> problem(res, 503)));
            return http.build();
        }
        AccountService service = accounts.getObject(); // Fails startup if the database opt-in is absent.
        var github = ClientRegistration.withRegistrationId("github")
                .clientId(properties.githubClientId()).clientSecret(properties.githubClientSecret())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(properties.baseUrl() + "/login/oauth2/code/github")
                .authorizationUri("https://github.com/login/oauth/authorize")
                .tokenUri("https://github.com/login/oauth/access_token")
                .userInfoUri("https://api.github.com/user").userNameAttributeName("id")
                .clientName("GitHub").build();
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/account", "/account/", "/account/index.html", "/account/dashboard.js", "/account/dashboard.css",
                                "/oauth2/**", "/login/oauth2/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth -> oauth
                        .clientRegistrationRepository(new InMemoryClientRegistrationRepository(github))
                        .authorizedClientRepository(new HttpSessionOAuth2AuthorizedClientRepository())
                        .loginPage("/account/")
                        .successHandler((req, res, auth) -> {
                            try {
                                var token = (OAuth2AuthenticationToken) auth;
                                service.register(token.getPrincipal().getName());
                                // GitHub credentials are unnecessary after identity was established.
                                new HttpSessionOAuth2AuthorizedClientRepository().removeAuthorizedClient("github", auth, req, res);
                                res.sendRedirect(properties.baseUrl() + "/account/");
                            } catch (RuntimeException unavailable) {
                                req.getSession().invalidate();
                                problem(res, 503);
                            }
                        })
                        .failureUrl(properties.baseUrl() + "/account/?login=failed"))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((req, res, ex) -> problem(res, 401))
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403)))
                .logout(logout -> logout.logoutUrl("/account/logout")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")));
        return http.build();
    }

    static void problem(jakarta.servlet.http.HttpServletResponse res, int status) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.setHeader("Cache-Control", "no-store");
        String detail = switch (status) {
            case 401 -> "Sign in with GitHub to access your account.";
            case 403 -> "Request rejected. Reload the account page and try again.";
            default -> "Self-serve accounts are unavailable. Please retry later.";
        };
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Account access\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
