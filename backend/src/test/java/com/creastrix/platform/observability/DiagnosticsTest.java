package com.creastrix.platform.observability;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.creastrix.platform.authentication.AuthenticationProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Real logging events, but no server, database, provider, or Docker resources. */
class DiagnosticsTest {
    private static final String PRIVATE_VALUE = "test-private-" + UUID.randomUUID();
    private final HttpDiagnosticsFilter filter = new HttpDiagnosticsFilter();
    private final CapturingAppender appender = new CapturingAppender();
    private Logger logger;
    private ch.qos.logback.classic.Level priorLevel;
    private boolean priorAdditivity;
    private Map<String, String> priorMdc;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void malformedConfigurationHasOnlySafeFieldNameAndNoRawUriCause(boolean issuer) {
        String malformed = "https://" + PRIVATE_VALUE + " invalid/";
        Throwable failure = catchThrowable(() -> {
            var properties = new AuthenticationProperties(issuer ? malformed : "https://pilot.eu.auth0.com/",
                    "client", PRIVATE_VALUE, issuer ? "http://localhost:3000" : malformed, java.util.Set.of(), false);
            properties.validateRuntime(true);
        });
        assertThat(failure instanceof IllegalArgumentException).as("Malformed configuration has the expected exception category").isTrue();
        assertThat(("creastrix.auth." + (issuer ? "issuer" : "browser-origin") + " must be a valid URI")
                .equals(failure.getMessage())).as("Configuration error contains only the exact safe field message").isTrue();
        assertThat(failure.getCause() == null).as("Configuration error does not retain a raw URI cause").isTrue();
        assertThat(failure.toString().contains(PRIVATE_VALUE)).as("Configuration error does not disclose private input").isFalse();
    }

    @BeforeEach
    void captureOnlyTheDiagnosticLogger() {
        priorMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        logger = (Logger) LoggerFactory.getLogger(Diagnostics.LOGGER_NAME);
        priorLevel = logger.getLevel();
        priorAdditivity = logger.isAdditive();
        logger.setLevel(ch.qos.logback.classic.Level.TRACE);
        logger.setAdditive(false);
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restoreOnlyThisTestsLoggingAndThreadState() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(priorLevel);
        logger.setAdditive(priorAdditivity);
        TransactionSynchronizationManager.clear();
        MDC.clear();
        if (priorMdc != null) {
            MDC.setContextMap(priorMdc);
        }
    }

    static Stream<Arguments> classifiedFailures() {
        return Stream.of(
                Arguments.of(new TimeoutException(PRIVATE_VALUE), Diagnostics.Reason.DEADLINE_EXCEEDED),
                Arguments.of(new SQLTimeoutException(PRIVATE_VALUE), Diagnostics.Reason.DEADLINE_EXCEEDED),
                Arguments.of(new QueryTimeoutException(PRIVATE_VALUE), Diagnostics.Reason.DEADLINE_EXCEEDED),
                Arguments.of(new InterruptedException(PRIVATE_VALUE), Diagnostics.Reason.INTERRUPTED),
                Arguments.of(new SQLException(PRIVATE_VALUE), Diagnostics.Reason.DATABASE_UNAVAILABLE),
                Arguments.of(new DataAccessResourceFailureException(PRIVATE_VALUE), Diagnostics.Reason.DATABASE_UNAVAILABLE),
                Arguments.of(new CannotCreateTransactionException(PRIVATE_VALUE), Diagnostics.Reason.DATABASE_UNAVAILABLE),
                Arguments.of(new IllegalStateException(PRIVATE_VALUE), Diagnostics.Reason.UNEXPECTED_FAILURE),
                Arguments.of(new RuntimeException(PRIVATE_VALUE, new SQLException(PRIVATE_VALUE,
                        new TimeoutException(PRIVATE_VALUE))), Diagnostics.Reason.DEADLINE_EXCEEDED));
    }

    @ParameterizedTest(name = "Safe failure classification case {index}")
    @MethodSource("classifiedFailures")
    void failureClassificationNeverNeedsDiagnosticText(Throwable failure, Diagnostics.Reason expected) {
        assertThat(Diagnostics.failureReason(failure)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(Diagnostics.Reason.class)
    void everyFiniteReasonHasItsDocumentedLevel(Diagnostics.Reason reason) throws Exception {
        Level expected = switch (reason) {
            case UNEXPECTED_FAILURE -> Level.ERROR;
            case SESSION_COORDINATION_UNAVAILABLE, IDENTITY_RESOLUTION_UNAVAILABLE,
                    DATABASE_UNAVAILABLE, DEADLINE_EXCEEDED, INTERRUPTED -> Level.WARN;
            default -> Level.INFO;
        };
        filter.doFilter(new MockHttpServletRequest("GET", "/api/me"), new MockHttpServletResponse(),
                (request, response) -> Diagnostics.mark(Diagnostics.Event.ACCESS_DENIED, reason));
        assertThat(Diagnostics.level(reason)).isEqualTo(expected);
        assertEventCount(2);
        assertThat(appender.list.getFirst().getLevel().levelStr).isEqualTo(expected.name());
        assertMessageContains(appender.list.getFirst(), "reason=" + reason);
        assertNoThrowableProxy();
    }

    @Test
    void cyclicCauseTraversalIsBoundedWithoutReadingMessages() {
        AtomicInteger visits = new AtomicInteger();
        RuntimeException cyclic = new RuntimeException() {
            @Override public synchronized Throwable getCause() {
                visits.incrementAndGet();
                return this;
            }
            @Override public String getMessage() {
                throw new AssertionError("Exception messages are outside the diagnostic contract");
            }
            @Override public String toString() {
                throw new AssertionError("Exception rendering is outside the diagnostic contract");
            }
        };
        assertThat(Diagnostics.failureReason(cyclic)).isEqualTo(Diagnostics.Reason.UNEXPECTED_FAILURE);
        assertThat(visits.get()).isEqualTo(16);
    }

    @Test
    void hostileRequestAndExceptionContentCannotReachTheLogBoundary() {
        var request = new MockHttpServletRequest("PRIVATE-" + PRIVATE_VALUE, "/" + PRIVATE_VALUE + "%0d%0a");
        request.setQueryString("code=" + PRIVATE_VALUE);
        request.addHeader("Authorization", "Bearer " + PRIVATE_VALUE);
        request.addHeader("Cookie", "JSESSIONID=" + PRIVATE_VALUE);
        request.addHeader("X-Request-ID", PRIVATE_VALUE + "\r\nforged=true");
        request.setContent(PRIVATE_VALUE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        var failure = new IllegalStateException(PRIVATE_VALUE,
                new SQLException("jdbc:postgresql://" + PRIVATE_VALUE));

        Throwable observed = catchThrowable(() -> filter.doFilter(request, response, (input, output) -> {
            response.setStatus(503);
            throw failure;
        }));
        assertThat(observed == failure).as("The exact original failure escapes without diagnostic substitution").isTrue();

        assertThat(response.getStatus()).isEqualTo(503);
        assertEventCount(2);
        assertMessageEquals(appender.list.getFirst(),
                "event=REQUEST_FAILURE reason=DATABASE_UNAVAILABLE prior_event=NONE prior_reason=NONE");
        assertMessageContains(appender.list.getLast(), "route=OTHER method=OTHER status=503", "outcome=THREW");
        assertNoPrivateValueOrThrowable();
        assertNoRequestId();
    }

    @ParameterizedTest
    @EnumSource(value = Diagnostics.Reason.class, names = { "LOCAL_SUCCESS_SELECTED", "ADMISSION_DENIED" })
    void anEarlierOutcomeCannotHideAnUnexpectedEscapingFailure(Diagnostics.Reason priorReason) {
        Diagnostics.Event priorEvent = priorReason == Diagnostics.Reason.LOCAL_SUCCESS_SELECTED
                ? Diagnostics.Event.LOGIN_RESULT : Diagnostics.Event.ACCESS_DENIED;
        var response = new MockHttpServletResponse();
        var failure = new IllegalStateException(PRIVATE_VALUE, new IllegalArgumentException(PRIVATE_VALUE));
        Throwable observed = catchThrowable(() -> filter.doFilter(
                new MockHttpServletRequest("GET", "/login/oauth2/code/auth0"), response, (request, output) -> {
                    response.setStatus(priorReason == Diagnostics.Reason.LOCAL_SUCCESS_SELECTED ? 303 : 403);
                    Diagnostics.mark(priorEvent, priorReason);
                    throw failure;
                }));

        assertThat(observed == failure).as("The original failure is not replaced").isTrue();
        assertThat(response.getStatus()).isEqualTo(priorReason == Diagnostics.Reason.LOCAL_SUCCESS_SELECTED ? 303 : 403);
        assertEventCount(2);
        ILoggingEvent diagnostic = appender.list.getFirst();
        assertThat(diagnostic.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
        assertMessageEquals(diagnostic, "event=REQUEST_FAILURE reason=UNEXPECTED_FAILURE prior_event="
                + priorEvent + " prior_reason=" + priorReason);
        assertMessageContains(appender.list.getLast(), "outcome=THREW");
        assertNoRequestId();
        assertNoPrivateValueOrThrowable();
    }

    @Test
    void internalClassifiedFailureExposesOnlyItsClosedReason() {
        var failure = new ClassifiedException(Diagnostics.Reason.SESSION_COORDINATION_UNAVAILABLE);
        Throwable observed = catchThrowable(() -> filter.doFilter(new MockHttpServletRequest("POST", "/auth/logout"),
                new MockHttpServletResponse(), (request, response) -> { throw failure; }));
        assertThat(observed == failure).as("The classified failure object is preserved").isTrue();
        assertThat(Diagnostics.failureReason(failure)).isEqualTo(Diagnostics.Reason.SESSION_COORDINATION_UNAVAILABLE);
        assertEventCount(2);
        assertThat(appender.list.getFirst().getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
        assertMessageEquals(appender.list.getFirst(),
                "event=REQUEST_FAILURE reason=SESSION_COORDINATION_UNAVAILABLE prior_event=NONE prior_reason=NONE");
        assertNoPrivateValueOrThrowable();
    }

    @Test
    void handledTechnicalFailureSupersedesConflictAndCannotBeDowngradedByProtocolRejection() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/login/oauth2/code/auth0"), response, (request, output) -> {
            Diagnostics.mark(Diagnostics.Event.LOGIN_RESULT, Diagnostics.Reason.CALLBACK_CONFLICT);
            Diagnostics.mark(Diagnostics.Event.REQUEST_FAILURE, Diagnostics.Reason.SESSION_COORDINATION_UNAVAILABLE);
            Diagnostics.mark(Diagnostics.Event.LOGIN_RESULT, Diagnostics.Reason.PROTOCOL_REJECTED);
            response.setStatus(303);
            response.setHeader("Location", "/login?auth=failed");
        });

        assertThat(response.getStatus()).isEqualTo(303);
        assertThat("/login?auth=failed".equals(response.getHeader("Location"))).as("Failure navigation remains exact").isTrue();
        assertEventCount(2);
        assertThat(appender.list.getFirst().getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
        assertMessageEquals(appender.list.getFirst(),
                "event=REQUEST_FAILURE reason=SESSION_COORDINATION_UNAVAILABLE prior_event=LOGIN_RESULT prior_reason=CALLBACK_CONFLICT");
        assertMessageContains(appender.list.getLast(), "status=303", "outcome=RETURNED");
        assertNoRequestId();
    }

    static Stream<Arguments> accessorFailures() {
        return Stream.of(Arguments.of(false, false), Arguments.of(false, true),
                Arguments.of(true, false), Arguments.of(true, true));
    }

    @ParameterizedTest(name = "Malformed method accessor throws={0}, application throws={1}")
    @MethodSource("accessorFailures")
    void methodAccessorCannotReplaceTheApplicationOutcomeOrRetainRequestState(boolean accessorThrows,
                                                                             boolean applicationThrows) throws Exception {
        var request = new HttpServletRequestWrapper(new MockHttpServletRequest("GET", "/api/me")) {
            @Override
            public String getMethod() {
                if (accessorThrows) {
                    throw new IllegalStateException(PRIVATE_VALUE);
                }
                return null;
            }
        };
        var response = new MockHttpServletResponse();
        var originalFailure = new IllegalStateException(PRIVATE_VALUE);
        Throwable observed = catchThrowable(() -> filter.doFilter(request, response, (input, output) -> {
            Diagnostics.mark(Diagnostics.Event.ACCESS_DENIED, Diagnostics.Reason.CURRENT_USER_DENIED);
            response.setStatus(207);
            response.setHeader("X-Fixed", "preserved");
            response.getWriter().write("unchanged");
            if (applicationThrows) {
                throw originalFailure;
            }
        }));

        assertThat(applicationThrows ? observed == originalFailure : observed == null)
                .as("The diagnostic accessor cannot replace the original return or failure").isTrue();
        assertThat(response.getStatus()).isEqualTo(207);
        assertThat("preserved".equals(response.getHeader("X-Fixed"))).as("The fixed response header is preserved").isTrue();
        assertThat("unchanged".equals(response.getContentAsString())).as("The exact response body is preserved").isTrue();
        assertNoRequestId();
        assertNoPrivateValueOrThrowable();
        appender.list.clear();
        // A cleared request holder writes immediately instead of silently buffering this observation.
        Diagnostics.transaction(TransactionDiagnostics.Operation.PRODUCT_CREATE, Diagnostics.Completion.NOT_OBSERVED);
        assertEventCount(1);
        assertEventMdcEquals(appender.list.getFirst(), Map.of());
        appender.list.clear();
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(),
                (input, output) -> { });
        assertEventCount(1);
        assertMessageContains(appender.list.getFirst(), "event=HTTP_COMPLETED", "outcome=RETURNED");
        assertNoRequestId();
    }

    @Test
    void requestIdIsServerGeneratedAndResponseRemainsExactlySelectedByTheChain() throws Exception {
        String callerId = UUID.randomUUID().toString();
        String residualId = UUID.randomUUID().toString();
        MDC.put(Diagnostics.REQUEST_ID, residualId);
        var request = new MockHttpServletRequest("POST", "/auth/login");
        request.addHeader("X-Request-ID", callerId);
        var response = new MockHttpServletResponse();
        AtomicReference<String> assigned = new AtomicReference<>();

        filter.doFilter(request, response, (input, output) -> {
            assigned.set(Diagnostics.correlationId());
            response.setStatus(303);
            response.setHeader("Location", "/fixed-navigation");
            response.setHeader("X-Fixed", "preserved");
            response.getWriter().write("fixed-response");
        });

        assertThat(assigned.get() != null && !assigned.get().equals(callerId) && !assigned.get().equals(residualId))
                .as("Assigned correlation is present and differs from caller and residual values").isTrue();
        assertThat(isCanonicalUuid(assigned.get())).as("Assigned correlation is a canonical UUID").isTrue();
        assertThat(response.getStatus()).isEqualTo(303);
        assertThat(response.getHeaderNames().size() == 2
                && response.getHeaderNames().containsAll(java.util.Set.of("Location", "X-Fixed")))
                .as("Only the two exact chain-selected headers remain").isTrue();
        assertThat("/fixed-navigation".equals(response.getHeader("Location"))).as("Selected navigation remains exact").isTrue();
        assertThat("preserved".equals(response.getHeader("X-Fixed"))).as("The fixed response header is preserved").isTrue();
        assertThat("fixed-response".equals(response.getContentAsString())).as("The exact response body is preserved").isTrue();
        assertEventCount(2);
        appender.list.forEach(event -> assertEventMdcEquals(event, Map.of(Diagnostics.REQUEST_ID, assigned.get())));
        assertMessageEquals(appender.list.getFirst(), "event=LOGIN_RESULT reason=LOGIN_INTENT_ACCEPTED");
        assertNoRequestId();
    }

    static Stream<Arguments> chainFailures() {
        return Stream.of(Arguments.of(new IOException(PRIVATE_VALUE)),
                Arguments.of(new ServletException(PRIVATE_VALUE)),
                Arguments.of(new IllegalStateException(PRIVATE_VALUE)),
                Arguments.of(new AssertionError(PRIVATE_VALUE)));
    }

    @ParameterizedTest(name = "Cleanup after thrown boundary case {index}")
    @MethodSource("chainFailures")
    void cleanupPreservesTheExactFailureAndDoesNotLeakToTheNextRequest(Throwable failure) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/me");
        Throwable observed = catchThrowable(() -> filter.doFilter(request, new MockHttpServletResponse(), (input, output) -> {
            switch (failure) {
                case IOException io -> throw io;
                case ServletException servlet -> throw servlet;
                case RuntimeException runtime -> throw runtime;
                case Error error -> throw error;
                default -> throw new AssertionError("Unsupported test failure category");
            }
        }));
        assertThat(observed == failure).as("The exact original failure escapes without diagnostic substitution").isTrue();
        assertNoRequestId();
        String oldId = appender.list.getFirst().getMDCPropertyMap().get(Diagnostics.REQUEST_ID);
        appender.list.clear();

        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"),
                new MockHttpServletResponse(), (input, output) -> { });
        assertEventCount(1);
        assertMessageContains(appender.list.getFirst(), "event=HTTP_COMPLETED", "outcome=RETURNED");
        assertThat(!java.util.Objects.equals(appender.list.getFirst().getMDCPropertyMap().get(Diagnostics.REQUEST_ID), oldId))
                .as("A later request has a distinct correlation").isTrue();
        assertNoRequestId();
        assertNoPrivateValueOrThrowable();
    }

    @Test
    void parallelRequestsHaveDistinctCorrelationAndEachWorkerIsCleanAfterReturn() throws Exception {
        int workers = 4;
        CountDownLatch entered = new CountDownLatch(workers);
        CountDownLatch release = new CountDownLatch(1);
        var ids = new CopyOnWriteArrayList<String>();
        var executor = Executors.newFixedThreadPool(workers);
        try {
            var futures = IntStream.range(0, workers).mapToObj(index -> executor.submit(() -> {
                assertNoRequestId();
                filter.doFilter(new MockHttpServletRequest("GET", "/api/me"), new MockHttpServletResponse(),
                        (input, output) -> {
                            String id = Diagnostics.correlationId();
                            ids.add(id);
                            entered.countDown();
                            try {
                                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                            }
                            catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new ServletException("Test worker interrupted");
                            }
                            assertThat(java.util.Objects.equals(Diagnostics.correlationId(), id))
                                    .as("Concurrent request retains its own correlation").isTrue();
                            Diagnostics.mark(Diagnostics.Event.ACCESS_DENIED, Diagnostics.Reason.ADMISSION_DENIED);
                        });
                assertNoRequestId();
                return true;
            })).toList();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (var future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isTrue();
            }
        }
        finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(ids.size()).as("All parallel requests supplied correlation observations").isEqualTo(workers);
        assertThat(ids.stream().distinct().count()).as("Parallel request correlations are distinct").isEqualTo(workers);
        assertEventCount(workers * 2);
        for (String id : ids) {
            assertThat(appender.list.stream().filter(event -> id.equals(event.getMDCPropertyMap()
                    .get(Diagnostics.REQUEST_ID))).count()).as("Each request has exactly two correlated events").isEqualTo(2);
        }
        assertNoPrivateValueOrThrowable();
    }

    @Test
    void virtualWorkerReceivesOnlyExplicitCorrelationAndRestoresItsPriorState() throws Exception {
        String id = UUID.randomUUID().toString();
        MDC.put(Diagnostics.REQUEST_ID, id);
        MDC.put("private-context", PRIVATE_VALUE);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> {
                assertThat(Thread.currentThread().isVirtual()).isTrue();
                assertNoRequestId();
                assertThat(MDC.get("private-context") == null).as("Worker does not inherit arbitrary private MDC").isTrue();
                try (var ignored = Diagnostics.withCorrelation(id)) {
                    assertThat(Map.of(Diagnostics.REQUEST_ID, id).equals(MDC.getCopyOfContextMap()))
                            .as("Worker MDC contains only the exact explicitly transferred correlation").isTrue();
                    Diagnostics.transaction(TransactionDiagnostics.Operation.USER_CREATE, Diagnostics.Completion.UNKNOWN);
                }
                assertNoRequestId();
                return true;
            });
            assertThat(future.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(id.equals(Diagnostics.correlationId())).as("Calling thread retains its original correlation").isTrue();
        assertThat(PRIVATE_VALUE.equals(MDC.get("private-context"))).as("Calling thread retains its unrelated MDC").isTrue();
        assertEventCount(1);
        assertEventMdcEquals(appender.list.getFirst(), Map.of(Diagnostics.REQUEST_ID, id));
        assertNoPrivateValueOrThrowable();
    }

    @Test
    void invalidExplicitCorrelationIsNotInstalledAndPreviousCorrelationIsRestored() {
        String previous = UUID.randomUUID().toString();
        MDC.put(Diagnostics.REQUEST_ID, previous);
        try (var ignored = Diagnostics.withCorrelation(PRIVATE_VALUE + "\r\nforged=true")) {
            assertThat(Diagnostics.correlationId() == null).as("Malformed correlation is not installed").isTrue();
            Diagnostics.transaction(TransactionDiagnostics.Operation.USER_CREATE, Diagnostics.Completion.NOT_OBSERVED);
        }
        assertThat(previous.equals(Diagnostics.correlationId())).as("The previous correlation is restored").isTrue();
        assertEventMdcEquals(appender.list.getFirst(), Map.of());
        assertNoPrivateValueOrThrowable();
    }

    @Test
    void observationsAreWrittenOnlyAfterTheChainReleasesItsLockAndFirstSpecificReasonWins() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        AtomicInteger writes = new AtomicInteger();
        appender.beforeCapture = event -> {
            assertThat(lock.isLocked()).isFalse();
            writes.incrementAndGet();
        };
        filter.doFilter(new MockHttpServletRequest("GET", "/login/oauth2/code/auth0"),
                new MockHttpServletResponse(), (request, response) -> {
                    lock.lock();
                    try {
                        Diagnostics.mark(Diagnostics.Event.LOGIN_RESULT, Diagnostics.Reason.IDENTITY_RESOLUTION_UNAVAILABLE);
                        Diagnostics.mark(Diagnostics.Event.LOGIN_RESULT, Diagnostics.Reason.PROTOCOL_REJECTED);
                        Diagnostics.transaction(TransactionDiagnostics.Operation.USER_CREATE, Diagnostics.Completion.UNKNOWN);
                        assertThat(writes.get()).isZero();
                    }
                    finally {
                        lock.unlock();
                    }
                });
        assertThat(writes.get()).isEqualTo(3);
        assertMessageEquals(appender.list.getFirst(), "event=LOGIN_RESULT reason=IDENTITY_RESOLUTION_UNAVAILABLE");
    }

    @Test
    void perRequestTransactionObservationsAreBoundedAndReportTruncationOnce() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/me"), new MockHttpServletResponse(),
                (request, response) -> {
                    for (int index = 0; index < 100; index++) {
                        Diagnostics.transaction(TransactionDiagnostics.Operation.USER_CREATE, Diagnostics.Completion.COMMITTED);
                    }
                    assertEventCount(0);
                });
        assertEventCount(18);
        assertThat(appender.list.stream().filter(event -> event.getFormattedMessage().startsWith("event=DOMAIN_TRANSACTION")).count())
                .as("Only the bounded number of transaction events is emitted").isEqualTo(16);
        assertThat(appender.list.stream().filter(event -> event.getFormattedMessage()
                .equals("event=DIAGNOSTIC_LIMIT reason=REQUEST_TRANSACTION_LIMIT")).count())
                .as("Truncation emits exactly one fixed diagnostic").isEqualTo(1);
        assertThat(appender.list.get(16).getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
        assertNoRequestId();
    }

    static Stream<Arguments> completionCases() {
        return Stream.of(
                Arguments.of(TransactionSynchronization.STATUS_COMMITTED, false, Diagnostics.Completion.COMMITTED),
                Arguments.of(TransactionSynchronization.STATUS_ROLLED_BACK, false, Diagnostics.Completion.ROLLED_BACK),
                Arguments.of(TransactionSynchronization.STATUS_UNKNOWN, false, Diagnostics.Completion.UNKNOWN),
                Arguments.of(TransactionSynchronization.STATUS_COMMITTED, true, Diagnostics.Completion.UNKNOWN));
    }

    @ParameterizedTest
    @MethodSource("completionCases")
    void transactionReturnIsNotCommitAndSavepointRollbackRemainsConservative(int status, boolean savepointRollback,
                                                                          Diagnostics.Completion expected) {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        Object result = new Object();
        String id = UUID.randomUUID().toString();
        try (var ignored = Diagnostics.withCorrelation(id)) {
            assertThat(TransactionDiagnostics.returned(TransactionDiagnostics.Operation.PRODUCT_CREATE, result)).isSameAs(result);
        }
        assertEventCount(0);
        assertThat(TransactionSynchronizationManager.getSynchronizations().size()).isEqualTo(1);
        TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().getFirst();
        if (savepointRollback) {
            synchronization.savepointRollback(new Object());
            assertEventCount(0);
        }
        synchronization.afterCompletion(status);

        assertEventCount(1);
        assertMessageEquals(appender.list.getFirst(), "event=DOMAIN_TRANSACTION operation=PRODUCT_CREATE completion=" + expected);
        assertEventMdcEquals(appender.list.getFirst(), Map.of(Diagnostics.REQUEST_ID, id));
        assertNoRequestId();
    }

    @Test
    void rollbackStatusAfterBeforeCommitCannotProveDatabaseRollback() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        Object result = new Object();
        assertThat(TransactionDiagnostics.returned(TransactionDiagnostics.Operation.USER_CREATE, result)).isSameAs(result);
        TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().getFirst();
        synchronization.beforeCommit(false);
        assertEventCount(0);
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertEventCount(1);
        assertMessageEquals(appender.list.getFirst(), "event=DOMAIN_TRANSACTION operation=USER_CREATE completion=UNKNOWN");
        assertThat(appender.list.getFirst().getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
    }

    @Test
    void repeatedServiceReturnsRetainOnlyTheFiniteOperationSetAndLatestCorrelation() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        String latest = UUID.randomUUID().toString();
        for (int iteration = 0; iteration < 30; iteration++) {
            try (var ignored = Diagnostics.withCorrelation(iteration == 29 ? latest : UUID.randomUUID().toString())) {
                for (var operation : TransactionDiagnostics.Operation.values()) {
                    TransactionDiagnostics.returned(operation, null);
                }
            }
        }
        assertEventCount(0);
        assertThat(TransactionSynchronizationManager.getSynchronizations().size()).isEqualTo(1);
        TransactionSynchronizationManager.getSynchronizations().getFirst()
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertEventCount(TransactionDiagnostics.Operation.values().length);
        appender.list.forEach(event -> {
            assertThat(event.getFormattedMessage().endsWith("completion=ROLLED_BACK"))
                    .as("Every coalesced observation retains the rollback outcome").isTrue();
            assertEventMdcEquals(event, Map.of(Diagnostics.REQUEST_ID, latest));
        });
    }

    @Test
    void absentPhysicalTransactionCannotBeReportedAsCommitted() {
        Object result = new Object();
        assertThat(TransactionDiagnostics.returned(TransactionDiagnostics.Operation.DELTA_APPLY_OR_REPLAY, result)).isSameAs(result);
        TransactionSynchronizationManager.initSynchronization();
        assertThat(TransactionDiagnostics.returned(TransactionDiagnostics.Operation.PRODUCT_CREATE, result)).isSameAs(result);
        assertThat(TransactionSynchronizationManager.getSynchronizations().size()).isZero();
        assertEventCount(2);
        appender.list.forEach(event -> {
            assertThat(event.getFormattedMessage().endsWith("completion=NOT_OBSERVED"))
                    .as("No absent transaction is represented as observed").isTrue();
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
        });
    }

    @Test
    void loggingFailureDoesNotReplaceHttpResultOrPreventCorrelationCleanup() throws Exception {
        appender.beforeCapture = event -> { throw new IllegalStateException(PRIVATE_VALUE); };
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/me"), response,
                (request, output) -> response.setStatus(204));
        assertThat(response.getStatus()).isEqualTo(204);
        assertNoRequestId();
    }

    private void assertNoPrivateValueOrThrowable() {
        // Boolean assertions deliberately avoid including any synthetic private value in failure output.
        assertThat(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains(PRIVATE_VALUE)
                || event.getMDCPropertyMap().values().stream().anyMatch(value -> value.contains(PRIVATE_VALUE))))
                .as("Diagnostic text and MDC do not disclose synthetic private input").isTrue();
        assertNoThrowableProxy();
    }

    private void assertEventCount(int expected) {
        assertThat(appender.list.size()).as("The diagnostic event count is exact").isEqualTo(expected);
    }

    private static void assertMessageEquals(ILoggingEvent event, String expected) {
        assertThat(expected.equals(event.getFormattedMessage())).as("The diagnostic message matches the exact safe contract").isTrue();
    }

    private static void assertMessageContains(ILoggingEvent event, String... expected) {
        assertThat(java.util.Arrays.stream(expected).allMatch(value -> event.getFormattedMessage().contains(value)))
                .as("The diagnostic message contains every required safe field").isTrue();
    }

    private static void assertEventMdcEquals(ILoggingEvent event, Map<String, String> expected) {
        assertThat(expected.equals(event.getMDCPropertyMap())).as("Event MDC matches the exact allowed context").isTrue();
    }

    private void assertNoThrowableProxy() {
        assertThat(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null))
                .as("No diagnostic event carries a throwable proxy").isTrue();
    }

    private static void assertNoRequestId() {
        assertThat(MDC.get(Diagnostics.REQUEST_ID) == null).as("The request correlation is cleared").isTrue();
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        }
        catch (IllegalArgumentException | NullPointerException malformed) {
            return false;
        }
    }

    private static final class CapturingAppender extends ListAppender<ILoggingEvent> {
        private Consumer<ILoggingEvent> beforeCapture = event -> { };

        private CapturingAppender() {
            list = new CopyOnWriteArrayList<>();
        }

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            beforeCapture.accept(event);
            super.append(event);
        }
    }

    private static final class ClassifiedException extends RuntimeException implements Diagnostics.ClassifiedFailure {
        private final Diagnostics.Reason reason;

        private ClassifiedException(Diagnostics.Reason reason) {
            super(PRIVATE_VALUE, new SQLException(PRIVATE_VALUE));
            this.reason = reason;
        }

        @Override
        public Diagnostics.Reason diagnosticReason() {
            return reason;
        }
    }
}
