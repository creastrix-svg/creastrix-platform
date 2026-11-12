# Guest cart import: server contract

**Proposed; not implemented.** This defines the server behaviour behind the
[buyer merge rules](guest-cart-merge.md). The catalog is currently a separate
[read-only service](../local-catalog.md). There is no account-cart endpoint,
write authority, migration or production rollout in this change.

## What makes two cart lines the same?

A match is **(stable offer ID, stable variant ID, design)**. Design is either
the explicit value `plain` or the canonical bytes of an applied design. A free
design is still a design. Quantity is an input to the sum, not to equality.
Price, stock, user language, cart-line ID and the product's URL are not keys.

Proposed comparison format **design-v1**:

| Include in equality | Rule |
| --- | --- |
| Template and zones | Use immutable template ID and revision; validate variant compatibility, configured zone IDs and permitted operations. Represent zones in stable ID order; preserve the actual layer order within each zone and any template-defined composition order between zones. A different template revision or technique is not the same design. |
| Text and geometry | Preserve Unicode scalar values, case, meaningful spaces and line breaks (CRLF/CR → LF). No trimming, case folding or visual approximation. Include font, colour, placement, size, and every supported transform/fit that changes output. Use explicit defaults, fixed colour representation and integers in the published template's fixed-point units; reject out-of-range or non-representable input rather than silently rounding it. |
| Artwork and fonts | Resolve only authorised, immutable resources. For v1, equal validated bytes in the same resource kind/version denote the same resource, even under different upload IDs; different encodings need not match. Built-in fonts use a pinned font definition/version, not a platform font name. An upload handle, filename or hash supplied by the client proves neither identity nor access. |
| Serialization | Encode the validated fields as fixed-order arrays of integers and UTF-8 strings with explicit types and lengths, including format version. Drop editor selection, undo history, transient layer IDs and upload names. Unknown fields/versions, duplicate zones, unresolved assets and empty personalised designs are errors, never `plain`. |

The server builds this representation. A server-computed SHA-256 digest may
index candidates; compare the canonical bytes before coalescing. Resource IDs
in those bytes must themselves resolve by exact content, not a digest alone.
Keep old format readers or reject unsupported versions; do not equate versions
implicitly. The browser's [design key](../../frontend/src/product/studio/session/designSnapshot.ts)
is for local editing, not this wire format. Before code, pin field order,
units, limits and test vectors for each supported zone; an unsupported design
must remain recoverable locally, never mis-imported.

## Command and receipt

Use three owner-authorised writes, **prepare**, **apply** and **retire**, and
one owner-only **status** read. Status never creates, cancels or expires an
import. These are contracts, not deployed routes. The browser durably freezes
its additions once (shared across tabs) before any import request. On confirmed
sign-in, durably bind that frozen batch to the intended internal User before
transmission. Its logical import ID is **(owner namespace, random batch nonce)**.
The server supplies an opaque authenticated namespace for that User. On every
operation verify this namespace for the current User **before** any import
lookup or existence-dependent decision; the raw guest nonce is not an API ID.
The same nonce in B's namespace does not address A's batch. Token renewal must
preserve the logical namespace, not create a new import. Neither a namespace
nor an ID grants access on its own. Never rebind a pending batch to another
namespace. New additions use a separate nonce; sign-in or retry does not.

- **Prepare** registers the ID once, after account admission, with its owner,
  format version and immutable request identity. The request identity is the
  sorted multiset of canonical line keys and **summed positive quantities**;
  check all sums for overflow. Different transport order or local handles do
  not change it. Resource staging must already be durable, authorised and
  bounded; the import transaction never uploads or fetches files. Prepare
  does not add items. A lost prepare response can be recovered with the same
  ID and contents. A different payload is a conflict, not an amendment.
- **Apply** names the prepared ID, not a target account or client-supplied
  price. The persisted payload is the only source of incoming units. An
  unknown ID cannot create a cart or recreate a preparation. Same-owner
  replay of an applied ID returns the original acknowledgement and version,
  not a second addition or a historical cart in place of the current one.
  Read the current cart separately. Inaccessible and unknown IDs are
  indistinguishable to a different user.
- **Retire/correction:** a retryable failure leaves the immutable preparation
  pending. Only the currently admitted owner may explicitly retire it in v1;
  no background job or status read may create a terminal cancellation. An
  elapsed server deadline can prevent further apply attempts but is not proof
  that an earlier one did not commit. To replace a batch, obtain the owner-only
  terminal status **not applied and retired** for that exact ID. Retire takes
  the same authority, eligibility and import-key locks as prepare/apply,
  including a unique-key serialization point if no preparation row yet exists.
  If apply committed first, return applied; if retirement
  committed first, subsequent apply/prepare can never credit those units.
  If validation prevented initial preparation, retirement may seal the ID as
  never-applied only when the authoritative history proves that outcome. A
   timeout or client clock is not proof. A lost retire response is reconciled
   by status on the same ID. Only confirmed retirement unlocks guest units for
  correction and a new ID. Pending or unresolved means keep them frozen.
- **Lifetime:** the full import ID is never reusable. Owner-scoped status
  distinguishes pending, applied, terminal not-applied, and unresolved. After
  pruning, return an applied acknowledgement **only** if the remaining record
  still proves the owner, exact immutable batch, applied outcome and resulting
  cart version. If it proves only non-reuse, return **unresolved**: no new
  credit and no clearing or resubmitting guest units. A terminal not-applied
  marker is never an acknowledgement of consumption. Prepare must consult the
  same history, not revive pruned IDs. If history cannot be proven, return
  unresolved without mutation even on prepare. Account erasure,
  retention limits and recovery from backup must preserve non-reuse without
  retaining design content unnecessarily; a retention/recovery policy is
  required before enabling writes. Never mint a replacement on a timeout.

## Who may write, and at what instant?

The owner is the [internal User](../domain/user.md) resolved by the server
from the admitted session, not an email, Cognito subject alone, workspace,
staff role or body parameter. Require ordinary same-origin/CSRF checks on
all three mutating operations. When freezing the batch, bind it to the expected
account using an opaque server-issued write context; that context is a
precondition, never an independent credential. Never send A's pending design
from B's session. On every operation, including replay/status, compare the
authenticated owner with the recorded owner and scope all lookups accordingly.
A fresh admission for the **same** User may resume a prepared import.

Checking the principal only at request entry is insufficient. Proposed write
gate: use a **durable write-context record shared by the switchable sign-in
scope**, holding the current internal User, generation and active/revoked
state. All tabs that can replace one another's account must share that scope,
even with different pilot Qs. Independent devices need not share a generation.
Logout, account replacement and write-context retirement must update the
same record before the transition is considered effective for writes. Do not
create a second live write scope silently when continuity cannot be proved.

Prepare, apply and retire must revalidate the original immutable admission against
that record **inside** their database transaction, with the write-context
and ACTIVE User eligibility locked through commit. A user status change or
session transition must serialize against the same authority. If B's switch
commits first, A's old request cannot change either cart; if A's import
commits first, it can change only A's cart. A delayed acknowledgement for A
must neither display nor clear data in B's UI. Receipt reads also require a
fresh owner-scoped admission; never expose the other user's receipt.

This is a required property, **not** a claim about today's session registry.
The [local account-read pilot](../authentication-pilot.md#open-cookie-follow-up)
does not establish cross-context revocation for business writes. The common
scope bootstrap, transition protocol and eligibility fence need a separate
owner/security decision and independent evidence. Until then: **no cart writes**.

## One PostgreSQL transaction for apply

1. Lock and recheck the write authority as above. Lock the prepared import by
   its globally unique ID and check owner and state. If already applied,
   acknowledge only; if expired, unknown, mismatched or unavailable, do not
   touch the cart. Do this before reevaluating mutable catalog data on a replay.
2. Lock the owner's cart aggregate (also serialize first creation under a
   unique owner key). Every cart edit uses this same serialization point;
   edits based on a prior version must not overwrite a later import. Read
   current rows after acquiring the lock. Use one documented lock order across
   operations: authority/eligibility → import → cart → affected rows in stable
   key order. Bound waits. No session mutex, network round-trip or external
   catalog/asset call under these locks.
3. Merge each touched key once. Sum existing and imported units with checked
   arithmetic; retain the earliest existing line (stable ID breaks ties).
   Leave untouched rows alone. Preserve designs and requested units across a
   stock shortage. Distinguish a known retired variant (unavailable, no payable
   amount) from an unknown reference (reject the batch). Validate against
   authoritative, versioned local inputs; do not reserve stock or freeze prices.
4. In **that transaction**, give each retained design durable, owner-readable
   references to its exact immutable font/image content and advance the cart
   version once for a change; mark the import applied with its owner, ID,
   request identity and resulting version. A pending import pins its staged
   resources until resolved; an applied cart line pins them for as long as
   needed by that line. Staging or receipt cleanup must not revoke those
   references or remove their bytes. If the pins cannot be transferred atomically
   in the authoritative store, do not acknowledge or apply. Commit before success is returned.
   A rollback leaves neither the cart delta nor an applied receipt; the
   preparation remains pending. If COMMIT's outcome is unknown, reconcile
   using the same ID on the authoritative database. Never issue a second
   addition based on a missing HTTP response or a lagging replica. Only a
   confirmed receipt lets the browser release the captured guest additions.

## Checks required before implementation is accepted

| Scenario | Required evidence |
| --- | --- |
| Equivalent reordered wire fields, different browser font handles but identical validated bytes | One line. Changing one meaningful field, layer order, font bytes or template revision yields separate lines. A missing resource rejects the batch without partial changes. |
| Two tabs apply one batch; two devices apply independent identical batches | One increment for the first batch; both independent contributions count. A concurrent cart edit is retained or receives a version conflict, never silently overwritten. |
| Failure between writing lines and writing the receipt; lost response after COMMIT | First case: neither change commits. Second: owner-only retry confirms exactly one effect, even if the buyer edited the cart afterwards. |
| A prepares, then B signs in; logout or suspension is ordered against apply | No cross-account read/write and no stale mutation after the transition wins. A may resume only after fresh admission as A. No cart write is approved merely by passing browser/read tests. |
| ID reused with a changed quantity, different account, expired preparation or uncertain recovery | No fresh credit, no disclosure across owners. Guest data remains recoverable unless its consumption was confirmed. |
| Apply and retirement overlap; buyer then corrects the batch | Only one terminal result. A new ID is allowed only on confirmed not-applied; a late apply against the old ID has no effect. |
| A's retire waits; a switch to B or suspension of A commits first | No terminal marker or cancellation is written by that stale request, including for a never-prepared ID. Status alone cannot retire it. |
| Rich receipt and temporary upload storage have been cleaned | Without proof of the exact committed result, status is unresolved and guest units stay frozen. Independently, the cart owner on another device can still open the applied design with the original artwork and font. |

This proposal does not approve the [draft commerce specifications](../domain/README.md),
co-locate the current catalog and account profiles, or introduce payment, checkout
or account-cart APIs. Before implementation, decide how the commerce write store
obtains stable offer/template/asset facts independently of the local seed catalog.
