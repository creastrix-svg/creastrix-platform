# Designer Review

## Purpose

A Designer Review represents one purchase-backed evaluation authored by exactly one Buyer User of exactly one Designer Profile publication identity, grounded in exactly one FULFILLED Revision-based Order Item whose immutable publication-context snapshot identifies that Designer Profile.

It evaluates the purchased Designer Profile publication identity without evaluating or establishing Creastrix as seller, Manufacturer Profile performance, Shipment performance, legal authorship, intellectual-property ownership, design-specific publication rights, Royalty entitlement, Payout entitlement, or Workspace authority.

## Responsibilities

A Designer Review is responsible for:

- representing one stable buyer-authored review identity;
- preserving its immutable Reviewer User relationship;
- preserving its immutable qualifying Order Item relationship;
- preserving its immutable target Designer Profile relationship;
- holding one required current integer rating and optional current textual body;
- managing the PUBLISHED, HIDDEN, and WITHDRAWN lifecycle;
- preserving creation, content-update, and lifecycle-transition timing;
- preserving sufficient accepted moderation context for auditability;
- providing the authoritative source for current Designer Profile review aggregation.

## Relationships

A Designer Review:

- has exactly one immutable Reviewer User;
- belongs to exactly one immutable qualifying Order Item;
- targets exactly one immutable Designer Profile;
- has no Created By relationship;
- has no direct Listing, Project, Revision, Personalization, Payment, Payment Allocation, Royalty, Payout, Shipment, Manufacturer Profile, or Workspace relationship.

## Business Rules

- Designer Review is a dedicated domain entity. No generic polymorphic Review entity is introduced in MVP.
- A Designer Review may be created only for an Order Item whose immutable source type is FINALIZED Revision, whose immutable publication-context snapshot identifies exactly one Publication Designer Profile, and whose lifecycle state is FULFILLED.
- Shipment DELIVERED alone is insufficient when the Order Item has not reached FULFILLED.
- A CONFIRMED, IN_FULFILLMENT, CANCELLED, or Ready-Made Product Order Item cannot qualify for Designer Review in MVP.
- The Reviewer User must be exactly the Buyer User of the qualifying Order Item's Order.
- Guest, Organization, delegated, and Workspace reviewers are unsupported in MVP.
- Reviewer User itself represents review authorship and creation provenance. Platform moderation actors are operational provenance rather than review authors.
- One qualifying Order Item may have zero or one Designer Review, and one Designer Review belongs to exactly one qualifying Order Item.
- Order Item quantity greater than one still permits at most one Designer Review for that purchased line.
- The same Buyer may create another Designer Review targeting the same Designer Profile only through another distinct qualifying Order Item.
- A WITHDRAWN Designer Review continues to occupy the one-review slot of its qualifying Order Item. No replacement Review for that Order Item is supported in MVP.
- The target Designer Profile must equal the Publication Designer Profile identity preserved by the qualifying Order Item's immutable publication-context snapshot at creation.
- The target is never inferred from current Listing, Workspace, Profile Holder, Created By, Revision Created By, Royalty beneficiary, or Manufacturer Profile.
- Historical product and design traceability derives through the qualifying Order Item. Designer Review does not duplicate direct Listing, Project, or Revision relationships.
- A Designer Review has one required integer rating whose value is exactly 1, 2, 3, 4, or 5.
- Zero, values greater than five, fractions, half-stars, and category ratings are unsupported in MVP.
- A Designer Review may have an optional textual body. When present, the body must contain meaningful non-empty text after applicable normalization.
- A rating-only Designer Review is valid.
- Designer Review has no title, media, image, video, Designer response, or helpful-vote content in MVP.
- Designer Review does not preserve an immutable public Reviewer name or User Profile presentation snapshot in DRAFT 0.1.
- The immutable Reviewer User relationship is authoritative internally. A public MVP projection may use only a safe non-identifying or product-approved pseudonymous attribution such as a localized equivalent of "Verified buyer."
- Public review presentation never automatically exposes the Reviewer's legal or full name, email, address, User Profile contact information, or authentication identity.
- Purchase-backed status is derived from the valid immutable Designer Review, Order Item, Order Buyer, source, publication-context, and FULFILLED relationships. No verified-purchase boolean is stored.
- Designer Review does not duplicate the target Designer Profile display or studio name. Historical purchase-time display attribution remains in the qualifying Order Item publication-context snapshot, while a current Designer Profile page may use current profile presentation.
- Review composition occurs before entity creation in UI or workflow. Successful valid creation creates the Designer Review directly in PUBLISHED.
- No persistent DRAFT state or arbitrary review-expiration window exists in core MVP.
- A Designer Review has exactly one lifecycle state: PUBLISHED, HIDDEN, or WITHDRAWN.
- PUBLISHED means that the Review is publicly eligible and contributes to current Designer Profile review aggregation.
- HIDDEN means that authorized platform moderation suppresses public presentation and aggregation while preserving Review identity, content, relationships, and history.
- WITHDRAWN means that the Reviewer has voluntarily and permanently removed the Review from public presentation in MVP while its identity, content, relationships, and history remain preserved.
- The only lifecycle transitions are PUBLISHED to HIDDEN and HIDDEN to PUBLISHED by authorized platform moderation, and PUBLISHED to WITHDRAWN or HIDDEN to WITHDRAWN by the Reviewer User.
- WITHDRAWN is terminal in MVP and never transitions to PUBLISHED or HIDDEN.
- Content editing while PUBLISHED or HIDDEN is not a lifecycle transition.
- The Reviewer User may edit the current rating and body while the Review is PUBLISHED or HIDDEN.
- Editing preserves Review identity, Reviewer User, qualifying Order Item, target Designer Profile, and immutable creation timestamp and updates the applicable content-updated timestamp.
- Editing a HIDDEN Review never republishes it automatically. Authorized platform moderation may inspect the updated content and later perform HIDDEN to PUBLISHED.
- Only authorized platform moderation may perform PUBLISHED to HIDDEN or HIDDEN to PUBLISHED.
- Moderation may address abuse, spam, fraud, policy, or legal concerns and preserves sufficient timestamp, reason or evidence context, and actor or workflow provenance for auditability.
- Platform moderation does not silently rewrite Reviewer content merely because it can hide or republish a Review.
- The Reviewer User may withdraw the Review while it is PUBLISHED or HIDDEN.
- Target Designer Profile Holder authority does not grant authority to edit, hide, withdraw, delete, or republish a buyer Review.
- Organization Membership, Workspace Membership, Workspace roles, and Workspace permission scopes do not grant Designer Review mutation authority. No REVIEWS Workspace permission scope exists in MVP.
- The Reviewer User may view the User's own Review regardless of public lifecycle state. Public access is limited to the safe projection of PUBLISHED Reviews, and authorized platform moderation or administration may inspect the context required for its responsibilities.
- Later target Designer Profile rename, UNVERIFIED, VERIFIED, or SUSPENDED status, or Holder authority change never invalidates, retargets, rewrites, or automatically hides a Designer Review.
- If a Designer Profile public page becomes unavailable under separate profile presentation or moderation policy, the Designer Review lifecycle and historical record remain unchanged.
- A target Designer Profile cannot be destructively deleted while referenced by a Designer Review.
- A Designer Review cannot be destructively deleted after creation in MVP. HIDDEN and WITHDRAWN preserve its identity and history.
- Reviewer User deactivation does not rewrite Reviewer authorship or Review history.
- Once a qualifying Revision-based Order Item legitimately reaches FULFILLED, a later partial or full refund does not remove Designer Review eligibility or automatically delete, hide, withdraw, or change the rating of an existing Review.
- A Buyer may create the Review after a later refund when the Order Item remains historically FULFILLED and every other creation rule passes.
- Fraud or abuse associated with a refunded purchase may be evaluated independently through platform moderation.
- Designer Review never determines or changes Payment, Refund Allocation, Manufacturer compensation, Royalty, Royalty beneficiary, Payout eligibility, or Payout and never proves authorship or intellectual-property ownership.
- A qualifying made-to-order Order Item may identify both a Publication Designer Profile and a Manufacturer Profile, but Designer Review targets only the Publication Designer Profile. No Manufacturer rating is created or transferred.
- A public PUBLISHED projection may expose rating, optional body, safe approved Reviewer attribution, target Designer Profile identity or presentation, and a derived verified-purchase marker.
- Optional product or design context may be exposed only through a future explicitly approved safe public projection.
- Public Review presentation never automatically exposes Order or Payment identity, delivery destination, Manufacturer compensation, Royalty, Payout, internal moderation evidence, private fulfillment context, or private Order Item data.
- The direct qualifying Order Item relationship never grants public access to that Order Item or to Personalization selected values, generated output, production artifacts, or private Personalization snapshots.
- Current Designer Profile average rating and review count are derived only from current PUBLISHED Designer Reviews. HIDDEN and WITHDRAWN Reviews are excluded.
- Designer Profile does not own authoritative stored average-rating or review-count fields. Rebuildable materialized projections or caches may support implementation.
- Designer Review creation atomically validates the exact Order Item, Revision-based source, FULFILLED lifecycle, publication-context target identity, Reviewer and Buyer equality, and absence of another Designer Review for that Order Item.
- Concurrent creation attempts for the same Order Item permit at most one Designer Review to be created. Database uniqueness or equivalent serialization may implement this rule without a Review Reservation entity.

## Invariants

- A Designer Review always has one stable identity.
- A Designer Review always has exactly one immutable Reviewer User.
- A Designer Review always belongs to exactly one immutable qualifying Order Item.
- A Designer Review always targets exactly one immutable Designer Profile.
- The Reviewer User always equals the Buyer User of the qualifying Order Item's Order.
- The qualifying Order Item is always Revision-based, has reached FULFILLED, and preserves the target Designer Profile identity in its immutable publication-context snapshot.
- The target Designer Profile always equals the Publication Designer Profile identity preserved by the qualifying Order Item.
- One Order Item never has more than one Designer Review in MVP.
- A Designer Review rating is always an integer from 1 through 5 inclusive.
- A Designer Review always has exactly one lifecycle state: PUBLISHED, HIDDEN, or WITHDRAWN.
- WITHDRAWN is always terminal in MVP.
- Only PUBLISHED Designer Reviews contribute to current Designer Profile review aggregation.
- Reviewer, Order Item, target Designer Profile, and creation timestamp never change after Review creation.
- Editing never changes lifecycle state by itself.
- Refund, financial, profile-status, and Workspace changes never rewrite Designer Review identity, authorship, target, or qualifying-purchase history.
- Designer Review public presentation never exposes private Order, Payment, fulfillment, Personalization, or User Profile data merely through its relationships.
- Designer Review identity, immutable relationships, and accepted lifecycle or moderation history are never destructively deleted or rewritten.

## Notes

Designer Review is not a generic Review, Product Review, Manufacturer Review, review of Creastrix as seller, Shipment review, authorship record, intellectual-property decision, publication-right record, Royalty right, Payout right, or Workspace permission.

Future Manufacturer Review remains a separate domain because its qualifying relationship, target, eligibility, and meaning differ. Shared review abstractions should be extracted only if later concrete domains demonstrate common behavior that justifies them.

No Review Revision, Review Response, Review Vote, Review Media, Moderation Case, Purchase Verification, Designer Rating Aggregate, or Review Reservation entity is introduced in MVP. Designer responses, threaded discussion, votes, media, and detailed moderation workflow remain future work.

The current User and User Profile specifications do not define an authoritative public User presentation identity. Future explicit public User presentation policy may permit safe optional reviewer attribution without changing immutable Reviewer authorship or purchase eligibility.

Exact moderation evidence retention, Audit Log integration, legal erasure, pseudonymization, text limits, normalization mechanics, localization, public projection design, and retention duration remain future product, legal, compliance, and implementation work.

---

Status: DRAFT

Version: 0.1
