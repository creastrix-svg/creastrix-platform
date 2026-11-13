package com.creastrix.platform.authentication;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.creastrix.platform.accountcart.LocalCartCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalGuestLoginIntentTest {
    final Clock clock=Clock.systemUTC();
    final LocalCartCommands commands=mock(LocalCartCommands.class);
    final LocalCartSessionBinding cart=new LocalCartSessionBinding(commands);
    final BrowserSessionContextRegistry contexts=new BrowserSessionContextRegistry(clock);
    final MockHttpSession session=new MockHttpSession();
    final AuthenticationAttemptCoordinator coordinator=AuthenticationAttemptCoordinator.forSession(session);
    final String q=contexts.bootstrap(session,coordinator);
    final AuthenticationProperties props=new AuthenticationProperties(LocalAccountCartConfiguration.ISSUER,"local","test",
            LocalAccountCartConfiguration.ORIGIN,Set.of(LocalAccountCartConfiguration.SUBJECT),false,
            AuthenticationProperties.Provider.COGNITO,true);
    final AccountController accounts=new AccountController(props,clock,contexts);
    @AfterEach void close() { contexts.close(); }

    MockHttpServletRequest request(String method,String path) {
        var request=new MockHttpServletRequest(method,path);
        request.setSession(session);
        request.addHeader("Cookie",BrowserSessionContextRegistry.COOKIE_NAME+"="+q);
        return request;
    }
    String publish(UUID id) throws Exception {
        var post=request("POST","/auth/login");
        if(id!=null) post.setParameter("cart_intent",id.toString());
        var out=new MockHttpServletResponse();
        accounts.login(null,post,out,id!=null ? cart : null);
        assertThat(out.getStatus()).isEqualTo(303);
        if(id!=null) verify(commands).reserveGuestIntent(id);
        return java.net.URI.create(out.getHeader("Location")).getRawQuery();
    }
    MockHttpServletRequest entry(String query,boolean accepted) {
        var get=request("GET",props.entryPath());
        get.setQueryString(query);
        assertThat(coordinator.consumeLoginIntent(session,clock,get)).isEqualTo(accepted
                ? AuthenticationAttemptCoordinator.LoginIntent.ACCEPTED : AuthenticationAttemptCoordinator.LoginIntent.REQUIRED);
        return get;
    }

    @Test void staleNativeRedirectCannotClaimAReplacementLoginOrReuseAnOlderContribution() throws Exception {
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        String first=publish(a), second=publish(b);
        assertThat(first).isNotEqualTo(second).doesNotContain(a.toString());
        assertThat(entry(first,false).getAttribute(LocalCartSessionBinding.GUEST_INTENT)).isNull();
        assertThat(entry(null,false).getAttribute(LocalCartSessionBinding.GUEST_INTENT)).isNull();
        assertThat(entry(second,true).getAttribute(LocalCartSessionBinding.GUEST_INTENT)).isEqualTo(b);
        assertThat(entry(second,false).getAttribute(LocalCartSessionBinding.GUEST_INTENT)).isNull();
        assertThat(publish(null)).isNull();
        assertThat(entry(second,false).getAttribute(LocalCartSessionBinding.GUEST_INTENT)).isNull();
        assertThat(entry(null,true).getAttribute(LocalCartSessionBinding.GUEST_INTENT)).isNull();
    }

    @Test void callbackUsesTheOriginallyAdmittedFlowEvenIfAnotherFlowWasSavedInTheSameSession() throws Exception {
        var repo=new AuthenticationConfiguration.TimedAuthorizationRequests(clock);
        var out=new MockHttpServletResponse();
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        var first=entry(publish(a),true);
        repo.saveAuthorizationRequest(flow("first"),first,out);
        var callback=request("GET",props.callbackPath());
        callback.setParameter("state","first");
        callback.setParameter("code","code-first");
        var attempt=repo.admit(callback,out);
        assertThat(attempt).isNotNull();
        try {
            var second=entry(publish(b),true);
            repo.saveAuthorizationRequest(flow("second"),second,out);
            var saved=repo.removeAuthorizationRequest(attempt.pin(callback),out);
            assertThat(callback.getAttribute(LocalCartSessionBinding.GUEST_INTENT)).isEqualTo(a);
            assertThat(saved.getAuthorizationRequestUri()).doesNotContain(a.toString(),b.toString(),"cart_intent","cart_flow");
        } finally { attempt.finish(); }
        var later=request("GET",props.callbackPath());
        later.setParameter("state","second");
        repo.removeAuthorizationRequest(later,out);
        assertThat(later.getAttribute(LocalCartSessionBinding.GUEST_INTENT)).isEqualTo(b);
    }

    OAuth2AuthorizationRequest flow(String state) {
        return OAuth2AuthorizationRequest.authorizationCode().authorizationUri(LocalAccountCartConfiguration.ISSUER+"authorize")
                .clientId("local").redirectUri(props.callbackUri()).state(state)
                .attributes(a->a.put(AuthenticationConfiguration.FLOW_STARTED,clock.instant())).build();
    }

    @Test void duplicateUncertainOrMalformedReservationsNeverPublishANewLogin() throws Exception {
        var id=UUID.randomUUID();
        doThrow(new LocalCartCommands.Conflict()).doThrow(new LocalCartCommands.Unavailable())
                .when(commands).reserveGuestIntent(id);
        var post=request("POST","/auth/login"); post.setParameter("cart_intent",id.toString());
        for(int expected:List.of(409,503)) {
            var out=new MockHttpServletResponse(); accounts.login(null,post,out,cart);
            assertThat(out.getStatus()).isEqualTo(expected);
            assertThat(out.getHeader("Location")).isNull();
            assertThat(session.getAttribute(AuthenticationConfiguration.LOGIN_INTENT)).isNull();
        }
        for(String[] values:List.of(new String[]{""},new String[]{id.toString(),id.toString()},new String[]{"1-2-3-4-5"})) {
            post.setParameter("cart_intent",values);
            assertThatThrownBy(()->cart.reserveGuestLogin(post)).isInstanceOf(LocalCartSessionBinding.Refusal.class);
        }
        verify(commands,times(2)).reserveGuestIntent(id);
        verifyNoMoreInteractions(commands);
    }
}
