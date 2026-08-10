# Organization

## Purpose

An Organization represents a company, studio, workshop, team, or other business entity that operates on the Creastrix platform.

An Organization allows multiple Users to collaborate under a shared business identity.

An Organization acts as a single business participant on the Creastrix platform, regardless of its legal form.

An Organization may own Workspaces and thereby hold business rights to Projects in those Workspaces.

Specialized capabilities such as design publication and manufacturing are exercised through dedicated profiles where their respective domain rules define them.

Organization participation in commerce does not by itself determine seller-of-record, merchant identity, economic beneficiary, payment recipient, or payout identity.

## Responsibilities

An Organization is responsible for:

- representing a shared business identity;
- owning Workspaces and holding business rights to Projects through those Workspaces;
- managing Organization Memberships;
- collaborating through shared Workspaces;
- participating in specialized domain activities through dedicated profiles where the applicable domain rules require them.

## Relationships

An Organization:

- may own zero or more Workspaces;
- has one or more Organization Memberships;
- may be associated with zero or one Designer Profile;
- may hold zero or one Manufacturer Profile;
- may be the Effective Business Rights Holder for zero or more Projects through owned Workspaces.

## Business Rules

- An Organization must have one or more ACTIVE Organization Memberships with the role OWNER.
- Organization creation atomically creates an ACTIVE Organization Membership with the role OWNER for the creator.
- The last ACTIVE OWNER Membership cannot be removed, changed from OWNER, or transitioned out of ACTIVE until another ACTIVE OWNER Membership exists.
- An ACTIVE Organization Membership with the role OWNER is the structural source of general organization-level authority when no more specific domain delegation rule exists.
- Ordinary OWNER authority requires both an ACTIVE User and that User's ACTIVE Organization Membership with the role OWNER, in addition to any more specific domain requirements.
- A User account status change does not mutate the User's Organization Membership role or status and does not remove an ACTIVE OWNER Membership from the structural owner-preservation invariant.
- Suspending or deactivating the sole actionable OWNER is permitted for an independently authorized security or account workflow and does not require a replacement OWNER first because it does not change the structurally ACTIVE OWNER Membership.
- An Organization is operationally orphaned when no User simultaneously has ACTIVE User status and an ACTIVE Organization Membership with the role OWNER in that Organization.
- Operational orphaning is derived from current User and Organization Membership state. It is not a stored Organization status and does not rewrite Memberships, Organization identity, or ownership.
- Exceptional platform Organization recovery is available only while the Organization has no actionable OWNER and only after independent identity and business-control verification.
- Exceptional platform Organization recovery may restore an eligible SUSPENDED User to ACTIVE, establish a replacement ACTIVE OWNER Organization Membership for an appropriately verified User, and, where required, establish actionable Organization OWNER and Workspace ADMIN representation for affected Organization-owned Workspaces.
- Organization recovery may perform only the minimum coordinated Membership operations required despite the absence of an ordinary Organization actor.
- Organization recovery authority is not granted to a Workspace ADMIN, EDITOR, VIEWER, generic Organization member, profile Holder, buyer, or other domain participant merely through that role or relationship.
- Organization recovery never rewrites Organization or Workspace identity or ownership, Projects, Created By provenance, Orders, financial history, Profile Holder identity, or prior Membership history.
- Creating a Workspace owned by an Organization requires the acting User to be ACTIVE and to have an ACTIVE Organization Membership with the role OWNER in that Organization.
- Every Organization-owned Workspace must always have at least one User who simultaneously has an ACTIVE Organization Membership with the role OWNER in the owning Organization and an ACTIVE Workspace Membership with the role ADMIN in that Workspace.
- The Organization OWNER and Workspace ADMIN intersection is structural. Ordinary administration through that intersection additionally requires the User to be ACTIVE.
- If a User is the last User satisfying both requirements for an Organization-owned Workspace, no operation may remove that User's Workspace Membership, suspend it, change its role from ADMIN, remove that User's Organization Membership, change its role from OWNER, or transition it out of ACTIVE until that Workspace has another User satisfying both requirements. Different Workspaces may be protected by different replacement Users.
- An Organization cannot exist without at least one Organization Membership.
- An Organization-held Manufacturer Profile represents the Organization's specialized manufacturing-capability identity.
- In MVP, an Organization-held Manufacturer Profile may be created, managed, or used to accept made-to-order manufacturing work by a User with an ACTIVE Organization Membership with the role OWNER in the Organization, subject to Manufacturer Profile rules.
- Generic Organization Membership is insufficient by itself for Manufacturer Profile management or acceptance of manufacturing work; a future explicitly specified domain delegation rule may authorize another actor.

## Invariants

- An Organization always has at least one ACTIVE Organization Membership with the role OWNER.
- Every Workspace owned by an Organization always has at least one User who is both an ACTIVE Organization OWNER and an ACTIVE Workspace ADMIN for that Workspace.
- Every Organization Membership of an Organization belongs to that Organization only.
- An Organization cannot hold more than one Manufacturer Profile in MVP.

## Notes

An Organization is a first-class business participant of the platform and may own domain objects independently from individual Users.

Specialized capabilities use dedicated profiles only where their respective domain rules define them.

Organization does not by itself determine seller-of-record, merchant identity, economic beneficiary, payment recipient, or payout identity. Those remain future Payment and commerce architecture decisions.

Additional Organization roles, permissions, and domain-specific delegation beyond current ACTIVE OWNER authority remain future Organization authorization work.

An operationally orphaned Organization is a derived non-actionable condition, not a new lifecycle state or domain entity. The Organization may remain structurally valid while ordinary Organization authority is temporarily unavailable.

Different Organization-owned Workspaces may use different Users to restore actionable Organization OWNER and Workspace ADMIN representation during exceptional platform recovery.

Project business rights are derived from Workspace ownership in MVP; an Organization does not act as a separate direct Project Business Owner.

---

Status: APPROVED
Version: 1.5
