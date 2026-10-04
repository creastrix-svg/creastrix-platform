package com.creastrix.platform.observability;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Closed diagnostic vocabulary, not an audit trail. No caller text, identifiers,
 * exception messages or throwable chains are accepted by the logging boundary.
 * HTTP observations are staged in bounded request-local memory and written only
 * after the security chain has released its session/publication locks.
 */
public final class Diagnostics {
    public static final String REQUEST_ID = "requestId";
    public static final String LOGGER_NAME = "com.creastrix.platform.diagnostics";
    private static final Logger LOG = LoggerFactory.getLogger(LOGGER_NAME);
    private static final ThreadLocal<RequestObservation> REQUEST = new ThreadLocal<>();
    private static final int TRANSACTION_LIMIT = 16;

    private Diagnostics() { }

    public enum Event { LOGIN_RESULT, ACCESS_DENIED, SESSION_EXPIRED, LOGOUT_RESULT, REQUEST_FAILURE }

    public enum Reason {
        LOGIN_INTENT_ACCEPTED, LOCAL_SUCCESS_SELECTED, PROTOCOL_REJECTED,
        NO_AUTHENTICATED_SESSION, ABSOLUTE_LIFETIME_EXPIRED, ADMISSION_DENIED,
        CURRENT_USER_DENIED, REQUEST_BOUNDARY_REJECTED, ALREADY_AUTHENTICATED,
        CALLBACK_CONFLICT, LOGIN_INTENT_REQUIRED, LOCAL_LOGOUT_COMPLETED,
        SESSION_COORDINATION_UNAVAILABLE, IDENTITY_RESOLUTION_UNAVAILABLE,
        DATABASE_UNAVAILABLE, DEADLINE_EXCEEDED, INTERRUPTED, UNEXPECTED_FAILURE
    }

    public enum Completion { COMMITTED, ROLLED_BACK, UNKNOWN, NOT_OBSERVED }
    public enum Route { HEALTH, CSRF, LOGIN, OAUTH_ENTRY, CALLBACK, LOGOUT, PRIVATE_API, OTHER }
    public enum Method { GET, POST, HEAD, OPTIONS, OTHER }

    /** Safe internal exceptions can supply a closed category without exposing their cause text. */
    public interface ClassifiedFailure {
        Reason diagnosticReason();
    }

    /** A safe category only: never retain or stringify the inspected exception. */
    public static Reason failureReason(Throwable failure) {
        if (failure instanceof ClassifiedFailure classified) {
            return classified.diagnosticReason();
        }
        boolean database = false;
        Throwable current = failure;
        // Bounded even for a malicious/cyclic cause chain; no message or SQL detail is read.
        for (int i = 0; current != null && i < 16; i++, current = current.getCause()) {
            if (current instanceof TimeoutException || current instanceof java.sql.SQLTimeoutException
                    || current instanceof org.springframework.dao.QueryTimeoutException) {
                return Reason.DEADLINE_EXCEEDED;
            }
            if (current instanceof InterruptedException) {
                return Reason.INTERRUPTED;
            }
            database |= current instanceof SQLException || current instanceof DataAccessException
                    || current instanceof TransactionException;
        }
        return database ? Reason.DATABASE_UNAVAILABLE : Reason.UNEXPECTED_FAILURE;
    }

    public static Level level(Reason reason) {
        return switch (reason) {
            case UNEXPECTED_FAILURE -> Level.ERROR;
            case SESSION_COORDINATION_UNAVAILABLE, IDENTITY_RESOLUTION_UNAVAILABLE,
                    DATABASE_UNAVAILABLE, DEADLINE_EXCEEDED, INTERRUPTED -> Level.WARN;
            default -> Level.INFO;
        };
    }

    /** First specific reason wins, unless a later technical failure has greater severity. No I/O here. */
    public static void mark(Event event, Reason reason) {
        RequestObservation observation = REQUEST.get();
        if (observation != null) {
            if (observation.event == null) {
                observation.event = event;
                observation.reason = reason;
            }
            else if (level(reason).toInt() > level(observation.reason).toInt()) {
                observation.priorEvent = observation.event;
                observation.priorReason = observation.reason;
                observation.event = event;
                observation.reason = reason;
            }
        }
    }

    public static String correlationId() {
        String value = MDC.get(REQUEST_ID);
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                ? value : null;
    }

    /** Explicitly transfers only the generated correlation ID, not principal or the complete MDC. */
    public static CorrelationScope withCorrelation(String id) {
        return new CorrelationScope(id);
    }

    public static final class CorrelationScope implements AutoCloseable {
        private final String previous = MDC.get(REQUEST_ID);

        private CorrelationScope(String id) {
            MDC.remove(REQUEST_ID);
            if (id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                MDC.put(REQUEST_ID, id);
            }
        }

        @Override
        public void close() {
            MDC.remove(REQUEST_ID);
            if (previous != null) {
                MDC.put(REQUEST_ID, previous);
            }
        }
    }

    static void beginRequest() {
        // A new server-owned ID on every request; never read X-Request-ID or any caller value.
        MDC.put(REQUEST_ID, UUID.randomUUID().toString());
        REQUEST.set(new RequestObservation());
    }

    static void finishRequest(Route route, Method method, int status, long durationNanos, Throwable failure) {
        RequestObservation observation = REQUEST.get();
        try {
            if (observation != null) {
                if (failure != null) {
                    // Selection/denial can precede a later throw. Preserve both facts, never mask failure.
                    Reason reason = failureReason(failure);
                    write(level(reason), "event=REQUEST_FAILURE reason={} prior_event={} prior_reason={}", reason,
                            observation.event == null ? "NONE" : observation.event,
                            observation.reason == null ? "NONE" : observation.reason);
                }
                else if (observation.event != null) {
                    if (observation.priorEvent == null) {
                        write(level(observation.reason), "event={} reason={}", observation.event, observation.reason);
                    }
                    else {
                        write(level(observation.reason), "event={} reason={} prior_event={} prior_reason={}",
                                observation.event, observation.reason, observation.priorEvent, observation.priorReason);
                    }
                }
                for (int i = 0; i < observation.size; i++) {
                    writeTransaction(observation.operations[i], observation.completions[i]);
                }
                if (observation.truncated) {
                    write(Level.WARN, "event=DIAGNOSTIC_LIMIT reason=REQUEST_TRANSACTION_LIMIT");
                }
            }
            // Status is selected server output, not proof of delivery. Never record URI/body/headers.
            write(Level.INFO, "event=HTTP_COMPLETED route={} method={} status={} duration_ms={} outcome={}",
                    route, method, Math.max(100, Math.min(599, status)),
                    Math.max(0, durationNanos / 1_000_000), failure == null ? "RETURNED" : "THREW");
        }
        finally {
            clearRequest();
        }
    }

    static void clearRequest() {
        REQUEST.remove();
        MDC.remove(REQUEST_ID);
    }

    public static void transaction(TransactionDiagnostics.Operation operation, Completion completion) {
        RequestObservation observation = REQUEST.get();
        if (observation == null) {
            writeTransaction(operation, completion);
        }
        else if (observation.size < TRANSACTION_LIMIT) {
            observation.operations[observation.size] = operation;
            observation.completions[observation.size++] = completion;
        }
        else {
            observation.truncated = true;
        }
    }

    private static void writeTransaction(TransactionDiagnostics.Operation operation, Completion completion) {
        Level level = completion == Completion.UNKNOWN || completion == Completion.NOT_OBSERVED
                ? Level.WARN : Level.INFO;
        write(level, "event=DOMAIN_TRANSACTION operation={} completion={}", operation, completion);
    }

    private static void write(Level level, String template, Object... fields) {
        try {
            LOG.atLevel(level).log(template, fields);
        }
        catch (RuntimeException diagnosticFailure) {
            // Diagnostics must not replace application results or turn a committed operation into failure.
            // No fallback throwable print: an appender can itself hold sensitive configuration.
        }
    }

    private static final class RequestObservation {
        private Event event;
        private Reason reason;
        private Event priorEvent;
        private Reason priorReason;
        private final TransactionDiagnostics.Operation[] operations = new TransactionDiagnostics.Operation[TRANSACTION_LIMIT];
        private final Completion[] completions = new Completion[TRANSACTION_LIMIT];
        private int size;
        private boolean truncated;
    }
}
