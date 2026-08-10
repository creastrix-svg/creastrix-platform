# Designer Profile

## Purpose

A Designer Profile represents the stable public professional design identity and platform-verified publication capability of exactly one User or Organization in Creastrix.

It is the publication identity through which Revision-based Listings may present design work without automatically establishing authorship, intellectual-property ownership, royalty entitlement, payout identity, seller identity, or manufacturing capability.

## Responsibilities

A Designer Profile is responsible for:

- representing a stable public designer identity;
- preserving its immutable Profile Holder relationship;
- managing one current public designer identity used for publication and any optional public professional presentation;
- recording its profile-level publication eligibility status;
- recording the User who created the profile record;
- providing a stable publication identity for Revision-based Listings;
- remaining stable for required historical publication traceability.

## Relationships

A Designer Profile:

- belongs to exactly one immutable Profile Holder, which is either one User or one Organization;
- has exactly one immutable Created By User;
- may be the immutable Publication Designer Profile of zero or more Revision-based Listings;
- may be identified in the immutable publication-context snapshots of zero or more Revision-based Order Items;
- has no mandatory Workspace relationship;
- has no direct Project, Revision, Royalty, Payment, Payout, or Manufacturer Profile relationship.

## Business Rules

- A Designer Profile Holder must be either one User or one Organization, but cannot be both.
- A User may hold zero or one personal Designer Profile in MVP.
- An Organization may hold zero or one Designer Profile in MVP.
- A User's zero-or-one Designer Profile Holder cardinality applies only to the User's directly held personal Designer Profile.
- Acting through an Organization-held Designer Profile does not make that profile User-held, create another direct User-to-Designer Profile Holder relationship, or count against the User's personal Designer Profile cardinality. The User is only an authorized acting User for that Organization-held profile.
- A User may act through multiple Organization-held Designer Profiles only through authority in their respective Holder Organizations. Those profiles remain held by their respective Organizations.
- The Profile Holder cannot be changed in MVP. A different Holder requires another Designer Profile or a future explicit transfer workflow.
- Created By records the User who created the profile record and does not determine the Profile Holder, authorship, intellectual-property ownership, design-specific publication rights, royalty beneficiary, payout identity, or permanent management authority.
- For a User-held profile, Created By may initially be the Holder User, but the two meanings remain separate. For an Organization-held profile, Created By is the User who created the profile while authorized to act for the Organization.
- A Designer Profile is created with the eligibility status UNVERIFIED.
- An UNVERIFIED Designer Profile may exist without a public display/studio name and before optional public presentation fields are complete.
- A Designer Profile eligibility status must be UNVERIFIED, VERIFIED, or SUSPENDED in MVP.
- UNVERIFIED means that the profile does not currently satisfy the platform verification required for new design publication.
- VERIFIED means that the profile currently satisfies profile-level platform eligibility for design publication.
- SUSPENDED means that the platform currently prevents the profile from participating in new publication or commerce regardless of other profile data.
- The allowed status transitions are UNVERIFIED to VERIFIED or SUSPENDED; VERIFIED to UNVERIFIED or SUSPENDED; and SUSPENDED to UNVERIFIED or VERIFIED.
- Only a platform-authorized verification or moderation workflow may change eligibility status. Holder authority does not permit self-assignment of VERIFIED.
- A Holder may manage permitted public presentation information and submit or request verification or re-verification subject to the applicable workflow.
- To receive or retain VERIFIED status, a Designer Profile must have one valid non-empty current public display/studio name used for publication.
- A current public display/studio name may change prospectively, but a later rename never rewrites the confirmation-time name preserved by an Order Item.
- VERIFIED status does not prove authorship, intellectual-property ownership, the right to publish a specific Revision, royalty entitlement, payout eligibility, or seller identity.
- Every Revision-based Listing additionally requires independent design-specific publication-rights validation and sufficient current embedded rights context or basis under Listing rules.
- A User-held Designer Profile is created, managed, and used as publication context through authorization of its Holder User.
- An Organization-held Designer Profile may be created, managed, submitted for verification, or used as publication context by a User with an ACTIVE Organization Membership with the role OWNER in the Profile Holder Organization, unless a future explicit delegation rule authorizes another actor.
- Generic Organization Membership, Workspace Membership, and the PROJECTS, READY_MADE_PRODUCTS, and LISTINGS scopes do not by themselves authorize management or use of an Organization-held Designer Profile.
- Every Revision-based Listing selects exactly one Publication Designer Profile at creation. Association requires effective LISTINGS write authorization in the source-derived Workspace and authority to act through the selected Designer Profile.
- The selected Publication Designer Profile does not need to be VERIFIED merely to create a DRAFT Listing, but only a VERIFIED profile can support Listing activation, reactivation, or effective orderability for a new purchase.
- A Publication Designer Profile relationship never changes during the lifetime of its Listing. Publishing the same Revision through another Designer Profile requires another Listing in MVP.
- One Designer Profile may publish Revision-based Listings from multiple Workspaces when each Listing independently satisfies Workspace authorization, profile authority, source, design-specific rights, royalty, and other business rules.
- Publication context must select one Designer Profile explicitly and is never inferred from Organization Membership, Workspace ownership, Workspace Membership, or a Created By User.
- A Ready-Made Product Listing has no Publication Designer Profile merely because of its source type.
- Loss of Designer Profile eligibility or design-specific publication rights does not change Listing lifecycle automatically, but makes an ACTIVE Revision-based Listing effectively non-orderable for new purchases while the loss continues.
- Effective orderability may recover when the same Designer Profile and design-specific rights become eligible again and every other current Listing requirement passes.
- Order confirmation preserves the required immutable publication-context snapshot through Order Item. Later status or presentation changes never rewrite confirmed Order Items, existing Royalties, or other historical commerce.
- The Designer Profile and its Holder do not automatically determine Workspace ownership, Project or Revision ownership, Project business rights, legal authorship, copyright or intellectual-property ownership, royalty beneficiary, payout identity, seller identity, or manufacturing capability.
- A Designer Profile referenced by any existing Revision-based Listing cannot be destructively deleted, whether that Listing is DRAFT, ACTIVE, PAUSED, or ARCHIVED.
- A Designer Profile required by a confirmed Order Item historical publication snapshot cannot be destructively deleted.

## Invariants

- A Designer Profile always has one stable identity.
- A Designer Profile always has exactly one immutable Profile Holder.
- The Profile Holder is always either exactly one User or exactly one Organization, never both.
- The Profile Holder remains unchanged in MVP.
- A User never holds more than one personal Designer Profile in MVP.
- An Organization never holds more than one Designer Profile in MVP.
- A Designer Profile always has exactly one immutable Created By User.
- A Designer Profile always has exactly one eligibility status: UNVERIFIED, VERIFIED, or SUSPENDED.
- A VERIFIED Designer Profile always has one valid non-empty current public display/studio name used for publication.
- Only a VERIFIED Designer Profile can support new Revision-based Listing activation, reactivation, or Order confirmation.
- Every Revision-based Listing published through a Designer Profile retains the same Publication Designer Profile throughout its lifetime.
- Designer Profile changes never rewrite immutable historical publication or commercial snapshots.

## Notes

Designer Profile is not User, User Profile, Organization, Organization Membership, Workspace, Project, Revision, Listing, authorship, intellectual-property ownership, Royalty beneficiary, payout account, seller identity, or Manufacturer Profile.

The public display/studio name is the profile's current public designer identity used for publication. An UNVERIFIED profile may exist without that name, while optional presentation may include a public bio, avatar or logo reference, and portfolio-oriented presentation. Exact database field naming, slugs, URLs, social links, follower counts, media architecture, portfolio projection, and public visibility policy remain future product and implementation work.

Profile-level verification and design-specific publication-rights validation are separate concepts. No Designer Verification, Design Right, License, Contributor, Design Team, Portfolio, Media, Social Link, or Payout Profile entity is introduced by this specification.

Designer Profile and Manufacturer Profile are independent capabilities. A User or Organization may hold both without either profile granting authority in the other domain.

Creastrix may publish its own original designs through a normal Organization-held Designer Profile belonging to the Creastrix Organization. No PLATFORM Designer Profile or PLATFORM Profile Holder type is introduced.

Future Designer Review may use the stable Designer Profile identity preserved by historical Order Item publication context, but review eligibility, lifecycle, aggregation, and moderation remain future work.

Internal verification evidence is not automatically public. Exact moderation reasons, verification evidence, voluntary closure, deletion of a never-used UNVERIFIED profile, and retention duration remain future policy and implementation work.

---

Status: DRAFT

Version: 0.1
