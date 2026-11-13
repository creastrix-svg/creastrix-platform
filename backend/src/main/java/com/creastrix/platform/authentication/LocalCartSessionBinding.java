package com.creastrix.platform.authentication;

import java.util.Collections;
import java.util.UUID;
import java.time.Instant;

import com.creastrix.platform.accountcart.LocalCartCommands;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;

/** Opt-in synthetic writer only. Servlet ownership authorises neither the database lane nor another user's batch. */
final class LocalCartSessionBinding {
    /** Supplied exclusively by the loopback test-classpath runner, never by property binding. */
    record SyntheticRuntime(com.creastrix.platform.accountcart.catalog.SharedCatalogAuthority catalog) {}
    static final String EPOCH = LocalCartSessionBinding.class.getName()+".epoch";
    static final String GUEST_INTENT = LocalCartSessionBinding.class.getName()+".guestIntent";
    record GuestLogin(UUID intent, UUID continuation) {
        String query() { return "cart_flow=" + continuation; }
        @Override public String toString() { return "GuestLogin[redacted]"; }
    }
    record Publication(Instant started, GuestLogin login) {}
    private static final String SLOT = LocalCartSessionBinding.class.getName()+".slot";
    private static final String SELECTED = LocalCartSessionBinding.class.getName()+".selected";
    private static final String PINNED = LocalCartSessionBinding.class.getName()+".pinned";
    final LocalCartCommands commands;

    LocalCartSessionBinding(LocalCartCommands commands) { this.commands=commands; }

    GuestLogin reserveGuestLogin(HttpServletRequest request) {
        var values=request.getParameterValues("cart_intent");
        if(request.getQueryString()!=null || values==null || values.length!=1) throw new Refusal();
        UUID intent;
        try { intent=UUID.fromString(values[0]); }
        catch(IllegalArgumentException invalid) { throw new Refusal(); }
        if(!intent.toString().equals(values[0])) throw new Refusal();
        commands.reserveGuestIntent(intent);
        return new GuestLogin(intent,UUID.randomUUID());
    }

    static final class Slot {
        final UUID owner;
        final String sid;
        final long eligibility;
        final UUID guestIntent;
        volatile LocalCartCommands.Claim claim;
        volatile boolean establishing=true;
        Slot(UUID owner,String sid,long eligibility,UUID guestIntent) {
            this.owner=owner; this.sid=sid; this.eligibility=eligibility; this.guestIntent=guestIntent;
        }
        @Override public String toString() { return "LocalCartSession[redacted]"; }
    }
    private record Selection(Slot slot,HttpSession session,long epoch,String context,BrowserSessionContextRegistry.Stamp stamp) {
        @Override public String toString() { return "LocalCartSelection[redacted]"; }
    }
    static final class Refusal extends RuntimeException {}
    static final class Establishing extends RuntimeException {}

    // Runs during the ordinary callback publication segment. No JDBC, waiting or response commit here.
    void select(HttpServletRequest request,Authentication authentication,BrowserSessionContextRegistry contexts) {
        if (!(request.getAttribute(EPOCH) instanceof Long epoch)
                || !(authentication.getPrincipal() instanceof CreastrixPrincipal p) || p.cartEligibility()==null)
            throw CreastrixOidcUserService.rejected();
        var session=request.getSession(false);
        if(session==null || session.getAttribute(SLOT)!=null) throw CreastrixOidcUserService.rejected();
        var slot=new Slot(p.userId(),session.getId(),p.cartEligibility(),(UUID)request.getAttribute(GUEST_INTENT));
        var selection=new Selection(slot,session,epoch,BrowserSessionContextRegistry.cookie(request),contexts.capture(session));
        session.setAttribute(SLOT,slot);
        request.setAttribute(SELECTED,selection);
    }

    // Called after attempt.finish releases the servlet publication mutex. A concurrent
    // logout sees "establishing" and cannot claim success until it can fence the exact claim.
    void complete(HttpServletRequest request,HttpServletResponse response,AuthenticationProperties properties,
                  BrowserSessionContextRegistry contexts,boolean chainCompleted) {
        if (!(request.getAttribute(SELECTED) instanceof Selection s)) return;
        request.removeAttribute(SELECTED);
        LocalCartCommands.Claim claim=null;
        boolean success=false;
        try {
            if (!chainCompleted || !live(s,contexts) || response.isCommitted()) return;
            claim=commands.beginLogin(s.epoch,s.slot.owner,s.slot.eligibility,s.slot.sid);
            commands.bind(claim,s.slot.sid); // BOUND or a classified durable, recoverable rollback.
            if (live(s,contexts)) {
                s.slot.claim=claim;
                success=true;
            }
        } catch (RuntimeException refused) {
            // Unknown binding does not become pending or authorise implicit retries.
        } finally {
            if (!success && claim!=null) {
                try { commands.logout(claim,s.slot.sid); }
                catch(RuntimeException unavailable) { /* No session receives this grant. Its epoch cannot be reused. */ }
            }
            s.slot.establishing=false;
            if (!response.isCommitted()) AuthenticationConfiguration.redirect(response,
                    success ? properties.successUri() : properties.failureUri());
        }
    }

    private static boolean live(Selection s,BrowserSessionContextRegistry registry) {
        try {
            return s.session.getAttribute(SLOT)==s.slot && s.slot.sid.equals(s.session.getId())
                    && registry.finalAdmit(s.context,s.stamp);
        } catch(IllegalStateException expired) { return false; }
    }

    static Slot pin(HttpServletRequest request,UUID actor) {
        HttpSession session=request.getSession(false);
        if(session==null || !(session.getAttribute(SLOT) instanceof Slot slot)
                || !slot.owner.equals(actor) || !slot.sid.equals(session.getId())) throw new Refusal();
        if(slot.establishing) throw new Establishing();
        if(slot.claim==null) throw new Refusal();
        request.setAttribute(PINNED,slot);
        return slot;
    }
    static Slot pinned(HttpServletRequest request) {
        if (!(request.getAttribute(PINNED) instanceof Slot slot)) throw new Refusal();
        return slot;
    }

    boolean logout(HttpServletRequest request,Authentication authentication,BrowserSessionContextRegistry registry) {
        var session=request.getSession(false);
        if(session==null || !(authentication!=null && authentication.getPrincipal() instanceof CreastrixPrincipal p)
                || !(session.getAttribute(SLOT) instanceof Slot slot)) return false;
        if (!slot.owner.equals(p.userId()) || !slot.sid.equals(session.getId())
                || !registry.echo(BrowserSessionContextRegistry.cookie(request),registry.capture(session))) throw new Refusal();
        if(slot.establishing) throw new Establishing();
        if(slot.claim==null) return false;
        try { commands.logout(slot.claim,slot.sid); }
        catch(LocalCartCommands.Conflict superseded) { return false; } // Cannot revoke another login's grant.
        return true;
    }

    static UUID header(HttpServletRequest request,String name) {
        var values=Collections.list(request.getHeaders(name));
        if(values.size()!=1) throw new Refusal();
        try {
            var id=UUID.fromString(values.getFirst());
            if(!id.toString().equals(values.getFirst())) throw new Refusal();
            return id;
        } catch(IllegalArgumentException invalid) { throw new Refusal(); }
    }
    static UUID commandNamespace(HttpServletRequest request,Slot slot) {
        if(!slot.claim.ticket().equals(header(request,"X-Cart-Context"))) throw new Refusal();
        var namespace=header(request,"X-Cart-Namespace");
        if(!namespace.equals(slot.claim.namespace())) throw new Refusal();
        return namespace;
    }
}
