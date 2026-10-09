package com.creastrix.platform.authentication;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/** Disposable single-instance feasibility prototype; not integrated production architecture.
 * The monitor guards memory only. In particular it never encloses a Servlet call or callback.
 * Authoritative time is sampled from the trusted, nonblocking Clock after memory-lock acquisition.
 */
final class BrowserSessionContextRegistry implements AutoCloseable {
    static final String COOKIE_NAME = "CREASTRIX_Q";
    static final Duration HARD_LIFETIME = Duration.ofHours(8);
    static final int MAX_CONTEXTS = 64;
    static final int MAX_REFERENCES = 128;
    static final int MAX_MEMBERS = 8;
    static final int MAX_CLEANUP_CLAIMS = 8;

    private final ReentrantLock memory = new ReentrantLock();
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Context> contexts = new LinkedHashMap<>();
    private int references;
    private int cleanupClaims;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor cleanupWorkers = new ThreadPoolExecutor(0, MAX_CLEANUP_CLAIMS,
            30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
                Thread worker = new Thread(task, "cookie-context-cleanup");
                worker.setDaemon(true);
                return worker;
            }, new ThreadPoolExecutor.AbortPolicy());

    interface PairingObserver { void afterReservation(Stamp stamp); }
    volatile PairingObserver pairingObserver = stamp -> { };

    BrowserSessionContextRegistry(Clock clock) { this.clock = clock; }

    record Stamp(String contextId, long generation, String logicalSessionToken) {
        @Override public String toString() { return "Stamp[redacted]"; }
    }

    static final class Binding {
        final BrowserSessionContextRegistry registry;
        final Stamp stamp;
        final AtomicBoolean dead = new AtomicBoolean();
        Binding(BrowserSessionContextRegistry registry, Stamp stamp) {
            this.registry = registry;
            this.stamp = stamp;
        }
    }

    static final class Owner {
        final Stamp stamp;
        private final String nonce;
        private final Instant deadline;
        private boolean publishing;
        Owner(Stamp stamp, String nonce, Instant deadline) {
            this.stamp = stamp;
            this.nonce = nonce;
            this.deadline = deadline;
        }
        @Override public String toString() { return "Owner[redacted]"; }
    }

    private static final class Member {
        final Binding binding;
        final HttpSession session;
        boolean paired;
        final AtomicReference<Pairing> pairing = new AtomicReference<>(Pairing.PENDING);
        final Instant reservationDeadline;
        boolean retired;
        boolean claimed;
        final AtomicBoolean cleanupFinished = new AtomicBoolean();
        final AtomicBoolean cleanupCompleted = new AtomicBoolean();
        final AtomicBoolean callerReleased = new AtomicBoolean();
        Member(Binding binding, HttpSession session, Instant now) {
            this.binding = binding;
            this.session = session;
            this.reservationDeadline = now.plus(AuthenticationProperties.FLOW_LIFETIME);
        }
    }

    private enum Pairing { PENDING, OWNED, ABANDONED }

    private static final class Context {
        final Instant expiresAt;
        final Map<String, Member> members = new LinkedHashMap<>();
        long generation;
        boolean issued;
        boolean retired;
        String accepted;
        Owner owner;
        Context(Instant expiresAt) { this.expiresAt = expiresAt; }
    }

    record Counts(int contexts, int references, int retiredReferences, int owners, int cleanupClaims) {}

    /** Reads the actual Cookie fields, not first/last-value Servlet cookie selection. */
    static String cookie(HttpServletRequest request) {
        String selected = null;
        var headers = request.getHeaders("Cookie");
        while (headers != null && headers.hasMoreElements()) {
            for (String part : headers.nextElement().split(";", -1)) {
                String value = part.trim();
                int equals = value.indexOf('=');
                String name = equals < 0 ? value : value.substring(0, equals).trim();
                if (!COOKIE_NAME.equals(name)) continue;
                if (selected != null || equals < 0) return null;
                selected = value.substring(equals + 1);
                if (selected.length() == 45 && selected.startsWith("\"") && selected.endsWith("\"")) {
                    selected = selected.substring(1, 44);
                }
                if (!selected.matches("[A-Za-z0-9_-]{43}")) return null;
            }
        }
        return selected;
    }

    static boolean cookieSupplied(HttpServletRequest request) {
        var headers = request.getHeaders("Cookie");
        while (headers != null && headers.hasMoreElements()) {
            for (String part : headers.nextElement().split(";", -1)) {
                String value = part.trim();
                int equals = value.indexOf('=');
                if (COOKIE_NAME.equals((equals < 0 ? value : value.substring(0, equals)).trim())) return true;
            }
        }
        return false;
    }

    Stamp capture(HttpSession session) {
        var coordinator = AuthenticationAttemptCoordinator.existing(session);
        Binding binding = coordinator == null ? null : coordinator.contextBinding();
        return binding != null && binding.registry == this ? binding.stamp : null;
    }

    /** The only return value that authorizes initial Set-Q. A lost initial response is not reissued. */
    String bootstrap(HttpSession session, AuthenticationAttemptCoordinator coordinator) {
        return bootstrap(session, coordinator, null);
    }

    /** Caller must prove no resolved session and no incoming SID before CSRF creates this anonymous session.
     * Unknown/expired is established here, never inferred from a rejected attachment or unavailable registry.
     */
    String recoveryBootstrap(String presentedQ, HttpSession session, AuthenticationAttemptCoordinator coordinator) {
        if (closed.get()) throw new Capacity();
        if (presentedQ == null || !presentedQ.matches("[A-Za-z0-9_-]{43}")) return null;
        return bootstrap(session, coordinator, presentedQ);
    }

    private String bootstrap(HttpSession session, AuthenticationAttemptCoordinator coordinator, String unavailableQ) {
        if (closed.get()) throw new Capacity();
        if (coordinator.contextBinding() != null || !coordinator.live(session) || coordinator.authenticated(session)) {
            return null;
        }
        String q = token();
        Binding binding = new Binding(this, new Stamp(q, 0, token()));
        Member member;
        lockMemory(); try {
            Instant now = clock.instant();
            if (closed.get()) throw new Capacity();
            if (unavailableQ != null) {
                Context supplied = contexts.get(unavailableQ);
                // Any unexpired record, even pending or retired, prevents recovery issuance.
                if (supplied != null && now.isBefore(supplied.expiresAt)) return null;
            }
            sweep(now);
            if (contexts.size() >= MAX_CONTEXTS || references >= MAX_REFERENCES
                    || contexts.containsKey(q) || q.equals(unavailableQ)) throw new Capacity();
            member = new Member(binding, session, now);
            var context = new Context(now.plus(HARD_LIFETIME));
            context.members.put(binding.stamp.logicalSessionToken(), member);
            contexts.put(q, context);
            references++;
        } finally { memory.unlock(); }
        // This one-time CAS is deliberately outside the registry monitor and adds no session lock.
        boolean paired = pair(member, coordinator);
        if (!paired) binding.dead.set(true);
        boolean committed = false;
        try {
            lockMemory();
        }
        catch (Capacity unavailable) {
            binding.dead.set(true);
            throw unavailable;
        }
        try {
            Instant committedAt = clock.instant();
            sweep(committedAt);
            Context context = contexts.get(q);
            if (closed.get() || !paired || binding.dead.get() || context == null || context.retired || member.retired
                    || context.generation != binding.stamp.generation()
                    || context.members.get(binding.stamp.logicalSessionToken()) != member) {
                if (context != null) context.retired = true;
                member.retired = true;
                return null;
            }
            member.paired = true;
            context.issued = true;
            committed = true;
            return q;
        } finally {
            if (!committed) binding.dead.set(true);
            memory.unlock();
        }
    }

    boolean attachAnonymous(String q, HttpSession session, AuthenticationAttemptCoordinator coordinator) {
        if (closed.get()) return false;
        Binding existing = coordinator.contextBinding();
        if (existing != null) return existing.registry == this && echo(q, existing.stamp);
        if (!coordinator.live(session) || coordinator.authenticated(session)) return false;
        String logical = token();
        Member member;
        lockMemory(); try {
            Instant now = clock.instant();
            sweep(now);
            Context context = contexts.get(q);
            if (context == null || context.retired || !context.issued) return false;
            if (context.members.size() >= MAX_MEMBERS || references >= MAX_REFERENCES || context.members.containsKey(logical)) {
                throw new Capacity();
            }
            var binding = new Binding(this, new Stamp(q, context.generation, logical));
            member = new Member(binding, session, now);
            context.members.put(logical, member);
            references++;
        } finally { memory.unlock(); }
        boolean paired = pair(member, coordinator);
        if (!paired) member.binding.dead.set(true);
        try { lockMemory(); }
        catch (Capacity unavailable) {
            member.binding.dead.set(true);
            throw unavailable;
        }
        try {
            Instant committedAt = clock.instant();
            sweep(committedAt);
            Context context = contexts.get(q);
            if (!paired || member.binding.dead.get() || context == null || context.retired || member.retired
                    || context.generation != member.binding.stamp.generation()
                    || context.members.get(logical) != member) {
                member.retired = true;
                return false;
            }
            member.paired = true;
            return true;
        } finally { memory.unlock(); }
    }

    private boolean pair(Member member, AuthenticationAttemptCoordinator coordinator) {
        boolean paired = false;
        try {
            // A test observer may delay a real reservation, but never runs under coordination.
            pairingObserver.afterReservation(member.binding.stamp);
            paired = !member.binding.dead.get() && coordinator.bindContext(member.binding);
            return paired;
        }
        finally {
            member.pairing.set(paired ? Pairing.OWNED : Pairing.ABANDONED);
            if (!paired) member.binding.dead.set(true);
        }
    }

    boolean echo(String q, Stamp stamp) {
        lockMemory(); try {
            Instant now = clock.instant();
            sweep(now);
            return q != null && stamp != null && q.equals(stamp.contextId()) && liveMember(stamp) != null;
        } finally { memory.unlock(); }
    }

    boolean canStart(String q, Stamp stamp) {
        lockMemory(); try {
            Instant now = clock.instant();
            sweep(now);
            if (q == null || stamp == null || !q.equals(stamp.contextId()) || liveMember(stamp) == null) return false;
            Context context = contexts.get(q);
            return context.accepted == null;
        } finally { memory.unlock(); }
    }

    Owner beginOwner(Stamp stamp) {
        String nonce = token();
        lockMemory(); try {
            Instant now = clock.instant();
            sweep(now);
            if (liveMember(stamp) == null) return null;
            Context context = contexts.get(stamp.contextId());
            if (context.accepted != null || context.owner != null) return null;
            Owner owner = new Owner(stamp, nonce, now.plus(AuthenticationProperties.FLOW_LIFETIME));
            context.owner = owner;
            return owner;
        } finally { memory.unlock(); }
    }

    boolean owns(Owner owner) {
        lockMemory(); try { sweep(clock.instant()); return exactOwner(owner); } finally { memory.unlock(); }
    }

    boolean reservePublication(Owner owner) {
        lockMemory(); try {
            Instant now = clock.instant();
            sweep(now);
            if (!exactOwner(owner) || owner.publishing) return false;
            owner.publishing = true;
            return true;
        } finally { memory.unlock(); }
    }

    boolean publish(Owner owner) {
        lockMemory(); try {
            Instant now = clock.instant();
            sweep(now);
            if (!exactOwner(owner) || !owner.publishing) return false;
            Context context = contexts.get(owner.stamp.contextId());
            if (context.accepted != null) return false;
            context.accepted = owner.stamp.logicalSessionToken();
            return true;
        } finally { memory.unlock(); }
    }

    void cancel(Owner owner) {
        if (owner == null) return;
        lockMemory(); try {
            Context context = contexts.get(owner.stamp.contextId());
            if (context != null && context.owner == owner) context.owner = null;
        } finally { memory.unlock(); }
    }

    boolean finalAdmit(String q, Stamp stamp) {
        lockMemory(); try {
            Instant now = clock.instant();
            sweep(now);
            if (q == null || stamp == null || !q.equals(stamp.contextId()) || liveMember(stamp) == null) return false;
            return stamp.logicalSessionToken().equals(contexts.get(q).accepted);
        } finally { memory.unlock(); }
    }

    boolean revoke(String q) {
        lockMemory(); try {
            Instant now = clock.instant();
            sweep(now);
            Context context = contexts.get(q);
            if (context == null || context.retired || !context.issued) return false;
            retireGeneration(context);
            return true;
        } finally { memory.unlock(); }
    }

    /** Captures <=8 exact old references. No queue, background thread, or coordination lock during invalidation. */
    void cleanup() {
        if (closed.get()) return;
        List<Member> claims = new ArrayList<>();
        lockMemory(); try {
            Instant now = clock.instant();
            sweep(now);
            for (Context context : contexts.values()) {
                for (Member member : context.members.values()) {
                    if (cleanupClaims == MAX_CLEANUP_CLAIMS) break;
                    if ((member.retired || member.binding.dead.get()) && !member.claimed
                            && member.pairing.get() != Pairing.PENDING) {
                        member.claimed = true;
                        member.callerReleased.set(false);
                        cleanupClaims++;
                        claims.add(member);
                    }
                }
            }
        } finally { memory.unlock(); }
        List<CompletableFuture<Void>> completions = new ArrayList<>();
        try {
          for (Member member : claims) {
            try {
                completions.add(CompletableFuture.runAsync(() -> cleanMember(member), cleanupWorkers));
            }
            catch (RejectedExecutionException notScheduled) {
                member.cleanupCompleted.set(false);
                member.cleanupFinished.set(true);
            }
          }
        // Caller waiting is bounded; stalled physical cleanup keeps its exact credit and worker slot.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
          for (var completion : completions) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try { completion.get(remaining, TimeUnit.NANOSECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            catch (TimeoutException timeout) { break; }
            catch (ExecutionException failed) { /* Exact unsuccessful claim remains charged for retry. */ }
          }
        }
        finally {
            // Captured lists are also retained resources: do not release credit while this caller holds them.
            claims.forEach(member -> member.callerReleased.set(true));
        }
    }

    private void cleanMember(Member member) {
        boolean completed = false;
        try {
            // A losing initial-pair CAS never owned this session. Do not destroy the winner.
            if (member.pairing.get() == Pairing.OWNED) member.session.invalidate();
            completed = true;
        }
        catch (IllegalStateException alreadyInvalid) { completed = true; }
        catch (RuntimeException cleanupFailed) { /* Still charged; a later explicit sweep may retry. */ }
        finally {
            if (completed) member.binding.dead.set(true);
            member.cleanupCompleted.set(completed);
            // Including Error: acknowledgement cannot block; a later memory sweep retries failure.
            member.cleanupFinished.set(true);
        }
    }

    int activeCleanupWorkers() { return cleanupWorkers.getActiveCount(); }
    int queuedCleanupTasks() { return cleanupWorkers.getQueue().size(); }

    @Override public void close() {
        closed.set(true);
        cleanupWorkers.shutdown();
        try {
            if (!cleanupWorkers.awaitTermination(100, TimeUnit.MILLISECONDS)) cleanupWorkers.shutdownNow();
        }
        catch (InterruptedException interrupted) {
            cleanupWorkers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    Counts counts() {
        lockMemory(); try {
            Instant now = clock.instant();
            sweep(now);
            int retired = 0;
            int owners = 0;
            for (Context context : contexts.values()) {
                if (context.owner != null) owners++;
                for (Member member : context.members.values()) if (member.retired || member.binding.dead.get()) retired++;
            }
            return new Counts(contexts.size(), references, retired, owners, cleanupClaims);
        } finally { memory.unlock(); }
    }

    private boolean exactOwner(Owner owner) {
        return owner != null && liveMember(owner.stamp) != null
                && contexts.get(owner.stamp.contextId()).owner == owner;
    }

    private Member liveMember(Stamp stamp) {
        if (stamp == null || closed.get()) return null;
        Context context = contexts.get(stamp.contextId());
        if (context == null || context.retired || !context.issued || context.generation != stamp.generation()) return null;
        Member member = context.members.get(stamp.logicalSessionToken());
        return member != null && member.binding.stamp.equals(stamp) && member.paired
                && !member.retired && !member.binding.dead.get() ? member : null;
    }

    private void sweep(Instant now) {
        for (Context context : contexts.values()) {
            var members = context.members.values().iterator();
            while (members.hasNext()) {
                Member member = members.next();
                if (!member.paired && !now.isBefore(member.reservationDeadline)) {
                    member.retired = true;
                    member.binding.dead.set(true);
                }
                if (member.claimed && member.cleanupFinished.get() && member.callerReleased.get()) {
                    member.claimed = false;
                    cleanupClaims--;
                    member.cleanupFinished.set(false);
                    if (member.cleanupCompleted.get()) {
                        members.remove();
                        references--;
                    }
                }
            }
            if (!context.issued && context.members.values().stream().allMatch(member -> member.binding.dead.get())) {
                context.retired = true;
            }
            if (!now.isBefore(context.expiresAt)) {
                context.retired = true;
                context.owner = null;
                context.accepted = null;
                context.members.values().forEach(member -> member.retired = true);
            }
            else if (context.accepted != null) {
                Member accepted = context.members.get(context.accepted);
                if (accepted == null || accepted.binding.dead.get()) retireGeneration(context);
            }
            if (context.owner != null && (!now.isBefore(context.owner.deadline)
                    || liveMember(context.owner.stamp) == null)) context.owner = null;
        }
        contexts.values().removeIf(context -> context.retired && context.members.isEmpty());
    }

    private void retireGeneration(Context context) {
        context.generation++;
        context.accepted = null;
        context.owner = null;
        context.members.values().forEach(member -> member.retired = true);
    }

    private void lockMemory() {
        try {
            if (memory.tryLock(100, TimeUnit.MILLISECONDS)) return;
        }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        throw new Capacity();
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static final class Capacity extends RuntimeException {
        Capacity() { super("Authentication context capacity unavailable"); }
    }
}
