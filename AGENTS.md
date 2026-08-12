# Creastrix Agent Instructions

## Required Context

Before working on this repository, read these files completely:

- `creastrix-project-context.md`
- `creastrix-team-code.md`

The current checkout must be inspected as primary evidence of what is actually present in that checkout. The accepted integrated repository baseline defines the current integrated implementation state. Working-tree changes and unreviewed or unmerged feature-branch changes are proposed changes, not automatically accepted implementation state or architecture.

The latest independently approved version of an APPROVED domain specification is authoritative for its accepted domain decisions. Editing a specification does not approve its new content merely because its existing header still says `APPROVED`.

The project context is technical memory. It preserves approved decisions and current direction, but does not independently approve DRAFT, PLANNED, or proposed changes.

Keep these dimensions separate:

- repository evidence;
- specification approval;
- implementation coverage;
- completion of a particular delivery task.

Never infer that an APPROVED specification is fully implemented. Never infer that a DRAFT or PLANNED dependency is approved merely because it is referenced by an APPROVED specification or the project context.

## Repository Verification

Before suggesting changes to existing files:

- inspect the current repository state;
- read the current file content;
- inspect the relevant specifications and implementation evidence;
- do not rely on previous conversation memory alone.

## Working Process

Follow this sequence:

1. Understand the request and verify the repository state.
2. Inspect relevant approved decisions and the current implementation.
3. Discuss ambiguity, risks, and meaningful trade-offs.
4. Agree on the smallest bounded decision or change.
5. Document an approved architecture decision when applicable.
6. Implement only the agreed scope.
7. Run verification proportional to risk.
8. Obtain independent review.
9. Remediate findings explicitly and repeat review when necessary.
10. Stage, commit, push, and open a pull request only with explicit authorization.
11. Verify the remote diff and applicable pull-request checks.
12. Merge only with separate explicit authorization.
13. Verify the final repository state and applicable post-merge checks.

Work in small, bounded steps and stop at authorization or verification gates. Do not rush, invent unchecked facts, or modify unrelated files.

## Engineering Principles

- Work domain-first.
- Prefer simple solutions and clear domain boundaries.
- Keep responsibilities explicit.
- Avoid premature complexity and unnecessary abstractions.
- Question weak decisions and explain trade-offs.
- Do not change approved architecture without discussion.

## Innovation and Future Evolution

The project should continue evolving beyond the MVP.

Suggest new technologies, architectural improvements and domain concepts when they provide clear value.

However:

- explain the problem first;
- explain the benefit;
- explain the trade-offs;
- do not implement major architectural changes without discussion.

Future ideas such as blockchain, AI improvements, new business models or additional domain entities should be considered when appropriate.

## Domain Documentation

- Follow `docs/domain/entity-template.md` when creating entity specifications.
- New domain entities require discussion before creation.
- Specification status applies independently to each specification.
- Cross-status references do not promote or approve the referenced specification.
- APPROVED specifications are the source of truth for their approved entity decisions.
- DRAFT specifications represent current active architecture work and must be inspected and preserved unless the task explicitly revises them.
- If a DRAFT or PLANNED dependency changes incompatibly with an APPROVED specification, explicitly review and version the affected APPROVED specification before accepting the change.
- Keep approved entities and their responsibilities consistent across documents.
- Distinguish business rules from invariants and implementation notes.
- Update project context only when a decision or project state has actually changed and the user has approved it.

## Domain Implementation Gate

- Normal production implementation of a domain foundation must be based on the relevant independently APPROVED specification.
- A DRAFT or PLANNED specification is not ordinary production implementation authorization.
- Exploratory work based on DRAFT material requires an explicitly bounded task and must not be represented as approved production architecture.
- When implementation intentionally covers only part of an APPROVED specification, report delivered and deferred coverage explicitly.
- If implementation reveals an incompatibility with an approved decision, return to architecture review instead of silently changing the decision in code.

## Roles and Review Separation

Delivery distinguishes these roles:

- architecture or decision owner;
- change author or implementer;
- independent reviewer;
- integration operator.

One actor may perform different roles at different times, but authorship and independent approval of the same change must remain distinct.

- The author verifies their own work but does not provide its sole independent approval.
- Independent review is evidence-based and inspects the actual repository state and diff.
- A reviewer does not silently edit the reviewed change during an independent-review pass.
- Remediation is a separate, explicit authoring step followed by re-review.
- Integration preserves the reviewed change set and stops if expected SHAs, files, checks, or merge conditions differ.

## Verification and CI Applicability

- Match verification to risk: validate documentation changes; run focused and full tests for production behavior where applicable; use concurrency and adversarial tests for concurrency invariants.
- Report exact commands and results or equivalent evidence.
- Report a configured check as `NOT APPLICABLE` because of path filters only after verifying the exclusion.
- Never add fake or unrelated file changes merely to trigger a workflow.
- Never bypass a failing required check.

## Communication Style

When uncertain:

- state uncertainty clearly;
- ask questions;
- do not invent missing business rules.

## Change Safety

- Inspect repository status before editing.
- Preserve existing user changes.
- Do not edit files outside the requested scope.
- Do not stage, commit, push, or open a pull request without explicit user approval.
- Do not merge or delete a branch without explicit user approval.
- If the verified baseline changes, do not automatically rebase, repair conflicts, or expand scope; stop and report the mismatch.
- Report exactly which files changed, what was verified, and whether changes are unstaged, staged, committed, pushed, opened as a pull request, or merged.
