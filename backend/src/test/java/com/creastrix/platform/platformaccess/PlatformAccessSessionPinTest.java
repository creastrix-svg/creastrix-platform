package com.creastrix.platform.platformaccess;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

import com.creastrix.platform.platformaccess.application.*;
import com.creastrix.platform.platformaccess.application.PlatformAccessOperations.*;
import com.creastrix.platform.platformaccess.application.TrustedPlatformAccessFacts.Snapshot;
import com.creastrix.platform.platformaccess.application.port.PlatformAccessRepository.*;
import com.creastrix.platform.platformaccess.domain.PlatformAccessContext.*;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant;
import com.creastrix.platform.platformaccess.domain.PlatformAccessGrant.*;
import com.creastrix.platform.platformaccess.domain.PlatformRole;
import com.creastrix.platform.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Service-level admission decisions only: no external identity, JDBC storage or commit proof. */
class PlatformAccessSessionPinTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final UUID actor = UUID.randomUUID(), target = UUID.randomUUID(), grant = UUID.randomUUID();
    private final Intent read = new Intent(actor, UUID.randomUUID(), Kind.USER_SECURITY_READ, target,
            null, ReadReason.USER_REQUESTED_SUPPORT, null);
    private final Snapshot a = sessionFacts(UUID.randomUUID()), b = sessionFacts(UUID.randomUUID());
    private final Session session = mock(Session.class);
    private final TrustedPlatformAccessFacts facts = mock(TrustedPlatformAccessFacts.class);
    private PlatformAccessService service;

    @BeforeEach
    void syntheticAuthority() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        var actorFact = new UserFact(actor, UserStatus.ACTIVE, 1);
        var support = new PlatformAccessGrant(grant, actor, PlatformRole.SUPPORT_READ, 1, 1, 1, State.ACTIVE,
                now.minusSeconds(60), new Validity(ValidityKind.BOUNDED, now.plusSeconds(60)),
                new Scope(ScopeKind.EXACT_USERS, List.of(target)));
        var authority = new Authority(actorFact, new UserFact(target, UserStatus.ACTIVE, 1), actorFact,
                support, null, List.of(grant), List.of(), Set.of(actor, target));
        when(session.lockAuthority(eq(actor), eq(read), anyBoolean())).thenReturn(authority);
        when(session.refreshAuthority()).thenReturn(authority);
        when(session.now()).thenReturn(now);
        service = new PlatformAccessService(new OwnedPlatformAccessTransactions(source),
                (ignored, deadline) -> session, facts);
    }

    private Snapshot sessionFacts(UUID generation) {
        var stamp = new Stamp(actor, AccountEligibilityGeneration.encode(1), generation, grant, 1);
        return new Snapshot(actor, generation, new Assurance(Verification.VERIFIED_MFA, stamp, now, now, now, null));
    }

    @ParameterizedTest(name = "Generation changes at first gate: {0}")
    @ValueSource(booleans = {true, false})
    void differentGenerationIsDeniedBeforeConsideringAnExecutionResult(boolean firstGate) throws Exception {
        // No executable record exists. A pin failure must be an admission denial, not an intent lookup result.
        when(facts.current()).thenReturn(a, firstGate ? b : a, b);
        assertThat(service.readUserSecurity(read)).isEqualTo(new Denied<>());
        verify(session, times(firstGate ? 0 : 1)).lockIntent(actor, read.operationId());
        var audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(session).writeAudit(audit.capture());
        assertThat(audit.getValue().kind()).isEqualTo(AuditKind.ACCESS_DENIED);
        assertThat(audit.getValue().reason()).isEqualTo(AuditReason.STAMP_MISMATCH);
        verify(session, never()).writeOutcome(any(), any());
    }

    @Test
    void validRegistrationDoesNotCarryAdmissionAcrossSessions() throws Exception {
        when(session.lockIntent(actor, read.operationId())).thenReturn(
                new RegisteredIntent(read, now, UUID.randomUUID(), grant, 1));
        // Stop at the history boundary even if a future implementation omits the final gate.
        when(session.outcome(actor, read.operationId())).thenThrow(new AssertionError("Unexpected history lookup"));
        when(facts.current()).thenReturn(a, a, b);
        assertThat(service.readUserSecurity(read)).isEqualTo(new Denied<>());
        verify(session, never()).outcome(any(), any());
        verify(session, never()).writeAudit(argThat(event -> event.kind() == AuditKind.READ_ADMITTED));
        verify(session, never()).writeOutcome(any(), any());
    }

    @Test
    void sameGenerationStillNeedsCurrentAssuranceAtFinalGate() throws Exception {
        when(facts.current()).thenReturn(a, a, new Snapshot(actor, a.sessionGeneration(), null));
        assertThat(service.readUserSecurity(read)).isEqualTo(new Denied<>());
        var audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(session).writeAudit(audit.capture());
        assertThat(audit.getValue().reason()).isEqualTo(AuditReason.ASSURANCE_REQUIRED);
        verify(session, never()).writeOutcome(any(), any());
    }

    @Test
    void stableAAdmitsReadAndFreshBAdmitsOnlyItsOwnReceiptRequest() throws Exception {
        when(session.lockIntent(actor, read.operationId())).thenReturn(
                new RegisteredIntent(read, now, UUID.randomUUID(), grant, 1));
        when(facts.current()).thenReturn(a);
        assertThat(service.readUserSecurity(read)).isEqualTo(new Success<>(new UserSecurity(target, UserStatus.ACTIVE)));
        var outcome = ArgumentCaptor.forClass(StoredOutcome.class);
        verify(session).writeOutcome(eq(read), outcome.capture());
        assertThat(outcome.getValue().kind()).isEqualTo(OutcomeKind.READ_ADMITTED);

        when(session.outcome(actor, read.operationId())).thenReturn(outcome.getValue());
        when(facts.current()).thenReturn(b);
        assertThat(service.readUserSecurityReceipt(read, ReadReason.USER_REQUESTED_SUPPORT)).isEqualTo(
                new Success<>(new Receipt(read, now, now, OutcomeKind.READ_ADMITTED, null, null, null, null)));
        verify(session, times(1)).writeOutcome(any(), any());
        var audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(session, times(2)).writeAudit(audit.capture());
        assertThat(audit.getAllValues()).extracting(AuditEvent::projection)
                .containsExactly(AuditProjection.USER_SECURITY, AuditProjection.OPERATION_RECEIPT);
    }
}
