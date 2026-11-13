# Run the persistent local cart candidate

**What is connected:** the existing React catalog, product card and cart use
HTTP. A listed offer and its variants come from a Workspace-owned PostgreSQL
catalog. A synthetic buyer signs in through a local signed OIDC flow and reads
their own cart. Each line points to its stable offer/variant and its applied
design revision's template, independent of the latest template. An explicit
source-image request can read a single eligible PNG through its authorised
cart line. For a supported layered print design the buyer can instead open
**View applied design**, which loads the composition and its private files through that cart line.

**What the isolated writer does:** before leaving the storefront for sign-in,
it seals the guest contribution in IndexedDB. After sign-in, only matching
proof from that exact login lets the verified buyer claim it. The real client
obtains the current session write context, reconciles the owner-scoped receipt,
prepares and applies a supported batch, and acknowledges it locally only after APPLIED.
The HTTP protocol also supports explicit retirement. A failed network read
can be retried with a GET. Only a classified binding rollback permits
same-session recovery, which never adds units.

**Coverage limit:** the importer accepts plain lines for known catalog SKUs and applied
Loop designs using the supported surface-print template (revision 2). It freezes
the ordered image/text layers, geometry and styling, and only the files those layers
reference: the studio-normalised PNG and supported TTF/OTF fonts. It does not
rebuild the PNG from its thumbnail or substitute an operating-system font.
The local v2 template pins the bundled Nunito, Cormorant Garamond and Caveat
files; uploaded fonts keep their own bytes. WOFF2 is outside this importer.
The existence of a newer template does not itself invalidate a previously
applied design: the server must still recognise its original revision and variant.
The importer does not
import unrelated drafts. An unsupported format, missing resource or legacy design
without its pinned template keeps the **whole batch** from being credited. The
application never converts an unsupported design into a plain line. Adding a
product after sign-in is a separate purchase action, not another import. The
shared storefront also supports editing existing account rows, as described below.
Checkout, file collection and writes for real users are not enabled. None of the local results clear the
[business write gate](account-cart-import.md) for a public deployment.

## Start on loopback

### Use the same catalog as the storefront

Keep the [catalog service](../local-catalog.md) running on port 8081. A guest
on port 4174 and a signed-in test buyer on `http://localhost:4317` read the
same product feed, gallery, published cover, prices and stock. The account
database stores cart lines, receipts, private designs and stable historical
references; it is not the authority for what is on sale.

After compiling the current backend and applying the catalog migrations through
its own launcher:

```sh
bash backend/tools/account-cart-local.sh setup-shared-catalog
bash backend/tools/account-cart-local.sh server-shared
# In another terminal:
cd frontend
npm run dev:shared-cart
```

Use a clean local Chrome profile for synthetic sign-in. The shared launcher
and the earlier fixture launcher are alternatives on the **same ports**. Do
not combine the catalog and authentication Spring profiles. Real Cognito,
checkout, stock allocation and public writes are outside this workflow.

Sign in and open the catalog. Any available variant of a published offer can
be added plain without a SKU-specific code change. The server checks current
availability even if the buyer has an old page open. Optional personalisation
also requires an active template the editor supports. Applying a design is not
a purchase: use **Add to cart** on the product card. After reload, **View
applied design** on the cart line opens the account's saved composition.
Opening that line returns to the saved design rather than the plain product.
If the seller no longer offers the template, that link does not offer a plain
replacement. A compatible surface on a new product can use the flat editor
without requiring a product-specific 3D model.

For the personal library, save directly in the studio using **Save design**.
The confirmation leaves the studio open. Go to **Account → My designs** to see
your work after closing the page. Open a saved entry, **Apply design**, go back
to its product and explicitly **Add to cart**. Edit the library entry and
save again: the existing cart line's design and quantity do not change.
The library also offers preview, independent copies and deletion. Unsupported
or withdrawn offers are not silently replaced with an undecorated item.

A guest choosing **Save design** sees an account prompt without leaving the
studio. Cancel returns to editing; only **Sign in** prepares the draft for the
sign-in flow. Registration is unavailable with the local synthetic issuer.
The library link is shown only to signed-in buyers. In **My designs**, confirm the save into the signed-in
account. Ordinary guest drafts are not transferred by logging in, and any
unsent or expired handoff is not proof of ownership. The library APIs exist
only in the dedicated local shared writer; default, live Cognito and public
order flows are unchanged. See [storage and retention limits](account-cart-activation.md#my-designs-local-buyer-library).

On **Your cart**, the quantity control, line subtotal and summary use the same
layout as the guest cart. Change the quantity and refresh: it is read back from
the account, not from browser storage. **Remove** crosses the line out of the
total without losing its saved design. While the cart stays open, **Restore**
on the dimmed row restores the previous quantity if it is still available; **×**
dismisses only that removed row. After leaving the cart or refreshing, the
removed row and its undo control are no longer displayed. If another tab changed the cart, read its latest
state before submitting a new decision. Retrying an action whose response was
lost does not apply it twice.

### Earlier isolated fixture

Use a JDK 25, the repository frontend toolchain and local PostgreSQL server
tools. Use a **fresh isolated browser profile** for this synthetic application,
not a browser profile signed into another localhost service: cookies are not
isolated by port. Do not use real credentials. Do not combine Spring profiles.

From the repository root:

```sh
bash backend/tools/account-cart-local.sh start-db
bash backend/tools/account-cart-local.sh compile
bash backend/tools/account-cart-local.sh server
```

After Spring finishes Flyway, in another terminal:

```sh
bash backend/tools/account-cart-local.sh seed
cd frontend
npm ci
npm run dev:account-cart
```

| Service | Address |
| --- | --- |
| Existing React storefront | `http://localhost:4317` |
| Spring / dedicated synthetic OIDC issuer | `127.0.0.1:8191` / `127.0.0.1:8192` |
| Separate PostgreSQL | `127.0.0.1:55489/creastrix_account_cart_local` |

### Add two Tide cups after sign-in

In an isolated Chrome profile, sign in at `http://localhost:4317/login` **before**
adding anything. Open [Tide](http://localhost:4317/products/tide), select 2, click
**Add to cart** once, and follow **View cart**. If the account had no Tide line
beforehand, it should show one plain Tide / Cobalt blue line with quantity 2.
Reload the cart: this is a read, not another addition. Do not empty an existing
buyer's cart to run this check.

Each deliberate addition has its own ID. Retrying the same ID and quantity
acknowledges the original addition; a genuinely new click after success adds
more units. The server checks the signed-in buyer, enabled variant, accumulated
quantity and current stock. It commits the quantity and its acknowledgement in
one PostgreSQL transaction. There is no stock reservation, guest transfer or order.
If a valid addition is refused because the item is unavailable, that intent
remains refused even if the item returns to stock. A purchase after restocking
requires a new click. If a response is lost instead, **View cart** reads the
server state; retrying the pending click uses its original ID and quantity.
The pending action is remembered in the same browser tab, not across devices.
For a read-only check, compare `snapshot-json` and `snapshot-additions` from
`backend/tools/account-cart-local.sh` before and after reloading.

The earlier isolated fixture enables only `tide-cobalt` and seeds its starting catalog
values once. In this profile the card and write use the same permanent catalog
row; the separate seller-photo storefront on port 4174 is **not** a live feed of
price, stock or media into this database. Reseeding does not reset a user's cart.

### Other local journeys

Open `/products/kiln-cup?variant=blue`, then `/login` and use the hosted
sign-in button. The test issuer selects only the configured synthetic buyer;
it does not send mail or contact Cognito. Open `/preview/cart`: the initial
account has Kiln Cup / Blue glaze plain ×2 and Checker motif ×1, applied
against template 1. Inspect the source image or return to the referenced
product. The initial quantities apply only to a new database: a real import
advances the version and leaves its receipt in that database. The fixture
does not overwrite a previously installed buyer cart or recreate deleted lines.

For the personalised journey, start as a guest at `/products/loop`, open the studio,
upload a picture and/or a compatible custom font, apply the design and add it
to the cart. Sign in, then open **View applied design** on the corresponding cart
row. Repeating the sign-in without new guest items must preserve the line, quantity
and receipt count. Use a second **clean browser profile** signed in as the same
synthetic owner to distinguish a server-backed preview from the guest's IndexedDB.
See the [file storage guide](media-storage.md) for ownership and deployment boundaries.

The test-classpath launcher is not shipped in the application jar. Development
mode is opt-in; the ordinary build and `local-catalog` do not install this
transport. A fresh backend uses the regular foundation migrations **plus** the
separate `db/account-cart-local` location. No business data is in the migrations.
The [SQL fixture](../../backend/src/test/resources/account-cart-local/fixture.sql)
is explicit and separate. Stop the frontend/backend with Ctrl+C, then run
`bash backend/tools/account-cart-local.sh stop-db`. Credentials, the cluster
and private files are retained under `${XDG_DATA_HOME:-$HOME/.local/share}/creastrix/account-cart-local`.
`start-db` + `server` reopens the same records. There is no purge command.
The server reserves an owner-only `parser-tmp/` next to `assets/`: the JDK
may spool a font there during validation. This scratch area is private, not a
source for restoring designs and never a static web directory.
If overriding `XDG_DATA_HOME`, choose a private location outside **all** directories
served by any local web server. The launcher rejects paths inside this checkout
(including resolved symlink ancestors); it cannot discover unrelated web servers' roots.
In this bounded local importer, a batch whose touched SKUs and existing designs exceed
the service's 256-resource verification limit is refused intact, not partially credited.

### Optional two-buyer verification

Use only the dedicated synthetic lane, with a fresh Chrome profile and no
other operator using it. The ordinary `server` remains A-only. Stop only that
synthetic Spring process before starting `server-two-buyers` instead; never
combine this with the real account or catalog profiles. After the normal seed,
`seed-two-buyers` provisions a separate ordinary buyer B with an initially
empty private cart. It does not overwrite A or reseed either cart.

Run `next-buyer-b` immediately before B's next ordinary hosted sign-in. The
test provider consumes that choice once and returns to A by default;
`next-buyer-a` cancels a pending B selection. These are subcommands of
`bash backend/tools/account-cart-local.sh`, not buyer-facing endpoints.
Use `snapshot-json` (A) and `snapshot-json-b` (B) for independent SQL
observations. When finished, stop the two-buyer Spring process and use the
ordinary launcher again. No change to the real Cognito tenant is involved.

For acceptance on the reserved synthetic lane, if Playwright and Chromium
are already installed:

```sh
# Set CART_PLAYWRIGHT_MODULE and CART_CHROME if the installations aren't on the default paths.
node backend/tools/verify-account-cart-local.cjs read
node backend/tools/verify-account-cart-local.cjs import
node backend/tools/verify-account-cart-local.cjs auto
node backend/tools/verify-account-cart-local.cjs manual
```

Each command uses its own fresh Chrome context, real sign-in navigation,
actual HTTP and PostgreSQL. `import` **adds three units** to the retained
plain row and one receipt; it then signs out and back in and checks that no
second credit occurred. `auto` and `manual` deliberately roll back the next
one or two binding transactions in the test database. They require the same
original servlet session and internal User, the expected recovery POSTs and
unchanged lines, IDs, design and receipts. The source image must equal the
original 90-byte fixture. The runner never injects Redux, fabricates an HTTP
response or resets cart data. Run one scenario at a time.

For a manual Chrome check, use `bash backend/tools/account-cart-local.sh snapshot-json`
before and after. Use `fault-once` or `fault-twice` in place of `snapshot-json`
**before the next login** to arm a genuine, synthetic deferred database error.
With `fault-twice`, after the one automatic retry fails, click **Retry** in
the cart; this is distinct from **Retry loading cart** after a network outage.
Fault control exists only in the [dedicated SQL fixture](../../backend/src/test/resources/account-cart-local/lane-fixture.sql),
never as an HTTP endpoint. Do not run tests on a lane another operator is verifying.

## Local write acceptance gate

The owner [selected a single server-assigned write context for the synthetic
test sign-in](account-cart-activation.md#accepted-local-test-sign-in-write-scope).
That choice does not enable writes in the normal account pilot. The following
checks establish whether this implementation meets the selected scope.

- For this test, provision **one explicit server-side write scope for
  one isolated browser lane**. The ordinary launcher admits A; the optional
  two-buyer launcher admits A and B solely for the local handoff checks.
  Do not silently create a
  second lane or use an incoming user ID, browser localStorage, a Q cookie,
  a filename, or a User-wide lock as authority. This choice does **not** solve
  bootstrap for arbitrary devices or independently provisioned lanes.
- Disable cart write routes by default. An opt-in test process must refuse
  real issuers, identities, database roles, external origins and mixed profiles.
  Capture the admitted session, immutable owner and both the write-scope and
  User eligibility generations. Login/account replacement and logout must fence
  the old writer in PostgreSQL before the new write admission is effective.
  All side-effecting operations, **including prepare and retire**, recheck the
  original authority and ACTIVE User under locks through commit. If continuity
  or the outcome of a transition is uncertain, make no shopping write.
- A known binding rollback may leave login successful without cart access.
  Recover using a durable claim for **that session, owner and lane generation**:
  once automatically, then explicit Retry on the same ticket. After a switch,
  suspension or lost authority, refuse the old claim. Recovery only binds;
  never prepare/import/clear lines. An uncertain commit needs authoritative
  status, not a second binding or an import under a new identity.
- Introduce durable guest **contribution identity at mutation**, not by hashing
  the latest basket snapshot. Two plain units can be removed and freshly added,
  yielding the exact same persisted basket bytes. Freeze contributions and
  pin their applied template/asset versions before sending; do not include
  unrelated drafts. No partial or plain fallback for unresolvable personalisation.
  Prepare and apply the same owner-bound batch; release captured units only
  after a matching durable receipt. Retain pending data on failure or unknown
  commit. Never compact receipts in this local experiment.
- Before activation, independently check cross-generation ordering, status
  revocation, exact resource equivalence, transaction rollback and replay.
  Then use the actual React route to prove first import, repeated login with
  no duplicate units, and the two **binding** recovery paths with unchanged
  cart/receipt rows. Do not count a network GET retry as either recovery path.
