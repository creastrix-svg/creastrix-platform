package com.creastrix.platform.platformaccess.application;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.creastrix.platform.observability.Diagnostics.Completion;
import com.creastrix.platform.observability.Diagnostics.Reason;
import com.creastrix.platform.observability.TransactionDiagnostics.Operation;
import com.creastrix.platform.platformaccess.application.OwnedPlatformAccessTransactions.Phase;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.*;
import com.creastrix.platform.platformaccess.application.port.PlatformAccessRepository;
import com.creastrix.platform.platformaccess.application.port.PlatformAccessRepository.Authority;
import com.creastrix.platform.platformaccess.application.port.PlatformAccessRepository.Session;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.*;
import com.creastrix.platform.platformaccess.domain.PlatformAccessDecision;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant.State;
import com.creastrix.platform.platformaccess.domain.PlatformAccessPolicy;
import com.creastrix.platform.platformaccess.domain.PlatformPermission;

/**
 * Six explicitly constructed, unwired platform-access workflow entry points.
 *
 * <p>Registration is an independently committed immutable binding, not execution
 * or an authorization lease. Every call obtains current database and trusted
 * session facts; the caller's supplied target is authorized before any operation
 * lookup. Authority rows are locked in the repository's canonical order and held
 * through completion. A second fresh gate follows every intent/unique-slot wait.
 * No operation identity, historical result or pure-policy ALLOW is a credential.
 *
 * <p>Payloads remain private until required audit/outcome and an acknowledged
 * physical commit complete. UNKNOWN never becomes a rollback receipt or fresh-ID
 * retry. Admission is not response delivery. These local synthetic-adapter proofs
 * provide neither live MFA/session verification nor an HTTP/employee API.
 */
public final class PlatformAccessService {
    private final OwnedPlatformAccessTransactions transactions;
    private final PlatformAccessRepository repository;
    private final TrustedPlatformAccessFacts facts;
    private final PlatformAccessPolicy policy = new PlatformAccessPolicy();

    public PlatformAccessService(OwnedPlatformAccessTransactions transactions,
                                 PlatformAccessRepository repository, TrustedPlatformAccessFacts facts) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.facts = Objects.requireNonNull(facts, "facts");
    }

    public Result<RegistrationAck> registerUserSecurityRead(Intent intent) {
        return register(intent, Kind.USER_SECURITY_READ);
    }

    public Result<RegistrationAck> registerSupportRevoke(Intent intent) {
        return register(intent, Kind.SUPPORT_REVOKE);
    }

    public Result<UserSecurity> readUserSecurity(Intent intent) {
        return attempt(intent, Kind.USER_SECURITY_READ, Mode.EXECUTE, intent == null ? null : intent.readReason(),
                Operation.PLATFORM_USER_SECURITY_READ, (session, initial, context) -> {
                    RegisteredIntent retained = executable(session, intent);
                    Gate gate = refresh(session, context);
                    matchingExecution(session, intent, retained);
                    UUID audit = UUID.randomUUID();
                    writeAudit(session, successAudit(context, gate, intent, audit, AuditKind.READ_ADMITTED,
                            AuditProjection.USER_SECURITY, null, null, null, null, null));
                    session.writeOutcome(intent, new StoredOutcome(OutcomeKind.READ_ADMITTED, gate.at(),
                            gate.authority().actorGrant().id(), gate.authority().actorGrant().revision(), audit,
                            null, null, null, null));
                    return new UserSecurity(gate.authority().target().id(), gate.authority().target().status());
                });
    }

    public Result<Receipt> revokeSupportGrant(Intent intent) {
        return attempt(intent, Kind.SUPPORT_REVOKE, Mode.EXECUTE, null,
                Operation.PLATFORM_SUPPORT_REVOKE, (session, initial, context) -> {
                    RegisteredIntent retained = executable(session, intent);
                    Gate gate = refresh(session, context);
                    matchingExecution(session, intent, retained);
                    PlatformAccessGrant target = gate.authority().targetGrant();
                    long afterRevision = Math.addExact(target.revision(), 1);
                    if (session.revoke(intent, context.actor(), gate.at()) != 1) {
                        throw denied(PlatformAccessDecision.Code.STALE_REVISION);
                    }
                    UUID audit = UUID.randomUUID();
                    writeAudit(session, successAudit(context, gate, intent, audit, AuditKind.GRANT_REVOKED,
                            null, target.state(), target.revision(), State.REVOKED, afterRevision, null));
                    StoredOutcome outcome = new StoredOutcome(OutcomeKind.REVOKED, gate.at(),
                            gate.authority().actorGrant().id(), gate.authority().actorGrant().revision(), audit,
                            target.state(), target.revision(), State.REVOKED, afterRevision);
                    session.writeOutcome(intent, outcome);
                    return receipt(retained, outcome);
                });
    }

    public Result<Receipt> readUserSecurityReceipt(Intent intent, ReadReason currentReason) {
        return readReceipt(intent, Kind.USER_SECURITY_READ, currentReason);
    }

    public Result<Receipt> readRevokeReceipt(Intent intent) {
        return readReceipt(intent, Kind.SUPPORT_REVOKE, ReadReason.ACCESS_REVIEW);
    }

    private Result<RegistrationAck> register(Intent intent, Kind kind) {
        return attempt(intent, kind, Mode.REGISTER, intent == null ? null : intent.readReason(),
                Operation.PLATFORM_INTENT_REGISTER, (session, initial, context) -> {
                    PlatformAccessGrant actorGrant = initial.authority().actorGrant();
                    RegisteredIntent proposed = new RegisteredIntent(intent, initial.at(), context.correlation(),
                            actorGrant.id(), actorGrant.revision());
                    boolean inserted = session.insertIntentIfAbsent(proposed);
                    RegisteredIntent retained = session.lockIntent(intent.initiatorUserId(), intent.operationId());
                    Gate gate = refresh(session, context);
                    if (retained == null) { throw new SQLException("Registered intent was not retained.", "PA002"); }
                    if (!retained.intent().equals(intent)) {
                        throw rejected(Code.ID_UNAVAILABLE, AuditReason.ID_UNAVAILABLE);
                    }
                    // A provisional insert can wait; final denial rolls it back. The immutable
                    // registration timestamp denotes recording, not acknowledgement or delivery.
                    Gate auditGate = inserted ? new Gate(gate.authority(), retained.registeredAt()) : gate;
                    writeAudit(session, successAudit(context, auditGate, intent, UUID.randomUUID(),
                            inserted ? AuditKind.INTENT_RECORDED : AuditKind.READ_ADMITTED,
                            inserted ? (kind == Kind.USER_SECURITY_READ ? AuditProjection.USER_SECURITY : null)
                                    : AuditProjection.OPERATION_RECEIPT,
                            null, null, null, null, null));
                    return new RegistrationAck(retained.intent(), retained.registeredAt());
                });
    }

    private Result<Receipt> readReceipt(Intent intent, Kind kind, ReadReason currentReason) {
        return attempt(intent, kind, Mode.RECEIPT, currentReason, Operation.PLATFORM_OPERATION_RECEIPT,
                (session, initial, context) -> {
                    // Other-initiator READ never reads that history. A missing historical User
                    // is not an early existence oracle: the current target gate already ran.
                    if (kind == Kind.USER_SECURITY_READ && !context.actor().equals(intent.initiatorUserId())
                            || initial.authority().historicalInitiator() == null) {
                        throw rejected(Code.NO_DISCLOSABLE_RECEIPT, AuditReason.HISTORICAL_INITIATOR_UNAVAILABLE);
                    }
                    RegisteredIntent retained = session.lockIntent(intent.initiatorUserId(), intent.operationId());
                    Gate gate = refresh(session, context);
                    if (retained == null || !retained.intent().equals(intent)) {
                        throw rejected(Code.NO_DISCLOSABLE_RECEIPT,
                                retained == null ? AuditReason.INTENT_ABSENT : AuditReason.INTENT_MISMATCH);
                    }
                    StoredOutcome outcome = session.outcome(intent.initiatorUserId(), intent.operationId());
                    if (outcome == null) {
                        throw rejected(Code.NO_DISCLOSABLE_RECEIPT, AuditReason.INTENT_UNRESOLVED);
                    }
                    writeAudit(session, successAudit(context, gate, intent, UUID.randomUUID(),
                            AuditKind.READ_ADMITTED, AuditProjection.OPERATION_RECEIPT,
                            null, null, null, null, outcome.auditEventId()));
                    return receipt(retained, outcome);
                });
    }

    private RegisteredIntent executable(Session session, Intent intent) throws SQLException {
        return session.lockIntent(intent.initiatorUserId(), intent.operationId());
    }

    private void matchingExecution(Session session, Intent intent, RegisteredIntent retained) throws SQLException {
        if (retained == null || !retained.intent().equals(intent)) {
            throw rejected(Code.NO_EXECUTABLE_INTENT,
                    retained == null ? AuditReason.INTENT_ABSENT : AuditReason.INTENT_MISMATCH);
        }
        if (session.outcome(intent.initiatorUserId(), intent.operationId()) != null) {
            throw rejected(Code.TERMINAL_RECEIPT_REQUIRED, AuditReason.TERMINAL_RECEIPT_REQUIRED);
        }
    }

    private <T> Result<T> attempt(Intent intent, Kind expected, Mode mode, ReadReason readReason,
                                   Operation operation, Work<T> work) {
        // Do not call any adapter or sink, even for malformed input, under foreign locks.
        if (transactions.ambientPresent()) { return new Failure<>(Code.AMBIENT_TRANSACTION); }
        if (intent == null || !intent.valid() || intent.kind() != expected) {
            return new Failure<>(Code.INVALID_INPUT);
        }
        TrustedPlatformAccessFacts.Snapshot initiating;
        try { initiating = facts.current(); }
        catch (RuntimeException failure) { return new Failure<>(Code.UNEXPECTED_FAILURE); }
        if (initiating == null || initiating.userId() == null) { return new Denied<>(); }
        Attempt context = new Attempt(initiating.userId(), intent, mode, readReason,
                UUID.randomUUID(), UUID.randomUUID(), new AtomicReference<>());
        Phase<T> primary = transactions.primary(operation, (connection, deadline) -> {
            if (mode != Mode.RECEIPT && !context.actor().equals(intent.initiatorUserId())) {
                throw denied(PlatformAccessDecision.Code.INCONSISTENT_FACTS);
            }
            Session session = repository.session(connection, deadline);
            Authority authority = session.lockAuthority(context.actor(), intent, mode == Mode.RECEIPT);
            context.observed().set(authority);
            Gate first = authorize(authority, session.now(), context);
            return work.run(session, first, context);
        });
        if (primary.succeeded()) { return new Success<>(primary.value()); }
        if (primary.ambient()) { return new Failure<>(Code.AMBIENT_TRANSACTION); }
        if (!primary.cleanupComplete() || primary.completion() == Completion.UNKNOWN) {
            return new Failure<>(Code.COMMIT_UNKNOWN);
        }
        Result<T> result;
        AuditReason auditReason;
        boolean denial;
        if (primary.failure() instanceof Rejection rejection) {
            result = rejection.result();
            auditReason = rejection.auditReason;
            denial = rejection.diagnosticReason() == Reason.ADMISSION_DENIED;
        } else {
            Code code = technicalCode(primary.reason());
            result = new Failure<>(code);
            auditReason = AuditReason.valueOf(code.name());
            denial = false;
        }
        if (primary.completion() == Completion.ROLLED_BACK) {
            // Fallback is deliberately outside all primary locks. It never retries the
            // operation, manufactures a terminal outcome, or replaces deny/unavailable.
            transactions.fallback((connection, deadline) -> {
                Session session = repository.session(connection, deadline);
                session.writeAudit(attemptAudit(context, session.now(), denial, auditReason));
                return Boolean.TRUE;
            });
        }
        return result;
    }

    private Gate refresh(Session session, Attempt context) throws SQLException {
        Authority authority = session.refreshAuthority();
        context.observed().set(authority);
        return authorize(authority, session.now(), context);
    }

    private Gate authorize(Authority a, Instant now, Attempt context) {
        TrustedPlatformAccessFacts.Snapshot current = facts.current();
        if (current == null || !context.actor().equals(current.userId())) {
            throw denied(PlatformAccessDecision.Code.STAMP_MISMATCH);
        }
        Intent intent = context.intent();
        if (a.target() == null || !intent.targetUserId().equals(a.target().id())
                || intent.kind() == Kind.SUPPORT_REVOKE && (a.targetGrant() == null
                    || !intent.targetUserId().equals(a.targetGrant().recipientId())
                    || !intent.targetGrantId().equals(a.targetGrant().id()))) {
            throw denied(PlatformAccessDecision.Code.RESOURCE_MISMATCH);
        }
        boolean mutation = intent.kind() == Kind.SUPPORT_REVOKE && context.mode() != Mode.RECEIPT;
        PlatformAccessContext.Actor actor = a.actor() == null ? null : new PlatformAccessContext.Actor(
                a.actor().id(), a.actor().status(), AccountEligibilityGeneration.encode(a.actor().generation()),
                current.sessionGeneration());
        Resource resource = intent.kind() == Kind.USER_SECURITY_READ
                ? new UserResource(a.target().id(), a.target().status(), null)
                : new GrantResource(a.targetGrant(), a.target().status());
        Request request = new Request(intent.kind() == Kind.USER_SECURITY_READ ? PlatformPermission.USER_SECURITY_READ
                : mutation ? PlatformPermission.STAFF_GRANT_SUSPEND_REVOKE : PlatformPermission.STAFF_GRANTS_READ,
                mutation ? Action.REVOKE : Action.READ,
                mutation ? null : intent.kind() == Kind.SUPPORT_REVOKE ? ReadReason.ACCESS_REVIEW : context.readReason());
        Change change = mutation ? new Change(new Slot(intent.targetUserId(), a.targetSlot()),
                intent.expectedRevision(), null, null, null) : null;
        PlatformAccessContext c = new PlatformAccessContext(now, actor, a.actorGrant(),
                new Slot(context.actor(), a.slot()), current.assurance(), request, resource, change, a.existingUsers());
        requireAllow(policy.evaluate(c));
        if (mutation && context.mode() == Mode.REGISTER) {
            // Minimal registration acknowledgement is also a read of grant metadata;
            // this extra gate never drops the mutation step-up gate checked above.
            requireAllow(policy.evaluate(new PlatformAccessContext(now, actor, a.actorGrant(), c.actorSlot(),
                    current.assurance(), new Request(PlatformPermission.STAFF_GRANTS_READ, Action.READ,
                    ReadReason.ACCESS_REVIEW), resource, null, a.existingUsers())));
        }
        return new Gate(a, now);
    }

    private static void requireAllow(PlatformAccessDecision decision) {
        if (!decision.allowed()) { throw denied(decision.code()); }
    }

    private static void writeAudit(Session session, AuditEvent event) {
        try { session.writeAudit(event); }
        catch (SQLException failure) {
            Rejection rejected = new Rejection(new Failure<>(Code.AUDIT_UNAVAILABLE),
                    AuditReason.AUDIT_UNAVAILABLE, Reason.DATABASE_UNAVAILABLE);
            rejected.initCause(failure);
            throw rejected;
        }
    }

    private static AuditEvent successAudit(Attempt context, Gate gate, Intent intent, UUID id,
                                            AuditKind kind, AuditProjection projection,
                                            State before, Long beforeRevision, State after, Long afterRevision,
                                            UUID linked) {
        PlatformAccessGrant actor = gate.authority().actorGrant();
        boolean receipt = projection == AuditProjection.OPERATION_RECEIPT;
        AuditReason reason = kind == AuditKind.GRANT_REVOKED ? null
                : intent.kind() == Kind.SUPPORT_REVOKE ? (receipt ? AuditReason.ACCESS_REVIEW : null)
                : AuditReason.valueOf((context.mode() == Mode.RECEIPT ? context.readReason() : intent.readReason()).name());
        return new AuditEvent(id, kind, gate.at(), context.correlation(), context.attempt(),
                context.actor(), actor.id(), actor.revision(), actor.role(), intent.targetUserId(), intent.targetGrantId(),
                TargetVerification.VERIFIED, intent.targetUserId(), intent.targetGrantId(), intent.initiatorUserId(),
                intent.operationId(), intent.kind(), permission(intent, receipt), projection, reason,
                kind == AuditKind.INTENT_RECORDED ? AuditOutcome.REGISTERED
                        : kind == AuditKind.GRANT_REVOKED ? AuditOutcome.COMMITTED : AuditOutcome.ADMITTED,
                before, beforeRevision, after, afterRevision, linked);
    }

    private static AuditEvent attemptAudit(Attempt context, Instant now, boolean denial, AuditReason reason) {
        Authority observed = context.observed().get();
        PlatformAccessGrant grant = observed == null ? null : observed.actorGrant();
        UUID actor = observed == null || observed.actor() == null ? null : observed.actor().id();
        return new AuditEvent(UUID.randomUUID(), denial ? AuditKind.ACCESS_DENIED : AuditKind.OPERATION_FAILED,
                now, context.correlation(), context.attempt(), actor, grant == null ? null : grant.id(),
                grant == null ? null : grant.revision(), grant == null ? null : grant.role(),
                context.intent().targetUserId(), context.intent().targetGrantId(), TargetVerification.SUPPLIED,
                null, null, null, null, context.intent().kind(),
                permission(context.intent(), context.mode() == Mode.RECEIPT), null, reason,
                denial ? AuditOutcome.DENIED : AuditOutcome.CONFIRMED_FAILED, null, null, null, null, null);
    }

    private static PlatformPermission permission(Intent intent, boolean receipt) {
        return intent.kind() == Kind.USER_SECURITY_READ ? PlatformPermission.USER_SECURITY_READ
                : receipt ? PlatformPermission.STAFF_GRANTS_READ : PlatformPermission.STAFF_GRANT_SUSPEND_REVOKE;
    }

    private static Receipt receipt(RegisteredIntent intent, StoredOutcome outcome) {
        return new Receipt(intent.intent(), intent.registeredAt(), outcome.admittedAt(), outcome.kind(),
                outcome.beforeState(), outcome.beforeRevision(), outcome.afterState(), outcome.afterRevision());
    }

    private static Code technicalCode(Reason reason) {
        if (reason == Reason.DEADLINE_EXCEEDED) { return Code.DEADLINE_EXCEEDED; }
        if (reason == Reason.INTERRUPTED) { return Code.INTERRUPTED; }
        if (reason == Reason.DATABASE_UNAVAILABLE) { return Code.DATABASE_UNAVAILABLE; }
        return Code.UNEXPECTED_FAILURE;
    }

    private static Rejection denied(PlatformAccessDecision.Code code) {
        return new Rejection(new Denied<>(), AuditReason.valueOf(code.name()), Reason.ADMISSION_DENIED);
    }

    private static Rejection rejected(Code code, AuditReason reason) {
        return new Rejection(new Failure<>(code), reason, Reason.ADMISSION_DENIED);
    }

    /** Internal only: no cause, policy code or history mismatch is put in public Result. */
    private static final class Rejection extends OwnedPlatformAccessTransactions.Rejected {
        private final Result<?> result;
        private final AuditReason auditReason;
        private Rejection(Result<?> result, AuditReason auditReason, Reason reason) {
            super(reason);
            this.result = result;
            this.auditReason = auditReason;
        }
        @SuppressWarnings("unchecked")
        private <T> Result<T> result() { return (Result<T>) result; }
    }

    private enum Mode { REGISTER, EXECUTE, RECEIPT }
    private record Gate(Authority authority, Instant at) { }
    private record Attempt(UUID actor, Intent intent, Mode mode, ReadReason readReason,
                           UUID correlation, UUID attempt, AtomicReference<Authority> observed) { }
    @FunctionalInterface
    private interface Work<T> { T run(Session session, Gate gate, Attempt context) throws Exception; }
}
