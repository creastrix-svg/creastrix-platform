package com.creastrix.platform.accountcart;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Function;
import javax.sql.DataSource;

import com.creastrix.platform.accountcart.assets.PrivateCartAssetStore;
import com.creastrix.platform.accountcart.catalog.SharedCatalogAuthority;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicitly constructed by the isolated synthetic account-cart lane. The HTTP/session adapter
 * captures loginEpoch on the original login attempt, not on callback/retry. It must also pass the
 * admitted, rotated session ID on every operation; the caller cannot choose an account or a scope.
 */
public final class LocalCartCommands {
    public static final UUID LANE = UUID.fromString("6651617f-080f-4a4b-bdef-2d77ba1c85cf");
    public record Claim(UUID ticket,long generation,UUID owner,long eligibilityGeneration,UUID namespace) {
        @Override public String toString() { return "LocalCartClaim[redacted]"; }
    }
    public record Item(String sku,UUID designId,Long designRevision,long quantity) {}
    public enum EditAction { QUANTITY, REMOVE, RESTORE, DISMISS }
    public record Edit(UUID lineId, EditAction action, long expectedVersion, Long quantity) {}
    public record Addition(String sku,long quantity,UUID designId,Long designRevision) {
        public Addition(String sku,long quantity) { this(sku,quantity,null,null); }
        Item item() { return new Item(sku,designId,designRevision,quantity); }
    }
    public record Receipt(String state,Long version,UUID lineId) {
        public Receipt(String state,Long version) { this(state,version,null); }
    }
    public enum Access { BOUND,PENDING }
    public enum BindResult { BOUND,RETRYABLE }
    public static final class Conflict extends RuntimeException { public Conflict() { super("CART_CONFLICT"); } }
    public static final class InvalidBatch extends RuntimeException { public InvalidBatch() { super("CART_INVALID_BATCH"); } }
    public static final class Unavailable extends RuntimeException {
        public Unavailable() { super("CART_UNAVAILABLE"); }
    }
    private record Scope(long generation,UUID owner) {}
    private record PreparedInput(Receipt receipt,List<Item> items,List<UUID> assets) {}
    private record Persisted(Claim claim,byte[] proof,String state) {
        @Override public String toString() { return "StoredCartClaim[redacted]"; }
    }

    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final PreparedCartImportStore imports;
    private final LocalCartPreparation batches;
    private final ExactResourceRegistrar registrar;
    private final LocalStudioDesignStore designs;
    private final LocalCartAdditions additions;
    private final LocalCartEdits edits;
    private final SavedDesignLibrary library;
    private final BuyerFavorites favorites;
    private final SharedCatalogAuthority catalog;

    public LocalCartCommands(DataSource source,PrivateCartAssetStore files) {
        this(source,files,null);
    }
    public LocalCartCommands(DataSource source,PrivateCartAssetStore files,SharedCatalogAuthority catalog) {
        this.catalog=catalog;
        db=new JdbcTemplate(source);
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(12);
        imports=new PreparedCartImportStore(source);
        batches=new LocalCartPreparation(db);
        registrar=new ExactResourceRegistrar(source,files);
        designs=new LocalStudioDesignStore(db,files);
        additions=new LocalCartAdditions(db,batches);
        edits=new LocalCartEdits(db);
        library=new SavedDesignLibrary(db);
        favorites=new BuyerFavorites(db);
    }

    private static <T> T guarded(Supplier<T> operation) {
        try { return operation.get(); }
        catch (Conflict | InvalidBatch | Unavailable classified) { throw classified; }
        // Driver details can contain row values, claims and session proofs. Do not
        // attach them to exceptions crossing this synthetic HTTP boundary.
        catch (RuntimeException unresolved) { throw new Unavailable(); }
    }
    private <T> T tx(Supplier<T> work) {
        return transaction.execute(status->{
            db.execute("SET LOCAL lock_timeout='3s'");
            db.execute("SET LOCAL statement_timeout='8s'");
            db.execute("SET LOCAL transaction_timeout='12s'");
            return work.get();
        });
    }
    private Scope scope(boolean lock) {
        return db.query("SELECT generation,owner_id FROM account_cart.write_scope WHERE id=?"+
                (lock?" FOR NO KEY UPDATE":""),(r,n)->new Scope(r.getLong(1),r.getObject(2,UUID.class)),LANE)
                .stream().findFirst().orElseThrow(Unavailable::new);
    }
    private long activeGeneration(UUID owner,boolean lock) {
        return db.queryForList("SELECT account_eligibility_generation FROM public.users WHERE id=? AND status='ACTIVE'"+
                (lock?" FOR NO KEY UPDATE":""),Long.class,owner).stream().findFirst().orElseThrow(Conflict::new);
    }
    private static byte[] proof(String sessionId) {
        if (sessionId==null || sessionId.isBlank() || sessionId.length()>512) throw new Conflict();
        try { return MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public long loginEpoch() { return guarded(()->tx(()->scope(false).generation())); }
    public long eligibilityGeneration(UUID owner) { return guarded(()->tx(()->activeGeneration(owner,false))); }

    /** Never recycle a pre-login intent, even if publication, navigation or the callback fails. */
    public void reserveGuestIntent(UUID intent) {
        guarded(()->tx(()->{
            if (db.update("INSERT INTO account_cart.guest_login_intent(intent_id) VALUES (?) ON CONFLICT DO NOTHING",intent)!=1)
                throw new Conflict();
            return true;
        }));
    }

    public Claim beginLogin(long expectedEpoch,UUID owner,long eligibilityGeneration,String sessionId) {
        byte[] session=proof(sessionId);
        return guarded(()->tx(()->{
            var old=scope(true);
            if (expectedEpoch!=old.generation() || activeGeneration(owner,true)!=eligibilityGeneration) throw new Conflict();
            long pendingEpoch=Math.incrementExact(old.generation()), target=Math.incrementExact(pendingEpoch);
            db.update("INSERT INTO account_cart.owner_namespace(owner_id,namespace) VALUES (?,?) ON CONFLICT(owner_id) DO NOTHING",
                    owner,UUID.randomUUID());
            UUID ns=db.queryForObject("SELECT namespace FROM account_cart.owner_namespace WHERE owner_id=?",UUID.class,owner);
            Claim claim=new Claim(UUID.randomUUID(),target,owner,eligibilityGeneration,ns);
            db.update("UPDATE account_cart.write_scope SET generation=?,owner_id=NULL WHERE id=?",pendingEpoch,LANE);
            db.update("""
                INSERT INTO account_cart.lane_claim(scope_id,ticket,target_generation,owner_id,namespace,eligibility_generation,session_proof,state)
                VALUES (?,?,?,?,?,?,?,'PENDING') ON CONFLICT(scope_id) DO UPDATE SET ticket=EXCLUDED.ticket,
                  target_generation=EXCLUDED.target_generation,owner_id=EXCLUDED.owner_id,namespace=EXCLUDED.namespace,
                  eligibility_generation=EXCLUDED.eligibility_generation,session_proof=EXCLUDED.session_proof,state='PENDING'
                """,LANE,claim.ticket(),target,owner,ns,eligibilityGeneration,session);
            return claim;
        }));
    }

    private Persisted exact(Scope scope,Claim claim,String sessionId,boolean requireActive) {
        if (claim==null) throw new Conflict();
        if (requireActive && activeGeneration(claim.owner(),true)!=claim.eligibilityGeneration()) throw new Conflict();
        var original=db.query("""
                SELECT ticket,target_generation,owner_id,eligibility_generation,namespace,session_proof,state
                  FROM account_cart.lane_claim WHERE scope_id=?
                """,(r,n)->new Persisted(new Claim(r.getObject(1,UUID.class),r.getLong(2),r.getObject(3,UUID.class),
                    r.getLong(4),r.getObject(5,UUID.class)),r.getBytes(6),r.getString(7)),LANE)
                .stream().findFirst().orElseThrow(Conflict::new);
        boolean bound="BOUND".equals(original.state());
        if (!claim.equals(original.claim()) || !MessageDigest.isEqual(original.proof(),proof(sessionId))
                || scope.generation()!=(bound?claim.generation():claim.generation()-1)
                || !Objects.equals(scope.owner(),bound?claim.owner():null)) throw new Conflict();
        return original;
    }
    public Access inspect(Claim claim,String sessionId) {
        return guarded(()->tx(()->switch(exact(scope(true),claim,sessionId,true).state()) {
            case "BOUND" -> Access.BOUND;
            case "RETRYABLE" -> Access.PENDING;
            default -> throw new Unavailable(); // No confirmed bind rollback or reconciled success.
        }));
    }
    public BindResult bind(Claim claim,String sessionId) { return finish(claim,sessionId,false); }
    public BindResult recover(Claim claim,String sessionId,UUID ticket) {
        if (claim==null || !claim.ticket().equals(ticket)) throw new Conflict();
        return finish(claim,sessionId,true);
    }
    private BindResult finish(Claim claim,String sessionId,boolean recovery) {
        return guarded(()->{
            var preparedToCommit=new java.util.concurrent.atomic.AtomicBoolean();
            try {
                return tx(()->{
                    var original=exact(scope(true),claim,sessionId,true);
                    if (original.state().equals("BOUND")) return BindResult.BOUND;
                    if (recovery && !original.state().equals("RETRYABLE")) throw new Conflict();
                    db.update("UPDATE account_cart.write_scope SET generation=?,owner_id=? WHERE id=?",
                            claim.generation(),claim.owner(),LANE);
                    db.update("UPDATE account_cart.lane_claim SET state='BOUND' WHERE scope_id=?",LANE);
                    preparedToCommit.set(true);
                    return BindResult.BOUND;
                });
            } catch (RuntimeException failure) {
                if (!preparedToCommit.get() || !knownRollback(failure)) throw failure;
            }
            return tx(()->{
                var original=exact(scope(true),claim,sessionId,true);
                if (original.state().equals("BOUND")) return BindResult.BOUND;
                db.update("UPDATE account_cart.lane_claim SET state='RETRYABLE' WHERE scope_id=?",LANE);
                return BindResult.RETRYABLE;
            });
        });
    }
    private static boolean knownRollback(Throwable failure) {
        for (Throwable t=failure; t!=null; t=t.getCause()) {
            if (t instanceof java.sql.SQLException sql && "PCA01".equals(sql.getSQLState())
                    && "org.postgresql.util.PSQLException".equals(t.getClass().getName())) {
                // The PostgreSQL driver is runtime-only. Match its structured fields, never
                // exception text or a generic transaction/connection failure.
                try {
                    Object detail=t.getClass().getMethod("getServerErrorMessage").invoke(t);
                    return detail!=null && "account_cart_fixture".equals(detail.getClass().getMethod("getSchema").invoke(detail))
                            && "local_cart_bind_rollback".equals(detail.getClass().getMethod("getConstraint").invoke(detail));
                } catch (ReflectiveOperationException failureToClassify) { return false; }
            }
        }
        return false;
    }
    public void logout(Claim claim,String sessionId) {
        guarded(()->tx(()->{
            var old=scope(true);
            exact(old,claim,sessionId,false);
            db.update("UPDATE account_cart.write_scope SET generation=?,owner_id=NULL WHERE id=?",Math.incrementExact(old.generation()),LANE);
            db.update("DELETE FROM account_cart.lane_claim WHERE scope_id=?",LANE);
            return true;
        }));
    }

    private void authority(Claim claim,String sessionId,UUID namespace) {
        if (namespace==null || claim==null || !namespace.equals(claim.namespace())) throw new Conflict();
        if (!exact(scope(true),claim,sessionId,true).state().equals("BOUND")) throw new Conflict();
    }
    private <T> T usingStageTarget(Claim claim,String sid,UUID ns,StudioDesign design,Supplier<T> work) {
        if(catalog==null) return tx(()->{authority(claim,sid,ns); return work.get();});
        return catalog.withVariant(design.sku,result->tx(()->{
            authority(claim,sid,ns);
            var item=result.orElseThrow(InvalidBatch::new);
            // An applied guest design may be pinned to a previously offered format. Direct
            // purchase admission checks that format is still active when the cart is credited.
            if(!Objects.equals(item.retainedRevision(),design.template)) throw new InvalidBatch();
            item.remember(db); return work.get();
        }));
    }
    public UUID stageDesign(Claim claim,String sessionId,UUID namespace,UUID stageId,StudioDesign design) {
        return guarded(()->{
            var reservation=usingStageTarget(claim,sessionId,namespace,design,()->designs.reserve(claim.owner(),stageId,design));
            designs.ensureBytes(reservation,design);
            return usingStageTarget(claim,sessionId,namespace,design,()->{
                designs.publish(claim.owner(),stageId,design,reservation); return stageId;
            });
        });
    }
    public java.util.Optional<tools.jackson.databind.node.ObjectNode> lineDesign(Claim claim,String sessionId,UUID line) {
        return guarded(()->{
            var original=tx(()->{ authority(claim,sessionId,claim.namespace()); return designs.line(claim.owner(),line); });
            if(original==null) return java.util.Optional.empty();
            var view=designs.restore(original);
            return tx(()->{
                authority(claim,sessionId,claim.namespace());
                var current=designs.line(claim.owner(),line);
                // The revision and its ordered resource edges are immutable; never return the replaced line's source.
                return current!=null && current.design().equals(original.design()) ? java.util.Optional.of(view) : java.util.Optional.empty();
            });
        });
    }

    public List<SavedDesignLibrary.Item> library(Claim claim,String sid) {
        if(catalog==null)throw new Conflict();
        return guarded(()->tx(()->{authority(claim,sid,claim.namespace());return library.list(claim.owner());}));
    }

    public BuyerFavorites.Snapshot favorites(Claim claim, String sid) {
        if (catalog == null || claim == null) throw new Conflict();
        return guarded(() -> tx(() -> {
            authority(claim,sid,claim.namespace());
            return favorites.snapshot(claim.owner());
        }));
    }

    /** The guest proof comes from the server-selected, successfully completed login,
     * never the posted body. A later login may only reconcile an existing decision
     * of the same owner; it cannot make a first decision for the old guest batch. */
    public BuyerFavorites.Snapshot changeFavorites(Claim claim,String sid,UUID ns,UUID intent,
                                                   UUID originalGuestIntent,BuyerFavorites.Change change) {
        if (catalog == null || claim == null) throw new Conflict();
        if (intent == null || change == null) throw new InvalidBatch();
        byte[] fingerprint=change.fingerprint();
        return guarded(() -> {
            Supplier<BuyerFavorites.Decision> admitted = () -> {
                authority(claim,sid,ns);
                var old=favorites.prior(claim.owner(),intent,change,fingerprint);
                if (old==null && change.operation()==BuyerFavorites.Operation.IMPORT && !intent.equals(originalGuestIntent))
                    throw new Conflict();
                return old;
            };
            var known=tx(() -> {
                var old=admitted.get();
                if (old!=null) return old;
                // Forgetting a withdrawn variant already on this owner's shelf
                // needs no catalog access, even during an outage.
                if (change.operation()==BuyerFavorites.Operation.UNSET && favorites.holds(claim.owner(),change.skus().getFirst()))
                    return favorites.apply(claim.owner(),claim.generation(),intent,change,fingerprint,java.util.Set.of());
                return null;
            });
            if (known!=null) return known;
            // Lock order matches cart: acquire current seller rows, then the
            // write scope, re-admit, and commit membership with its receipt.
            return catalog.withVariants(change.skus(), rows -> tx(() -> {
                var prior=admitted.get();
                return prior!=null ? prior : favorites.apply(claim.owner(),claim.generation(),intent,change,fingerprint,rows.keySet());
            }));
        }).confirmed();
    }

    public SavedDesignLibrary.Result changeLibrary(Claim claim,String sid,UUID namespace,UUID intent,SavedDesignLibrary.Change change) {
        if(catalog==null)throw new Conflict();
        // Report even a terminal refusal only after its decision has committed.
        return guarded(()->tx(()->{authority(claim,sid,namespace);return library.change(claim.owner(),intent,change);})).confirmed();
    }

    private record LibraryRead(SavedDesignLibrary.Entry entry,LocalStudioDesignStore.Line snapshot) {}
    public java.util.Optional<SavedDesignLibrary.Opened> openSaved(Claim claim,String sid,UUID id) {
        if(catalog==null)throw new Conflict();
        return guarded(()->{
            var read=tx(()->{
                authority(claim,sid,claim.namespace());
                var entry=library.current(claim.owner(),id);
                return entry==null?null:new LibraryRead(entry,designs.snapshot(claim.owner(),entry.design()));
            });
            if(read==null)return java.util.Optional.empty();
            // File I/O is outside both transactions. Re-admit and confirm the
            // same pointer, so an update/delete during that I/O never opens a mixed revision.
            var data=designs.restore(read.snapshot);
            return tx(()->{
                authority(claim,sid,claim.namespace());
                var latest=library.current(claim.owner(),id);
                if(latest==null)return java.util.Optional.empty();
                if(!latest.equals(read.entry))throw new Conflict();
                return java.util.Optional.of(new SavedDesignLibrary.Opened(latest.item(),data));
            });
        });
    }
    public Receipt prepare(Claim claim,String sessionId,UUID namespace,UUID batchId,List<Item> lines) {
        return guarded(()->{
            if (lines==null || lines.size()>1000 || lines.stream().anyMatch(Objects::isNull)) throw new InvalidBatch();
            var items=List.copyOf(lines);
            for(var item:items) if(item.sku()==null || item.sku().length()>200 || !item.sku().matches("[a-z0-9]+(-[a-z0-9]+)*"))
                throw new InvalidBatch();
            verifyAssets(claim,tx(()->{
                authority(claim,sessionId,namespace);
                return assetsFor(claim.owner(),items);
            }));
            var skus=items.stream().map(Item::sku).distinct().toList();
            Function<Map<String,SharedCatalogAuthority.Target>,Receipt> prepare=found->tx(()->{
                authority(claim,sessionId,namespace);
                retainGuestCatalog(skus,found,false);
                return batches.prepare(claim.owner(),batchId,items);
            });
            return catalog==null?prepare.apply(Map.of()):catalog.withVariants(skus,prepare);
        });
    }
    private void retainGuestCatalog(List<String> skus,Map<String,SharedCatalogAuthority.Target> found,boolean alreadyPrepared) {
        if(catalog==null) return;
        for(String sku:skus) {
            var target=found.get(sku);
            if(target!=null) target.remember(db);
            else if(!Boolean.TRUE.equals(db.queryForObject(
                    "SELECT EXISTS(SELECT FROM account_cart.storefront_variant WHERE sku=?)",Boolean.class,sku))) {
                // Unknown new sources may be refused before prepare. A frozen historical
                // receipt stays pending until it can be resolved or its owner retires it.
                if(alreadyPrepared) throw new Unavailable();
                throw new InvalidBatch();
            }
        }
    }
    public Receipt status(Claim claim,String sessionId,UUID namespace,UUID batchId) {
        return guarded(()->tx(()->{ authority(claim,sessionId,namespace); return batches.status(claim.owner(),batchId); }));
    }
    public Receipt add(Claim claim,String sessionId,UUID namespace,UUID intent,Addition request) {
        var receipt=guarded(()->{
            var assets=new java.util.ArrayList<UUID>();
            var previous=tx(()->{
                authority(claim,sessionId,namespace);
                LocalCartAdditions.validate(intent,request);
                var decided=additions.previous(claim.owner(),intent,request);
                if(decided!=null || request.designId()==null) return decided;
                // A wrong owner, SKU or revision is a terminal decision for this intent.
                // A transient failure to read or compare registered private bytes is not.
                try { assets.addAll(assetsFor(claim.owner(),List.of(request.item()))); }
                catch(InvalidBatch invalidSource) { return additions.reject(claim.owner(),intent,request); }
                return null;
            });
            if(previous!=null) return previous;
            verifyAssets(claim,assets);
            Function<SharedCatalogAuthority.Target,Receipt> credit=source->tx(()->{
                authority(claim,sessionId,namespace);
                // Another transport copy could have completed while bytes were read.
                var decided=additions.previous(claim.owner(),intent,request);
                if(decided!=null) return decided;
                if(source!=null) source.remember(db);
                var target=catalog==null ? additions.fixtureTarget(request.sku())
                        : source==null ? null : LocalCartAdditions.liveTarget(source);
                return additions.add(claim.owner(),intent,request,target);
            });
            return catalog==null?credit.apply(null):catalog.withVariant(request.sku(),source->credit.apply(source.orElse(null)));
        });
        // A confirmed refusal is safe to show only AFTER its decision commits.
        // A lost connection to COMMIT remains unavailable, never a terminal 422.
        if (receipt.state().equals("REJECTED")) throw new InvalidBatch();
        return receipt;
    }
    public Receipt retire(Claim claim,String sessionId,UUID namespace,UUID batchId) {
        return guarded(()->tx(()->{ authority(claim,sessionId,namespace); return batches.retire(claim.owner(),batchId); }));
    }
    /** Existing scope/User locks are reacquired after the live catalog lookup.
     * A receipt replay (including a previously rejected edit) needs no catalog. */
    public Receipt edit(Claim claim,String sessionId,UUID namespace,UUID intent,Edit request) {
        if(catalog==null) throw new Conflict();
        LocalCartEdits.validate(intent,request);
        var decision=guarded(()->{
            var pre=tx(()->{
                authority(claim,sessionId,namespace);
                var prior=edits.previous(claim.owner(),intent,request);
                if(prior!=null) return new LocalCartEdits.Preflight(prior,null);
                var line=edits.current(claim.owner(),request.lineId());
                if(edits.stale(line,request))
                    return new LocalCartEdits.Preflight(edits.staleReceipt(claim.owner(),intent,request),null);
                // Stock is not involved in removal, dismissal or a reduction. A seller
                // outage must not prevent those actions or reconciliation of their receipts.
                boolean needsSource=request.action()==EditAction.RESTORE
                    || request.action()==EditAction.QUANTITY && request.quantity()>line.quantity();
                return new LocalCartEdits.Preflight(null,needsSource?line.sku():null,needsSource && line.design()!=null
                    ? assetsFor(claim.owner(),List.of(new Item(line.sku(),line.design(),line.revision(),1))) : List.of());
            });
            if(pre.previous()!=null) return pre.previous();
            verifyAssets(claim,pre.resources());
            Function<SharedCatalogAuthority.Target,Receipt> write=target->tx(()->{
                authority(claim,sessionId,namespace);
                var prior=edits.previous(claim.owner(),intent,request);
                return prior!=null ? prior : edits.apply(claim.owner(),intent,request,target);
            });
            return pre.sku()==null ? write.apply(null) : catalog.withVariant(pre.sku(),row->write.apply(row.orElse(null)));
        });
        return switch(decision.state()){
            case "APPLIED" -> decision;
            case "STALE" -> throw new Conflict();
            case "REJECTED" -> throw new InvalidBatch();
            default -> throw new Unavailable();
        };
    }
    public Receipt apply(Claim claim,String sessionId,UUID namespace,UUID batchId) {
        if (claim==null || namespace==null || !namespace.equals(claim.namespace())) throw new Conflict();
        if (batchId==null) throw new InvalidBatch();
        return guarded(()->{
            try {
                var prepared=tx(()->{
                    authority(claim,sessionId,namespace);
                    var receipt=batches.status(claim.owner(),batchId);
                    if(receipt.state().equals("APPLIED")) return new PreparedInput(receipt,List.of(),List.of());
                    if(!receipt.state().equals("PENDING")) throw new Conflict();
                    var items=db.query("""
                        SELECT v.sku,i.design_id,i.design_revision,i.quantity FROM account_cart.import_line i
                        JOIN account_cart.variant v ON (v.offer_id,v.id)=(i.offer_id,i.variant_id)
                        WHERE i.owner_id=? AND i.batch_id=? ORDER BY i.position LIMIT 1001
                        """,(r,n)->new Item(r.getString(1),r.getObject(2,UUID.class),r.getObject(3,Long.class),r.getLong(4)),claim.owner(),batchId);
                    return new PreparedInput(receipt,items,assetsFor(claim.owner(),items));
                });
                if(prepared.receipt().state().equals("APPLIED")) return prepared.receipt();
                verifyAssets(claim,prepared.assets());
                var a=new PreparedCartImportStore.Admission(LANE,claim.generation(),claim.owner(),claim.eligibilityGeneration());
                var skus=prepared.items().stream().map(Item::sku).distinct().toList();
                Function<Map<String,SharedCatalogAuthority.Target>,PreparedCartImportStore.Acknowledgement> apply=found->
                    imports.apply(a,batchId,ignored->{
                        authority(claim,sessionId,namespace);
                        if(batches.status(claim.owner(),batchId).state().equals("PENDING")) retainGuestCatalog(skus,found,true);
                    });
                var ack=catalog==null?apply.apply(Map.of()):catalog.withVariants(skus,apply);
                return new Receipt("APPLIED",ack.version());
            } catch (ArithmeticException invalidQuantity) { throw new InvalidBatch(); }
            catch (IllegalStateException refused) {
                if (List.of("CART_ADMISSION_REFUSED","CART_IMPORT_NOT_PENDING","CART_LINE_LIMIT").contains(refused.getMessage())) throw new Conflict();
                throw refused;
            }
        });
    }

    // A personalised match can touch only incoming and existing decorated lines of the same
    // variant. Never scan/validate unrelated legacy designs for a plain contribution or a replay.
    // Cap the union of incoming and affected existing owner asset IDs at 256. If exceeded,
    // reject the whole batch, preserving the guest source; do not scan or merge just a prefix.
    private List<UUID> assetsFor(UUID owner,List<Item> items) {
        if (items.size()>1000) throw new InvalidBatch();
        var result=new HashSet<UUID>();
        var touched=new HashSet<String>();
        for (var item:items) {
            if (item.designId()==null) continue;
            if (item.sku()==null || item.sku().length()>200 || item.designRevision()==null) throw new InvalidBatch();
            var found=db.query("""
                SELECT t.configuration->>'format'='loop-studio-v1' AS surface_layout,
                       CASE WHEN t.configuration->>'format'='loop-studio-v1' THEN d.canonical_bytes END
                  FROM account_cart.design_revision d JOIN account_cart.variant v
                ON (v.offer_id,v.id)=(d.offer_id,d.variant_id)
                JOIN account_cart.template_revision t ON (t.offer_id,t.revision)=(d.offer_id,d.template_revision)
                WHERE v.sku=? AND d.owner_id=? AND d.design_id=? AND d.revision=? AND d.layout_format=1
                AND ((? AND (t.configuration->>'format') IS DISTINCT FROM 'loop-studio-v1')
                     OR (account_cart.studio_v2(t.configuration,t.revision,v.id) AND octet_length(d.canonical_bytes)<=65536))
                """,(r,n)->!r.getBoolean(1) || StudioDesign.supportedLayout(r.getBytes(2)),
                    item.sku(),owner,item.designId(),item.designRevision(),catalog==null);
            if (!found.contains(true)) throw new InvalidBatch();
            result.addAll(db.queryForList("SELECT asset_id FROM account_cart.design_asset WHERE owner_id=? AND design_id=? AND revision=? LIMIT 257",
                    UUID.class,owner,item.designId(),item.designRevision()));
            if (touched.add(item.sku())) result.addAll(db.queryForList("""
                SELECT DISTINCT a.asset_id FROM account_cart.cart_line l JOIN account_cart.variant v
                  ON (v.offer_id,v.id)=(l.offer_id,l.variant_id) JOIN account_cart.design_asset a
                  ON (a.owner_id,a.design_id,a.revision)=(l.owner_id,l.design_id,l.design_revision)
                  WHERE l.owner_id=? AND NOT l.removed AND v.sku=? LIMIT 257
                """,UUID.class,owner,item.sku()));
            if (result.size()>256) throw new InvalidBatch();
        }
        return result.stream().sorted().toList();
    }
    private void verifyAssets(Claim claim,List<UUID> assets) {
        var a=new PreparedCartImportStore.Admission(LANE,claim.generation(),claim.owner(),claim.eligibilityGeneration());
        for (UUID asset:assets) {
            try { registrar.register(a,asset); }
            catch (java.io.IOException unavailable) { throw new Unavailable(); }
        }
    }
}
