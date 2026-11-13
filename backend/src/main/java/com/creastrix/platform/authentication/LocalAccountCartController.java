package com.creastrix.platform.authentication;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.creastrix.platform.accountcart.assets.PrivateCartAssetStore;
import com.creastrix.platform.accountcart.LocalCartCommands;
import com.creastrix.platform.accountcart.persistence.CartLineArtworkReader;
import com.creastrix.platform.accountcart.persistence.PermanentCartReader;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session-owned reads. The exact synthetic writer claim is never inferred from an account ID or cookie alone. */
@RestController
@Profile(LocalAccountCartConfiguration.PROFILE)
class LocalAccountCartController {
    private static final String RESULT = LocalAccountCartController.class.getName() + ".result";
    private static final String ART = LocalAccountCartController.class.getName() + ".art";
    static final String CART_PATH = "/api/account-cart";
    static final String ART_PATH = CART_PATH
            + "/lines/{lineId:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}}/artwork";
    private static final PathPatternRequestMatcher ART_MATCH = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.GET, ART_PATH);
    static final PathPatternRequestMatcher CART = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, CART_PATH);
    static final PathPatternRequestMatcher PUBLIC_CATALOG = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.GET, "/api/account-catalog/products");

    record CartView(String kind, UUID ownerId, UUID namespace, UUID writeContext,UUID guestIntent,List<PermanentCartReader.Line> cart) { }
    record Pending(String kind,UUID ownerId,UUID ticket,UUID guestIntent) {}
    private record Artwork(UUID owner, UUID line, byte[] bytes) { }
    private final PermanentCartReader reader;
    private final CartLineArtworkReader artwork;
    final LocalCartSessionBinding binding;
    private final AccountController accounts;

    LocalAccountCartController(JdbcTemplate jdbc, PrivateCartAssetStore files,LocalCartSessionBinding.SyntheticRuntime synthetic,
                               AccountController accounts) {
        reader = new PermanentCartReader(jdbc,synthetic.catalog());
        artwork = new CartLineArtworkReader(jdbc, files);
        binding=new LocalCartSessionBinding(new LocalCartCommands(java.util.Objects.requireNonNull(jdbc.getDataSource()),files,synthetic.catalog()));
        this.accounts=accounts;
    }

    @PostMapping(path="/auth/login",params="cart_intent")
    void guestLogin(Authentication authentication,HttpServletRequest request,HttpServletResponse response) throws IOException {
        accounts.login(authentication,request,response,binding);
    }

    static Optional<UUID> artworkLine(HttpServletRequest request) {
        var match = ART_MATCH.matcher(request);
        if (!match.isMatch() || request.getQueryString() != null) return Optional.empty();
        String id = match.getVariables().get("lineId");
        if (!(CART_PATH + "/lines/" + id + "/artwork").equals(request.getRequestURI())) return Optional.empty();
        return Optional.of(UUID.fromString(id));
    }

    void stage(HttpServletRequest request, UUID ownerId) {
        var slot=LocalCartSessionBinding.pin(request,ownerId);
        if(binding.commands.inspect(slot.claim,slot.sid)==LocalCartCommands.Access.PENDING) {
            request.setAttribute(RESULT,new Pending("pending",ownerId,slot.claim.ticket(),slot.guestIntent));
        } else {
            var lines=reader.lines(ownerId);
            if(binding.commands.inspect(slot.claim,slot.sid)==LocalCartCommands.Access.BOUND)
                lines.ifPresent(items -> request.setAttribute(RESULT,new CartView("ready",ownerId,slot.claim.namespace(),slot.claim.ticket(),slot.guestIntent,items)));
        }
    }

    void stageArtwork(HttpServletRequest request, UUID owner, UUID line) {
        var slot=LocalCartSessionBinding.pin(request,owner);
        if(binding.commands.inspect(slot.claim,slot.sid)!=LocalCartCommands.Access.BOUND) return;
        var result=artwork.read(owner,line);
        if(binding.commands.inspect(slot.claim,slot.sid)==LocalCartCommands.Access.BOUND)
            result.ifPresent(bytes -> request.setAttribute(ART,new Artwork(owner,line,bytes)));
    }

    @GetMapping(ART_PATH)
    void artwork(Authentication authentication, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (authentication == null || !(authentication.getPrincipal() instanceof CreastrixPrincipal actor)
                || !(request.getAttribute(ART) instanceof Artwork found) || !found.owner().equals(actor.userId())
                || artworkLine(request).filter(found.line()::equals).isEmpty()) {
            AuthenticationConfiguration.jsonError(response, 404);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        response.setContentType("image/png");
        response.setContentLength(found.bytes().length);
        response.getOutputStream().write(found.bytes());
    }

    @GetMapping(CART_PATH)
    Object cart(Authentication authentication, HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Only the private filter can stage a result, and it releases this
        // request to MVC only after the original session stamp passes finalAdmit.
        if (authentication != null && authentication.getPrincipal() instanceof CreastrixPrincipal actor) {
            Object data=request.getAttribute(RESULT);
            if(data instanceof CartView view && view.ownerId().equals(actor.userId())) return view;
            if(data instanceof Pending pending && pending.ownerId().equals(actor.userId())) {
                response.setStatus(409);
                return pending;
            }
        }
        AuthenticationConfiguration.jsonError(response, 403);
        return null;
    }
}
