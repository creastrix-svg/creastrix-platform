package com.creastrix.platform.authentication;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Provider-only recovery. No persistence, principal, session, or domain-service dependency. */
public final class CognitoEmailConfirmationService implements AutoCloseable {
    static final URI ENDPOINT = URI.create("https://cognito-idp.eu-central-1.amazonaws.com/");
    static final int MAX_BYTES = 16 * 1024;
    static final int MAX_WORKERS = 4;
    static final int MAX_TARGETS = 256;
    static final int GLOBAL_LIMIT = 20;
    static final int TARGET_LIMIT = 3;
    static final Duration WINDOW = Duration.ofMinutes(1);
    static final Duration TARGET_TTL = Duration.ofMinutes(5);
    static final Duration DEADLINE = Duration.ofSeconds(5);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> RECIPIENT_ERRORS = Set.of("UserNotFoundException", "NotAuthorizedException",
            "CodeMismatchException", "ExpiredCodeException", "AliasExistsException", "InvalidParameterException",
            "UserLambdaValidationException", "TooManyFailedAttemptsException", "TooManyRequestsException", "LimitExceededException");

    enum Operation {
        CONFIRM("ConfirmSignUp"), RESEND("ResendConfirmationCode");
        final String target;
        Operation(String name) { target = "AWSCognitoIdentityProviderService." + name; }
    }
    enum Result { CONFIRMED, NOT_CONFIRMED, CODE_REQUEST_ACCEPTED, RATE_LIMITED, UNAVAILABLE }
    record Input(String email, String code) {
        @Override public String toString() { return "Input[redacted]"; }
    }
    record ProviderResponse(int status, byte[] body) {
        @Override public String toString() { return "ProviderResponse[redacted]"; }
    }
    @FunctionalInterface
    interface Transport {
        ProviderResponse submit(Operation operation, byte[] body, long deadlineNanos) throws IOException;
    }
    record Limits(int workers, int targets, int global, int target, Duration window, Duration ttl, Duration deadline) {
        Limits {
            if (workers < 1 || targets < 1 || global < 1 || target < 1 || window.isNegative() || window.isZero()
                    || ttl.compareTo(window) < 0 || deadline.isNegative() || deadline.isZero()) {
                throw new IllegalArgumentException("Invalid confirmation limits");
            }
        }
        static Limits standard() {
            return new Limits(MAX_WORKERS, MAX_TARGETS, GLOBAL_LIMIT, TARGET_LIMIT, WINDOW, TARGET_TTL, DEADLINE);
        }
    }
    record Counts(int workers, int targets, int globalAttempts) { }

    private final AuthenticationProperties properties;
    private final Transport transport;
    private final LongSupplier ticker;
    private final Limits limits;
    private final byte[] targetKey = new byte[32];
    private final ReentrantLock admission = new ReentrantLock();
    private final Map<String, Target> targets = new HashMap<>();
    private final Set<Thread> workers = new HashSet<>();
    private long globalStarted;
    private int globalAttempts;
    private volatile boolean closed;

    public CognitoEmailConfirmationService(AuthenticationProperties properties) {
        this(properties, new HttpTransport(ENDPOINT), System::nanoTime, Limits.standard());
    }

    // Test-owned injection only. Neither the endpoint nor the limits are runtime properties.
    CognitoEmailConfirmationService(AuthenticationProperties properties, Transport transport,
                                   LongSupplier ticker, Limits limits) {
        this.properties = properties;
        this.transport = transport;
        this.ticker = ticker;
        this.limits = limits;
        new SecureRandom().nextBytes(targetKey);
        globalStarted = ticker.getAsLong();
    }

    static Input parse(byte[] body, Operation operation) {
        if (body.length > MAX_BYTES) throw new IllegalArgumentException("Invalid confirmation input");
        try {
            JsonNode object = JSON.readTree(body);
            int fields = operation == Operation.CONFIRM ? 2 : 1;
            if (object == null || !object.isObject() || object.size() != fields || !object.has("email")
                    || !object.get("email").isString()
                    || operation == Operation.CONFIRM && (!object.has("code") || !object.get("code").isString())) {
                throw new IllegalArgumentException("Invalid confirmation input");
            }
            String email = object.get("email").asString();
            String code = operation == Operation.CONFIRM ? object.get("code").asString() : null;
            // Preserve the exact submitted identifier. This is not an alias/case-normalization policy.
            int emailLength = email.codePointCount(0, email.length());
            if (emailLength < 1 || emailLength > 128 || email.chars().anyMatch(c -> Character.isWhitespace(c)
                    || Character.isSpaceChar(c) || c == 0xFEFF || Character.isISOControl(c)) || !StandardCharsets.UTF_8.newEncoder().canEncode(email) || email.indexOf('@') < 1
                    || email.indexOf('@') != email.lastIndexOf('@') || email.endsWith("@")
                    || code != null && (code.isEmpty() || code.codePointCount(0, code.length()) > 2048
                        || !StandardCharsets.UTF_8.newEncoder().canEncode(code)
                        || code.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0xFEFF || Character.isISOControl(c)))) {
                throw new IllegalArgumentException("Invalid confirmation input");
            }
            return new Input(email, code);
        }
        catch (RuntimeException malformed) {
            // Jackson messages can contain caller text. Do not expose or retain a parser cause.
            throw new IllegalArgumentException("Invalid confirmation input");
        }
    }

    Result execute(Operation operation, Input input) {
        if (properties.provider() != AuthenticationProperties.Provider.COGNITO || closed) return Result.UNAVAILABLE;
        long deadline = System.nanoTime() + limits.deadline().toNanos();
        // Case folding is conservative abuse-budget grouping only, never provider identity/alias normalization.
        String key = hash(targetKey, input.email().toLowerCase(Locale.ROOT));
        CompletableFuture<Result> outcome = new CompletableFuture<>();
        Thread worker;
        try {
            if (!admission.tryLock(50, TimeUnit.MILLISECONDS)) return Result.UNAVAILABLE;
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.UNAVAILABLE;
        }
        try {
            if (closed) return Result.UNAVAILABLE;
            long now = ticker.getAsLong();
            targets.entrySet().removeIf(entry -> entry.getValue().inFlight == 0
                    && now - entry.getValue().lastAdmitted >= limits.ttl().toNanos());
            if (now - globalStarted >= limits.window().toNanos()) {
                globalStarted = now;
                globalAttempts = 0;
            }
            Target target = targets.get(key);
            if (target != null && now - target.started >= limits.window().toNanos()) {
                target.started = now;
                target.attempts = 0;
            }
            if (workers.size() >= limits.workers() || globalAttempts >= limits.global()
                    || target != null && target.attempts >= limits.target()
                    || target == null && targets.size() >= limits.targets()) return Result.RATE_LIMITED;
            if (target == null) {
                target = new Target(now);
                targets.put(key, target);
            }
            target.attempts++;
            target.inFlight++;
            target.lastAdmitted = now;
            globalAttempts++;
            Target charged = target;
            worker = Thread.ofVirtual().name("cognito-email-confirmation")
                    .uncaughtExceptionHandler((thread, failure) -> { /* Never print arbitrary worker failure text. */ }).unstarted(() -> {
                Result selected = Result.UNAVAILABLE;
                try {
                    if (!closed && !Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
                        selected = classify(operation, transport.submit(operation, payload(operation, input), deadline));
                        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) selected = Result.UNAVAILABLE;
                    }
                }
                catch (IOException | RuntimeException failure) {
                    // Transport loss is UNKNOWN, not proof that the provider did not apply the operation.
                }
                finally {
                    release(charged, Thread.currentThread());
                    outcome.complete(selected);
                }
            });
            workers.add(worker);
        }
        finally { admission.unlock(); }
        // No queue and no network under the admission lock. Interrupt does not reclaim the charge.
        try { worker.start(); }
        catch (RuntimeException | Error failedStart) {
            admission.lock();
            try {
                if (workers.remove(worker)) targets.get(key).inFlight--;
            }
            finally { admission.unlock(); }
            return Result.UNAVAILABLE;
        }
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return outcome.get(remaining, TimeUnit.NANOSECONDS);
        }
        catch (InterruptedException interrupted) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            return Result.UNAVAILABLE;
        }
        catch (TimeoutException | ExecutionException unknown) {
            worker.interrupt();
            return Result.UNAVAILABLE;
        }
    }

    private void release(Target target, Thread worker) {
        // The transport has actually finished its finally/disconnect before releasing a worker slot.
        admission.lock();
        try {
            if (workers.remove(worker)) target.inFlight--;
        }
        finally { admission.unlock(); }
    }

    byte[] payload(Operation operation, Input input) {
        Map<String, Object> body = new HashMap<>();
        body.put("ClientId", properties.clientId());
        body.put("Username", input.email());
        body.put("SecretHash", hash(properties.clientSecret().getBytes(StandardCharsets.UTF_8),
                input.email() + properties.clientId()));
        if (operation == Operation.CONFIRM) {
            body.put("ConfirmationCode", input.code());
            body.put("ForceAliasCreation", false);
        }
        return JSON.writeValueAsBytes(body);
    }

    private static String hash(byte[] secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Confirmation cryptography unavailable");
        }
    }

    private static Result classify(Operation operation, ProviderResponse response) {
        if (response.body().length > MAX_BYTES) return Result.UNAVAILABLE;
        JsonNode body = JSON.readTree(response.body());
        if (body == null || !body.isObject()) return Result.UNAVAILABLE;
        if (response.status() == 200 && !body.has("__type") && !body.has("message") && !body.has("Message")) {
            // Session and delivery details are deliberately discarded; never continue authentication.
            return operation == Operation.CONFIRM ? Result.CONFIRMED : Result.CODE_REQUEST_ACCEPTED;
        }
        if (response.status() != 400 || !body.has("__type") || !body.get("__type").isString()) return Result.UNAVAILABLE;
        String type = body.get("__type").asString();
        int separator = type.lastIndexOf('#');
        if (separator >= 0) type = type.substring(separator + 1);
        // Provider quota/recipient refusals use the same recipient envelope; only our independent limiter returns 429.
        if (!RECIPIENT_ERRORS.contains(type)) return Result.UNAVAILABLE;
        return operation == Operation.CONFIRM ? Result.NOT_CONFIRMED : Result.CODE_REQUEST_ACCEPTED;
    }

    Counts counts() {
        admission.lock();
        try { return new Counts(workers.size(), targets.size(), globalAttempts); }
        finally { admission.unlock(); }
    }
    Set<String> retainedTargetKeys() {
        admission.lock();
        try { return Set.copyOf(targets.keySet()); }
        finally { admission.unlock(); }
    }
    @Override public void close() {
        Set<Thread> charged;
        admission.lock();
        try {
            closed = true;
            charged = Set.copyOf(workers);
        }
        finally { admission.unlock(); }
        charged.forEach(Thread::interrupt);
    }
    private static final class Target {
        long started;
        long lastAdmitted;
        int attempts;
        int inFlight;
        Target(long now) { started = now; lastAdmitted = now; }
    }

    static final class HttpTransport implements Transport {
        private final URI endpoint;
        HttpTransport(URI endpoint) { this.endpoint = endpoint; }
        @Override public ProviderResponse submit(Operation operation, byte[] body, long deadline) throws IOException {
            // URLConnection captures a JVM-global cookie handler. This pilot supports no such handler;
            // concurrent mutation of JVM globals is unsupported, and globals are never changed here.
            if (CookieHandler.getDefault() != null) throw new IOException("Unsupported confirmation transport state");
            HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection(Proxy.NO_PROXY);
            try {
                if (CookieHandler.getDefault() != null) throw new IOException("Unsupported confirmation transport state");
                connection.setAuthenticator(new Authenticator() { });
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setConnectTimeout(Math.min(2000, remainingMillis(deadline)));
                connection.setReadTimeout(remainingMillis(deadline));
                connection.setRequestProperty("Content-Type", "application/x-amz-json-1.0");
                connection.setRequestProperty("X-Amz-Target", operation.target);
                connection.setRequestProperty("Connection", "close");
                // JDK streaming prevents replay after body submission, including redirect/auth replay.
                // A low-level header reconnect before body submission is not a second API operation.
                connection.setFixedLengthStreamingMode(body.length);
                try (var output = connection.getOutputStream()) { output.write(body); }
                connection.setReadTimeout(remainingMillis(deadline));
                int status = connection.getResponseCode();
                if (status != 200 && status != 400) throw new IOException("Confirmation provider outcome unknown");
                if (connection.getContentLengthLong() > MAX_BYTES) throw new IOException("Confirmation response limit exceeded");
                String encoding = connection.getContentEncoding();
                if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new IOException("Unsupported confirmation response");
                InputStream stream = status == 200 ? connection.getInputStream() : connection.getErrorStream();
                if (stream == null) throw new IOException("Confirmation provider outcome unknown");
                try (stream) {
                    var bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    while (true) {
                        connection.setReadTimeout(remainingMillis(deadline));
                        int read = stream.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - bytes.size()));
                        if (read < 0) break;
                        bytes.write(buffer, 0, read);
                        if (bytes.size() > MAX_BYTES) throw new IOException("Confirmation response limit exceeded");
                    }
                    remainingMillis(deadline);
                    long declared = connection.getContentLengthLong();
                    if (declared >= 0 && bytes.size() != declared) throw new IOException("Confirmation provider outcome unknown");
                    return new ProviderResponse(status, bytes.toByteArray());
                }
            }
            finally { connection.disconnect(); }
        }
        private static int remainingMillis(long deadline) throws IOException {
            long nanos = deadline - System.nanoTime();
            if (Thread.currentThread().isInterrupted() || nanos <= 0) throw new IOException("Confirmation deadline exceeded");
            return (int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(nanos)));
        }
    }
}
