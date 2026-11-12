package com.creastrix.platform.authentication;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.creastrix.platform.accountcart.LocalCartCommands;
import com.creastrix.platform.accountcart.StudioDesign;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Commands for the dedicated synthetic lane, never mounted in the normal account or catalog. */
@RestController
@Profile(LocalAccountCartConfiguration.PROFILE)
final class LocalCartProtocol {
    private static final String PREFIX=LocalAccountCartController.CART_PATH;
    private static final String RECOVER=PREFIX+"/recover";
    private static final String IMPORT=PREFIX+"/imports/{batch}/{action}";
    private static final String STATUS=PREFIX+"/imports/{batch}/status";
    private static final String STAGED=LocalCartProtocol.class.getName()+".receipt";
    private static final String DESIGN=LocalCartProtocol.class.getName()+".design";
    private static final String UUID_PATH="([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";
    private static final Pattern SAVE=Pattern.compile(PREFIX+"/designs/"+UUID_PATH);
    private static final Pattern READ=Pattern.compile(PREFIX+"/lines/"+UUID_PATH+"/design");
    private static final Pattern PATH=Pattern.compile(PREFIX+"/imports/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/(prepare|apply|status|retire)");
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final JsonMapper STUDIO_JSON=JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder()
            .streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder().maxNestingDepth(5)
                    .maxTokenCount(180).maxNumberLength(32).maxNameLength(32)
                    .maxStringLength(4*((10*1024*1024+2)/3)).maxDocumentLength(StudioDesign.MAX_WIRE).build()).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    record Route(String action,UUID batch) {}
    record ReceiptView(String state,String version) {
        static ReceiptView of(LocalCartCommands.Receipt receipt) {
            return new ReceiptView(receipt.state(),receipt.version()==null?null:receipt.version().toString());
        }
    }
    private final LocalCartCommands commands;
    LocalCartProtocol(LocalAccountCartController controller) { commands=controller.binding.commands; }

    static Route route(HttpServletRequest req) {
        if(req.getQueryString()!=null) return null;
        var method=req.getMethod();
        if("POST".equals(method) && RECOVER.equals(req.getRequestURI())) return new Route("recover",null);
        var save=SAVE.matcher(req.getRequestURI());
        if("POST".equals(method) && save.matches()) return new Route("saveDesign",UUID.fromString(save.group(1)));
        var read=READ.matcher(req.getRequestURI());
        if("GET".equals(method) && read.matches()) return new Route("readDesign",UUID.fromString(read.group(1)));
        var match=PATH.matcher(req.getRequestURI());
        if(!match.matches()) return null;
        String action=match.group(2);
        if(!(action.equals("status")?"GET":"POST").equals(method)) return null;
        return new Route(action,UUID.fromString(match.group(1)));
    }

    static void stage(LocalCartSessionBinding binding,Route route,HttpServletRequest request,UUID actor) {
        var slot=LocalCartSessionBinding.pin(request,actor);
        if(route.action.equals("recover")) return;
        if(route.action.equals("readDesign")) {
            binding.commands.lineDesign(slot.claim,slot.sid,route.batch).ifPresent(value->request.setAttribute(DESIGN,value));
            return;
        }
        UUID namespace=LocalCartSessionBinding.commandNamespace(request,slot);
        if(route.action.equals("status")) request.setAttribute(STAGED,ReceiptView.of(
                binding.commands.status(slot.claim,slot.sid,namespace,route.batch)));
    }

    @PostMapping(PREFIX+"/designs/{id}")
    Map<String,String> saveDesign(HttpServletRequest req) throws IOException {
        var route=route(req);
        if(route==null || !route.action.equals("saveDesign")) throw new LocalCartSessionBinding.Refusal();
        var slot=LocalCartSessionBinding.pinned(req);
        var ns=LocalCartSessionBinding.commandNamespace(req,slot);
        var result=commands.stageDesign(slot.claim,slot.sid,ns,route.batch,StudioDesign.parse(body(req,StudioDesign.MAX_WIRE)));
        return Map.of("designId",result.toString(),"designRevision","1");
    }
    @GetMapping(PREFIX+"/lines/{id}/design")
    ResponseEntity<JsonNode> readDesign(HttpServletRequest request) {
        LocalCartSessionBinding.pinned(request);
        if(request.getAttribute(DESIGN) instanceof JsonNode data)
            return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(data);
        return ResponseEntity.notFound().build();
    }

    @GetMapping(STATUS)
    ReceiptView status(HttpServletRequest request) {
        if(request.getAttribute(STAGED) instanceof ReceiptView receipt) return receipt;
        throw new LocalCartSessionBinding.Refusal();
    }

    @PostMapping(RECOVER)
    ResponseEntity<Map<String,String>> recover(HttpServletRequest req) throws IOException {
        var slot=LocalCartSessionBinding.pinned(req);
        JsonNode body=body(req);
        if(body.size()!=1) throw new LocalCartCommands.InvalidBatch();
        UUID ticket=id(string(body,"ticket"));
        var result=commands.recover(slot.claim,slot.sid,ticket);
        return result==LocalCartCommands.BindResult.BOUND ? ResponseEntity.ok(Map.of("kind","recovered"))
                : ResponseEntity.status(503).body(Map.of("kind","retryable"));
    }

    @PostMapping(IMPORT)
    ReceiptView command(HttpServletRequest req) throws IOException {
        var route=route(req);
        if(route==null) throw new LocalCartSessionBinding.Refusal();
        var slot=LocalCartSessionBinding.pinned(req);
        UUID ns=LocalCartSessionBinding.commandNamespace(req,slot);
        return ReceiptView.of(switch(route.action) {
            case "prepare" -> commands.prepare(slot.claim,slot.sid,ns,route.batch,items(body(req)));
            case "apply","retire" -> {
                if(req.getContentLengthLong()>0 || req.getInputStream().read()!=-1) throw new LocalCartCommands.InvalidBatch();
                yield route.action.equals("apply") ? commands.apply(slot.claim,slot.sid,ns,route.batch)
                        : commands.retire(slot.claim,slot.sid,ns,route.batch);
            }
            default -> throw new LocalCartSessionBinding.Refusal();
        });
    }

    private static JsonNode body(HttpServletRequest req) throws IOException {
        return body(req,128*1024);
    }
    private static JsonNode body(HttpServletRequest req,int limit) throws IOException {
        try {
            if(req.getContentType()==null || !MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(req.getContentType()))
                    || req.getContentLengthLong()>limit) throw new LocalCartCommands.InvalidBatch();
            byte[] bytes=req.getInputStream().readNBytes(limit+1);
            if(bytes.length>limit) throw new LocalCartCommands.InvalidBatch();
            var data=(limit==StudioDesign.MAX_WIRE?STUDIO_JSON:JSON).readTree(bytes);
            if(data==null || !data.isObject()) throw new LocalCartCommands.InvalidBatch();
            return data;
        } catch(RuntimeException malformed) { throw new LocalCartCommands.InvalidBatch(); }
    }
    static List<LocalCartCommands.Item> items(JsonNode object) {
        var array=object.get("lines");
        if(object.size()!=1 || array==null || !array.isArray() || array.size()>1000) throw new LocalCartCommands.InvalidBatch();
        var result=new ArrayList<LocalCartCommands.Item>();
        for(var row:array) {
            if(!row.isObject() || row.size()!=4 || !row.has("designId") || !row.has("designRevision"))
                throw new LocalCartCommands.InvalidBatch();
            String sku=string(row,"sku");
            if(sku.length()>200 || !sku.matches("[a-z0-9]+(-[a-z0-9]+)*")) throw new LocalCartCommands.InvalidBatch();
            UUID design=row.get("designId").isNull()?null:id(string(row,"designId"));
            Long revision=row.get("designRevision").isNull()?null:positive(string(row,"designRevision"));
            if((design==null)!=(revision==null)) throw new LocalCartCommands.InvalidBatch();
            result.add(new LocalCartCommands.Item(sku,design,revision,positive(string(row,"quantity"))));
        }
        return List.copyOf(result);
    }
    private static String string(JsonNode data,String field) {
        var value=data.get(field);
        if(value==null || !value.isString()) throw new LocalCartCommands.InvalidBatch();
        return value.asString();
    }
    private static UUID id(String text) {
        try { var id=UUID.fromString(text); if(id.toString().equals(text)) return id; }
        catch(IllegalArgumentException invalid) { /* No caller data in diagnostics. */ }
        throw new LocalCartCommands.InvalidBatch();
    }
    private static long positive(String text) {
        if(text.matches("[1-9][0-9]{0,18}"))
            try { return Long.parseLong(text); } catch(NumberFormatException invalid) { /* bounded refusal */ }
        throw new LocalCartCommands.InvalidBatch();
    }
    @ExceptionHandler({LocalCartCommands.Conflict.class,LocalCartSessionBinding.Refusal.class})
    void conflict(HttpServletResponse res) throws IOException { AuthenticationConfiguration.jsonError(res,409); }
    @ExceptionHandler(LocalCartCommands.InvalidBatch.class)
    void invalid(HttpServletResponse res) throws IOException { AuthenticationConfiguration.jsonError(res,422); }
    @ExceptionHandler({LocalCartCommands.Unavailable.class,LocalCartSessionBinding.Establishing.class})
    void unavailable(HttpServletResponse res) throws IOException { AuthenticationConfiguration.jsonError(res,503); }
}
