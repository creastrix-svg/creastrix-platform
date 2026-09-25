package com.creastrix.platform.support;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * An actual loopback HTTP OIDC provider for backend transport tests, never a
 * replacement principal or production decoder. It validates the confidential
 * client, single-use authorization code, redirect URI and S256 proof on the wire.
 * Claims are signed from raw JSON (not JWTClaimsSet) to preserve malformed types.
 */
public final class OidcTestProvider implements AutoCloseable {

    public record Scenario(String subject, Map<String, Object> idClaims,
            Set<String> missingIdClaims, Map<String, Object> userInfoClaims,
            Set<String> missingUserInfoClaims, boolean wrongSigningKey,
            boolean unknownKeyId, boolean wrongState, boolean rejectToken, JWSAlgorithm algorithm) {

        public Scenario {
            idClaims = Collections.unmodifiableMap(new LinkedHashMap<>(idClaims));
            userInfoClaims = Collections.unmodifiableMap(new LinkedHashMap<>(userInfoClaims));
            missingIdClaims = Set.copyOf(missingIdClaims);
            missingUserInfoClaims = Set.copyOf(missingUserInfoClaims);
        }

        public static Scenario verified(String subject) {
            return new Scenario(subject, Map.of(), Set.of(), Map.of(), Set.of(),
                    false, false, false, false, JWSAlgorithm.RS256);
        }

        public Scenario idClaim(String name, Object value) {
            var claims = new LinkedHashMap<>(idClaims);
            claims.put(name, value);
            return new Scenario(subject, claims, missingIdClaims, userInfoClaims,
                    missingUserInfoClaims, wrongSigningKey, unknownKeyId, wrongState, rejectToken, algorithm);
        }

        public Scenario withoutIdClaim(String name) {
            var missing = new java.util.HashSet<>(missingIdClaims);
            missing.add(name);
            return new Scenario(subject, idClaims, missing, userInfoClaims,
                    missingUserInfoClaims, wrongSigningKey, unknownKeyId, wrongState, rejectToken, algorithm);
        }

        public Scenario userInfoClaim(String name, Object value) {
            var claims = new LinkedHashMap<>(userInfoClaims);
            claims.put(name, value);
            return new Scenario(subject, idClaims, missingIdClaims, claims,
                    missingUserInfoClaims, wrongSigningKey, unknownKeyId, wrongState, rejectToken, algorithm);
        }

        public Scenario withoutUserInfoClaim(String name) {
            var missing = new java.util.HashSet<>(missingUserInfoClaims);
            missing.add(name);
            return new Scenario(subject, idClaims, missingIdClaims, userInfoClaims,
                    missing, wrongSigningKey, unknownKeyId, wrongState, rejectToken, algorithm);
        }

        public Scenario badSignature() {
            return new Scenario(subject, idClaims, missingIdClaims, userInfoClaims,
                    missingUserInfoClaims, true, false, wrongState, rejectToken, algorithm);
        }

        public Scenario unknownKey() {
            return new Scenario(subject, idClaims, missingIdClaims, userInfoClaims,
                    missingUserInfoClaims, false, true, wrongState, rejectToken, algorithm);
        }

        public Scenario badState() {
            return new Scenario(subject, idClaims, missingIdClaims, userInfoClaims,
                    missingUserInfoClaims, wrongSigningKey, unknownKeyId, true, rejectToken, algorithm);
        }

        public Scenario tokenFailure() {
            return new Scenario(subject, idClaims, missingIdClaims, userInfoClaims,
                    missingUserInfoClaims, wrongSigningKey, unknownKeyId, wrongState, true, algorithm);
        }

        public Scenario unacceptedAlgorithm() {
            return new Scenario(subject, idClaims, missingIdClaims, userInfoClaims,
                    missingUserInfoClaims, wrongSigningKey, unknownKeyId, wrongState, rejectToken, JWSAlgorithm.RS384);
        }
    }

    private record CodeGrant(Map<String, String> request, Scenario scenario) { }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final RSAKey key;
    private final RSAKey otherKey;
    private final Clock clock;
    private final String clientId;
    private final String clientSecret;
    private final String callback;
    private final URI issuer;
    private final Map<String, CodeGrant> codes = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> accessTokens = new ConcurrentHashMap<>();
    private final List<Map<String, String>> authorizationRequests = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
    private final List<String> issuedTokenValues = new CopyOnWriteArrayList<>();
    private final AtomicInteger jwksRequests = new AtomicInteger();
    private final AtomicInteger userInfoRequests = new AtomicInteger();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Map<String, TokenResponseGate> tokenGates = new ConcurrentHashMap<>();
    private volatile Scenario scenario = Scenario.verified("auth0|pilot");

    public OidcTestProvider(String clientId, String clientSecret, String callback, Clock clock) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.callback = callback;
        this.clock = clock;
        try {
            key = newKey("fixture-key");
            otherKey = newKey("other-key");
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            issuer = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }
        catch (Exception exception) {
            executor.shutdownNow();
            throw new IllegalStateException("Cannot start test-only OIDC provider", exception);
        }
    }

    public URI issuer() { return issuer; }
    public String endpoint(String path) { return issuer.resolve(path).toString(); }
    public void scenario(Scenario next) { scenario = next; }
    public List<Map<String, String>> authorizationRequests() { return List.copyOf(authorizationRequests); }
    public List<Map<String, String>> tokenRequests() { return List.copyOf(tokenRequests); }
    public List<String> issuedTokenValues() { return List.copyOf(issuedTokenValues); }
    public int jwksRequests() { return jwksRequests.get(); }
    public int userInfoRequests() { return userInfoRequests.get(); }
    public Throwable failure() { return failure.get(); }

    /** Holds one real token request for one synthetic subject, never all provider traffic. */
    public TokenResponseGate holdTokenResponse(String subject) {
        TokenResponseGate gate = new TokenResponseGate();
        if (tokenGates.putIfAbsent(subject, gate) != null) {
            throw new IllegalStateException("Duplicate test token gate");
        }
        return gate;
    }

    public static final class TokenResponseGate implements AutoCloseable {
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        public boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
            return entered.await(timeout, unit);
        }

        private void pause() throws InterruptedException {
            if (claimed.compareAndSet(false, true)) {
                entered.countDown();
                if (!released.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test token-response barrier expired");
                }
            }
        }

        @Override public void close() { released.countDown(); }
    }

    public void reset() {
        tokenGates.values().forEach(TokenResponseGate::close);
        tokenGates.clear();
        codes.clear();
        accessTokens.clear();
        authorizationRequests.clear();
        tokenRequests.clear();
        issuedTokenValues.clear();
        userInfoRequests.set(0);
        failure.set(null);
        scenario = Scenario.verified("auth0|pilot");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                switch (exchange.getRequestURI().getPath()) {
                    case "/.well-known/openid-configuration" -> discovery(exchange);
                    case "/authorize" -> authorize(exchange);
                    case "/token" -> token(exchange);
                    case "/jwks" -> jwks(exchange);
                    case "/userinfo" -> userInfo(exchange);
                    default -> json(exchange, 404, Map.of("error", "not_found"));
                }
            }
            catch (IllegalArgumentException invalidRequest) {
                json(exchange, 400, Map.of("error", "invalid_request"));
            }
            catch (Exception unexpected) {
                failure.compareAndSet(null, unexpected);
                json(exchange, 500, Map.of("error", "fixture_failure"));
            }
        }
    }

    private void discovery(HttpExchange exchange) throws IOException {
        json(exchange, 200, Map.ofEntries(
                Map.entry("issuer", issuer.toString()),
                Map.entry("authorization_endpoint", endpoint("authorize")),
                Map.entry("token_endpoint", endpoint("token")),
                Map.entry("jwks_uri", endpoint("jwks")),
                Map.entry("userinfo_endpoint", endpoint("userinfo")),
                Map.entry("response_types_supported", List.of("code")),
                Map.entry("subject_types_supported", List.of("public")),
                Map.entry("id_token_signing_alg_values_supported", List.of("RS256")),
                Map.entry("token_endpoint_auth_methods_supported", List.of("client_secret_basic")),
                Map.entry("code_challenge_methods_supported", List.of("S256")),
                Map.entry("scopes_supported", List.of("openid", "profile", "email"))));
    }

    private void authorize(HttpExchange exchange) throws IOException {
        require("GET".equals(exchange.getRequestMethod()));
        var request = parameters(exchange.getRequestURI().getRawQuery());
        authorizationRequests.add(request);
        require(clientId.equals(request.get("client_id")));
        require(callback.equals(request.get("redirect_uri")));
        require("code".equals(request.get("response_type")));
        require("S256".equals(request.get("code_challenge_method")));
        require(request.getOrDefault("code_challenge", "").matches("[A-Za-z0-9_-]{43}"));
        require(!request.getOrDefault("state", "").isBlank());
        require(!request.getOrDefault("nonce", "").isBlank());
        require("login".equals(request.get("prompt")));
        require("0".equals(request.get("max_age")));
        require(Set.of(request.getOrDefault("scope", "").split(" "))
                .equals(Set.of("openid", "profile", "email")));
        String code = UUID.randomUUID().toString();
        Scenario current = scenario;
        codes.put(code, new CodeGrant(request, current));
        String state = current.wrongState() ? "not-the-request-state" : request.get("state");
        exchange.getResponseHeaders().set("Location", callback + "?code=" + encode(code)
                + "&state=" + encode(state));
        exchange.sendResponseHeaders(302, -1);
    }

    private void token(HttpExchange exchange) throws Exception {
        require("POST".equals(exchange.getRequestMethod()));
        String expectedBasic = "Basic " + Base64.getEncoder().encodeToString(
                (encode(clientId) + ":" + encode(clientSecret)).getBytes(StandardCharsets.UTF_8));
        require(expectedBasic.equals(exchange.getRequestHeaders().getFirst("Authorization")));
        var request = parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        tokenRequests.add(request);
        require("authorization_code".equals(request.get("grant_type")));
        CodeGrant grant = codes.remove(request.getOrDefault("code", ""));
        if (grant == null) {
            json(exchange, 400, Map.of("error", "invalid_grant"));
            return;
        }
        TokenResponseGate gate = tokenGates.get(grant.scenario().subject());
        if (gate != null) {
            gate.pause();
        }
        if (grant.scenario().rejectToken()) {
            json(exchange, 400, Map.of("error", "invalid_grant"));
            return;
        }
        require(callback.equals(request.get("redirect_uri")));
        String verifier = request.getOrDefault("code_verifier", "");
        require(verifier.matches("[A-Za-z0-9._~-]{43,128}"));
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        require(challenge.equals(grant.request().get("code_challenge")));

        long now = clock.instant().getEpochSecond();
        Scenario current = grant.scenario();
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", issuer.toString());
        claims.put("sub", current.subject());
        claims.put("aud", clientId);
        claims.put("iat", now);
        claims.put("exp", now + 600);
        claims.put("auth_time", now);
        claims.put("nonce", grant.request().get("nonce"));
        claims.put("email", "pilot@example.test");
        claims.put("email_verified", true);
        claims.putAll(current.idClaims());
        current.missingIdClaims().forEach(claims::remove);

        var userInfo = new LinkedHashMap<String, Object>();
        userInfo.put("sub", current.subject());
        userInfo.put("email", "pilot@example.test");
        userInfo.put("email_verified", true);
        userInfo.put("auth_time", now);
        userInfo.putAll(current.userInfoClaims());
        current.missingUserInfoClaims().forEach(userInfo::remove);
        String token = UUID.randomUUID().toString();
        accessTokens.put(token, Collections.unmodifiableMap(userInfo));
        var jwt = new JWSObject(new JWSHeader.Builder(current.algorithm())
                .keyID(current.unknownKeyId() ? "unknown-key" : key.getKeyID()).build(),
                new Payload(JSONObjectUtils.toJSONString(claims)));
        jwt.sign(new RSASSASigner(current.wrongSigningKey() ? otherKey : key));
        String idToken = jwt.serialize();
        json(exchange, 200, Map.of("access_token", token, "token_type", "Bearer",
                "expires_in", 600, "scope", "openid profile email", "id_token", idToken));
        issuedTokenValues.addAll(List.of(token, idToken));
    }

    private void jwks(HttpExchange exchange) throws IOException {
        jwksRequests.incrementAndGet();
        json(exchange, 200, new JWKSet(key.toPublicJWK()).toJSONObject());
    }

    private void userInfo(HttpExchange exchange) throws IOException {
        require("GET".equals(exchange.getRequestMethod()));
        userInfoRequests.incrementAndGet();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        Map<String, Object> claims = authorization != null && authorization.startsWith("Bearer ")
                ? accessTokens.get(authorization.substring(7)) : null;
        if (claims == null) {
            json(exchange, 401, Map.of("error", "invalid_token"));
            return;
        }
        json(exchange, 200, claims);
    }

    private static RSAKey newKey(String id) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate()).keyID(id).build();
    }

    public static Map<String, String> parameters(String encoded) {
        var values = new LinkedHashMap<String, String>();
        if (encoded != null && !encoded.isEmpty()) {
            for (String field : encoded.split("&")) {
                String[] pair = field.split("=", 2);
                String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                require(values.putIfAbsent(name, value) == null);
            }
        }
        return Collections.unmodifiableMap(values);
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Invalid test protocol request");
        }
    }

    private static void json(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
        byte[] bytes = JSONObjectUtils.toJSONString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        tokenGates.values().forEach(TokenResponseGate::close);
        server.stop(0);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("OIDC fixture executor did not terminate");
            }
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping OIDC fixture", interrupted);
        }
    }
}
