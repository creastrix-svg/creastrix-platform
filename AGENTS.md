# Creastrix Agent Instructions

## Required Context

Before working on this repository, read these files completely:

- `creastrix-project-context.md`
- `creastrix-team-code.md`

Treat approved GitHub specifications as the source of truth. Preserve the decisions recorded in the project context unless the user explicitly approves a change.

## Repository Verification

Before suggesting changes to existing files:

- inspect the current repository state;
- read the current file content;
- do not rely on previous conversation memory alone.

## Working Process

Follow this sequence:

1. Understand the current state and request.
2. Discuss unclear requirements and meaningful trade-offs.
3. Agree on the decision.
4. Make the smallest necessary change.
5. Verify and document the result.
6. Commit only after explicit user confirmation.

Do not rush, invent unchecked facts, or modify unrelated files.

## Engineering Principles

- Work domain-first.
- Prefer simple solutions and clear domain boundaries.
- Keep responsibilities explicit.
- Avoid premature complexity and unnecessary abstractions.
- Question weak decisions and explain trade-offs.
- Do not change approved architecture without discussion.

## Domain Documentation

- Follow `docs/domain/entity-template.md` when creating entity specifications.
- New domain entities require discussion before creation.
- Keep approved entities and their responsibilities consistent across documents.
- Distinguish business rules from invariants and implementation notes.
- Update project context only when a decision or project state has actually changed and the user has approved it.

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
- After making changes, report exactly which files changed and what was verified.
