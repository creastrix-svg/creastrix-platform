package com.creastrix.platform.authentication;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.creastrix.platform.CreastrixApplication;
import com.creastrix.platform.accountcart.catalog.SharedCatalogAuthority;
import com.creastrix.platform.support.OidcTestProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/** Explicit test-classpath launcher; no synthetic issuer, keys or beans enter the packaged application. */
public final class LocalAccountCartLauncher {
    private static final String CLIENT = "local-synthetic-cart";
    private static final String CALLBACK = LocalAccountCartConfiguration.ORIGIN + "/login/oauth2/code/cognito";
    private static final String SECRET = UUID.randomUUID().toString();
    static final String SUBJECT_B = "cart-synthetic-buyer-b";

    private LocalAccountCartLauncher() { }

    public static void main(String[] args) {
        var flags=java.util.List.of(args);
        boolean twoBuyers=flags.contains("--two-buyers"),shared=flags.contains("--shared-catalog");
        if (flags.size()!=(twoBuyers?1:0)+(shared?1:0) || System.getenv("CART_LOCAL_DB_PASSWORD") == null
                || System.getenv("CART_LOCAL_DB_PASSWORD").isBlank()) {
            throw new IllegalArgumentException("Use the dedicated local account-cart launcher");
        }
        new SpringApplicationBuilder(SyntheticWiring.class, CreastrixApplication.class).run(
                "--creastrix.test.two-buyers=" + twoBuyers,
                "--creastrix.test.shared-catalog=" + shared,
                "--spring.profiles.active=" + LocalAccountCartConfiguration.PROFILE,
                "--spring.config.location=classpath:/application.yml",
                "--server.address=127.0.0.1", "--server.port=8191", "--server.forward-headers-strategy=none",
                "--spring.datasource.url=" + LocalAccountCartConfiguration.DATABASE,
                "--spring.datasource.username=" + LocalAccountCartConfiguration.ROLE,
                "--spring.datasource.password=${CART_LOCAL_DB_PASSWORD}",
                "--spring.flyway.locations=" + LocalAccountCartConfiguration.MIGRATIONS,
                "--creastrix.auth.enabled=true", "--creastrix.auth.existing-only=true",
                "--creastrix.auth.issuer=" + LocalAccountCartConfiguration.ISSUER,
                "--creastrix.auth.browser-origin=" + LocalAccountCartConfiguration.ORIGIN,
                "--server.servlet.session.cookie.secure=false", "--server.servlet.session.cookie.http-only=true",
                "--server.servlet.session.cookie.same-site=lax", "--server.servlet.session.cookie.path=/",
                "--server.servlet.session.tracking-modes=cookie", "--spring.main.banner-mode=off");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SyntheticWiring {
        record Buyers(Set<String> admitted, Supplier<OidcTestProvider.Scenario> select) {}

        @Bean Buyers cartTestBuyers(@Value("${creastrix.test.two-buyers:false}") boolean twoBuyers, JdbcTemplate sql) {
            var a = OidcTestProvider.Scenario.verified(LocalAccountCartConfiguration.SUBJECT).idClaim("email", "buyer@cart.invalid");
            if (!twoBuyers) return new Buyers(Set.of(a.subject()), () -> a);
            var b = OidcTestProvider.Scenario.verified(SUBJECT_B).idClaim("email", "buyer-b@cart.invalid");
            return new Buyers(Set.of(a.subject(), b.subject()), () -> sql.queryForObject(
                    "SELECT account_cart_fixture.consume_next_buyer_b()", Boolean.class) ? b : a);
        }

        @Bean LocalCartSessionBinding.SyntheticRuntime syntheticCartRuntime(@Value("${creastrix.test.shared-catalog:false}") boolean shared) {
            return new LocalCartSessionBinding.SyntheticRuntime(shared
                    ? SharedCatalogAuthority.local(System.getenv("CART_CATALOG_LOOKUP_PASSWORD")) : null);
        }
        @Bean(destroyMethod = "close")
        OidcTestProvider cartSyntheticIssuer(Buyers buyers) {
            var provider = new OidcTestProvider(CLIENT, SECRET, CALLBACK, Clock.systemUTC(), 8192);
            provider.scenarios(buyers.select());
            return provider;
        }

        @Bean @Primary
        AuthenticationProperties cartAuthProperties(OidcTestProvider provider, Buyers buyers) {
            return new AuthenticationProperties(provider.issuer().toString(), CLIENT, SECRET,
                    LocalAccountCartConfiguration.ORIGIN, buyers.admitted(),
                    false, AuthenticationProperties.Provider.COGNITO, true);
        }

        @Bean @Primary
        ClientRegistrationRepository cartRegistration(OidcTestProvider provider) {
            return new InMemoryClientRegistrationRepository(ClientRegistration
                    .withRegistrationId(AuthenticationProperties.REGISTRATION_ID).clientId(CLIENT).clientSecret(SECRET)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri(CALLBACK)
                    .scope("openid", "profile", "email").authorizationUri(provider.endpoint("authorize"))
                    .tokenUri(provider.endpoint("token")).jwkSetUri(provider.endpoint("jwks"))
                    .issuerUri(provider.issuer().toString()).userNameAttributeName(IdTokenClaimNames.SUB)
                    .clientName("Synthetic cart owner").build());
        }

        @Bean @Primary
        CognitoEmailConfirmationService cartConfirmationUnavailable(AuthenticationProperties properties) {
            // Even an accidental invocation cannot send email or reach a cloud provider.
            return new CognitoEmailConfirmationService(properties, (operation, body, deadline) -> {
                throw new IllegalStateException("Confirmation is unavailable in the synthetic cart runtime");
            }, System::nanoTime, CognitoEmailConfirmationService.Limits.standard());
        }
    }
}
