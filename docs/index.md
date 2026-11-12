# Creastrix Documentation

This page helps readers find the correct source. It is a navigation aid and does not approve architecture or replace domain specifications.

## Project Orientation

- [Repository README](../README.md) — current repository and implementation orientation.
- [Platform Overview](platform-overview.md) — conceptual product-level introduction.
- [Project Context](../creastrix-project-context.md) — technical memory, current decisions, and delivery sequence.
- [Glossary](glossary.md) — supporting terminology.
- [Domain Map](domain-model.md) — current supporting conceptual and navigational map of the domain documentation.

## Domain Architecture

- [Domain Specifications Index](domain/README.md) — current specification statuses and links to individual specifications.
- [Entity Template](domain/entity-template.md) — required structure for domain specifications.
- [Cross-domain decision notes](engineering/domain-decision-notes.md) — relationships and constraints, including draft commerce rules; check the source specification before implementation.

## Engineering and Contribution

- [Team Code](../creastrix-team-code.md) — durable engineering culture and principles.
- [Agent Instructions](../AGENTS.md) — operational governance for repository work.
- [Backend README](../backend/README.md) — backend setup and implementation guidance.
- [Frontend README](../frontend/README.md) — local startup, feature locations and verification.
- [Connected local catalog](local-catalog.md) — run the buyer catalog and product cards against a dedicated PostgreSQL database.
- [Guest cart at sign-in](engineering/guest-cart-merge.md) — proposed account-cart merge rules and buyer acceptance examples; not an implemented API.
- [Account cart import contract](engineering/account-cart-import.md) — proposed design equality, atomic receipt and current-account write gate.
- [Account-cart storage decision](engineering/account-cart-activation.md#accepted-storage-decision) — catalog and design ownership, file lifetimes, and gates before enabling writes.
- [Media storage](engineering/media-storage.md) — private originals, catalogue previews and the boundary between PostgreSQL and file storage.
- [Personalisation zones](frontend-personalization-zones.md) — configured buyer tools and the gap to production fulfillment.
- [Local account pilot](authentication-pilot.md) and [provider migration](authentication-provider-migration.md) — current boundaries and operator procedures.
- [Engineering history](engineering/integration-history.md) — dated integration and verification records, separate from current instructions.

## Authority Boundaries

The latest independently approved version of an APPROVED specification is authoritative for its accepted entity decisions. DRAFT specifications are active architecture work and are not independently approved.

The project context is technical memory. The accepted integrated repository baseline defines the current integrated implementation state, and repository evidence must be inspected to determine implementation coverage. Repository presence alone does not prove deployment, full delivery, or completion of every approved rule.

This index, the platform overview, glossary, and domain map are supporting documents. They do not replace specifications or approve architecture. See the [Domain Specifications Index](domain/README.md) for current specification statuses.
