package com.creastrix.platform.authentication;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.creastrix.platform.accountcart.LocalCartCommands;
import com.creastrix.platform.user.application.port.UserIdentityBindingRepository.Identity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalCartProtocolTest {
    private static final AuthenticationProperties PROPERTIES=new AuthenticationProperties(
            LocalAccountCartConfiguration.ISSUER,"test","local",LocalAccountCartConfiguration.ORIGIN,
            Set.of(LocalAccountCartConfiguration.SUBJECT),false,AuthenticationProperties.Provider.COGNITO,true);
    private static final class Callback {
        final LocalCartCommands commands=mock(LocalCartCommands.class);
        final LocalCartSessionBinding binding=new LocalCartSessionBinding(commands);
        final UUID buyer=UUID.randomUUID(),ns=UUID.randomUUID(),ticket=UUID.randomUUID();
        final LocalCartCommands.Claim claim=new LocalCartCommands.Claim(ticket,3,buyer,7,ns);
        final BrowserSessionContextRegistry registry=mock(BrowserSessionContextRegistry.class);
        final MockHttpServletRequest request=new MockHttpServletRequest("GET","/login/oauth2/code/cognito");
        final MockHttpSession session=new MockHttpSession();
        final MockHttpServletResponse response=new MockHttpServletResponse();
        final UsernamePasswordAuthenticationToken auth=UsernamePasswordAuthenticationToken.authenticated(
                new CreastrixPrincipal(buyer,new Identity(PROPERTIES.issuer(),LocalAccountCartConfiguration.SUBJECT),Instant.now(),null,7L),null,List.of());
        Callback() {
            request.setSession(session);
            request.setAttribute(LocalCartSessionBinding.EPOCH,1L);
            request.addHeader("Cookie",BrowserSessionContextRegistry.COOKIE_NAME+"="+UUID.randomUUID());
            when(registry.finalAdmit(any(),any())).thenReturn(true);
            when(registry.echo(any(),any())).thenReturn(true);
            when(commands.beginLogin(1,buyer,7,session.getId())).thenReturn(claim);
        }
        void select() { binding.select(request,auth,registry); }
        void complete() { binding.complete(request,response,PROPERTIES,registry,true); }
    }

    @Test void selectionIsPureAndConcurrentLogoutMustNotReportDurableRevocationDuringBinding() {
        var c=new Callback(); c.select();
        assertThatThrownBy(()->c.binding.logout(c.request,c.auth,c.registry)).isInstanceOf(LocalCartSessionBinding.Establishing.class);
        verifyNoInteractions(c.commands);
        when(c.commands.bind(c.claim,c.session.getId())).thenReturn(LocalCartCommands.BindResult.RETRYABLE);
        c.complete();
        assertThat(c.response.getHeader("Location")).isEqualTo(PROPERTIES.successUri());
        assertThat(LocalCartSessionBinding.pin(c.request,c.buyer).claim).isSameAs(c.claim);
        assertThat(c.binding.logout(c.request,c.auth,c.registry)).isTrue();
        verify(c.commands).logout(c.claim,c.session.getId());
    }

    @Test void rechecksOriginalGenerationAfterBindingAndCannotPublishARevokedGrant() {
        var c=new Callback(); c.select();
        when(c.registry.finalAdmit(any(),any())).thenReturn(true,false);
        c.complete();
        verify(c.commands).logout(c.claim,c.session.getId());
        assertThat(c.response.getHeader("Location")).isEqualTo(PROPERTIES.failureUri());
        assertThatThrownBy(()->LocalCartSessionBinding.pin(c.request,c.buyer)).isInstanceOf(LocalCartSessionBinding.Refusal.class);
    }

    @Test void staleOrMissingWriteContextCannotReachEvenTheImportStatusLookup() {
        var c=new Callback(); c.select(); c.complete();
        var id=UUID.randomUUID();
        var request=new MockHttpServletRequest("GET","/api/account-cart/imports/"+id+"/status");
        request.setSession(c.session);
        request.addHeader("X-Cart-Namespace",c.ns.toString());
        var route=LocalCartProtocol.route(request);
        assertThat(route).isNotNull();
        assertThatThrownBy(()->LocalCartProtocol.stage(c.binding,route,request,c.buyer))
                .isInstanceOf(LocalCartSessionBinding.Refusal.class);
        request.addHeader("X-Cart-Context",UUID.randomUUID().toString());
        assertThatThrownBy(()->LocalCartProtocol.stage(c.binding,route,request,c.buyer))
                .isInstanceOf(LocalCartSessionBinding.Refusal.class);
        verify(c.commands,never()).status(any(),anyString(),any(),any());
    }

    @Test void onlyCanonicalMethodAndPathCanEnterAnImport() {
        String route="/api/account-cart/imports/"+UUID.randomUUID()+"/apply";
        assertThat(LocalCartProtocol.route(new MockHttpServletRequest("POST",route))).isNotNull();
        for(String wrong:List.of("GET","PUT","DELETE","HEAD"))
            assertThat(LocalCartProtocol.route(new MockHttpServletRequest(wrong,route))).isNull();
        var query=new MockHttpServletRequest("POST",route);
        query.setQueryString("owner=another");
        assertThat(LocalCartProtocol.route(query)).isNull();
        assertThat(LocalCartProtocol.route(new MockHttpServletRequest("POST",route+"/"))).isNull();
        assertThat(LocalCartProtocol.route(new MockHttpServletRequest("POST",route.replace("apply","status")))).isNull();
    }
    @Test void aDirectCartClickAcceptsOnlySkuQuantityAndAnOptionalCompleteImmutableDesignReference() {
        var id=UUID.randomUUID();
        String path="/api/account-cart/additions/"+id;
        assertThat(LocalCartProtocol.route(new MockHttpServletRequest("POST",path)))
                .isEqualTo(new LocalCartProtocol.Route("add",id));
        for(String method:List.of("GET","PUT","DELETE","HEAD"))
            assertThat(LocalCartProtocol.route(new MockHttpServletRequest(method,path))).isNull();
        for(String wrong:List.of(path+"/", path+"/apply","/api/account-cart/additions/not-an-id"))
            assertThat(LocalCartProtocol.route(new MockHttpServletRequest("POST",wrong))).isNull();
        var query=new MockHttpServletRequest("POST",path);query.setQueryString("anything");
        assertThat(LocalCartProtocol.route(query)).isNull();
        var mapper=JsonMapper.builder().build();
        var data=mapper.createObjectNode().put("sku","tide-cobalt").put("quantity","2");
        assertThat(LocalCartProtocol.addition(data)).isEqualTo(new LocalCartCommands.Addition("tide-cobalt",2));
        var decorated=data.deepCopy().put("designId",id.toString()).put("designRevision","1");
        assertThat(LocalCartProtocol.addition(decorated)).isEqualTo(new LocalCartCommands.Addition("tide-cobalt",2,id,1L));
        for(var bad:List.of(decorated.deepCopy().putNull("designId"),decorated.deepCopy().putNull("designRevision"),
                decorated.deepCopy().put("designRevision","0"),decorated.deepCopy().put("designId","not-an-id"),
                decorated.deepCopy().put("design","arbitrary"),data.deepCopy().put("designRevision","1")))
            assertThatThrownBy(()->LocalCartProtocol.addition(bad)).isInstanceOf(LocalCartCommands.InvalidBatch.class);
        for(String extra:List.of("ownerId","price","designId"))
            assertThatThrownBy(()->LocalCartProtocol.addition(data.deepCopy().put(extra,"x")))
                    .isInstanceOf(LocalCartCommands.InvalidBatch.class);
        assertThatThrownBy(()->LocalCartProtocol.addition(data.deepCopy().put("quantity",2))).isInstanceOf(LocalCartCommands.InvalidBatch.class);
        assertThatThrownBy(()->LocalCartProtocol.addition(data.deepCopy().put("quantity","02"))).isInstanceOf(LocalCartCommands.InvalidBatch.class);
    }

    @ParameterizedTest @ValueSource(strings={"0","01","-1","9223372036854775808"})
    void onlyPositiveRepresentableQuantitiesCanBePrepared(String quantity) {
        var data=JsonMapper.builder().build().readTree("""
                {"lines":[{"sku":"kiln-blue","designId":null,"designRevision":null,"quantity":"%s"}]}
                """.formatted(quantity));
        assertThatThrownBy(()->LocalCartProtocol.items(data)).isInstanceOf(LocalCartCommands.InvalidBatch.class);
    }
}
