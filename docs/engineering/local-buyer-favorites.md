# Saved products in the local buyer storefront

This slice is for the local synthetic buyer only. It does not introduce an
approved commerce domain entity or enable writes in the default app, the
Cognito pilot or a public deployment.

**What is saved.** A heart remembers one specific catalog SKU (the product
variant), owned by the internal buyer ID. Two colours are two choices.
Favourites do not reserve units or store quantities, prices, artwork or
personal designs. Zero stock does not, in itself, prevent a buyer from saving
a variant. A withdrawn variant must not be replaced by another colour.

**Signing in.** Add only the guest selections tied to this particular sign-in.
An existing account selection and the same guest selection produce one heart.
An old browser marker with no evidence of guest ownership is not proof that
the next buyer owns it. Account selections must not become guest selections
on logout, or be handed to the next buyer using the same browser.

**Retrying.** Ask to save or remove a specific variant, rather than toggling
whatever the server happens to hold. Store the guest import and its receipt
atomically. A retry of an already confirmed import is a read of its outcome:
it must not restore a heart that the buyer has since removed. A failed or
unconfirmed write must not be presented as a saved change.

**Opening the list.** If shopping access expires between opening the cart
session and fetching favourites, hide the old list and offer **Sign in again**
on the saved-products page. A temporary service outage instead offers
**Try again** without signing out.

**Acceptance.** Select two colours of Loop as a guest, sign in, then verify
the two variants in a clean browser profile. Remove one and sign in again:
only the other remains. A different buyer does not inherit those selections.
The same rules apply to other catalog SKUs without product-specific changes.

See also the [local buyer runbook](account-cart-local-run.md) and the
[cart's local session scope](account-cart-activation.md#accepted-local-test-sign-in-write-scope).
