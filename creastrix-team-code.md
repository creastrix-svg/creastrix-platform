# Creastrix Team Code

## Purpose

This document preserves the engineering culture and reasoning behind
Creastrix.

## How We Build

We do not rush.

We work in small, reversible steps and stop at decision, review, authorization, and verification gates.

Our end-to-end discipline is:

1. Verify the current state and relevant decisions.
2. Understand, discuss, and decide the smallest bounded step.
3. Document architecture decisions when applicable.
4. Implement only the agreed scope.
5. Test and verify in proportion to risk.
6. Obtain independent review; remediate and repeat when needed.
7. Integrate through an authorized commit and pull request, then verify applicable checks.
8. Merge only with explicit authorization.
9. Verify the repository again after merge before moving forward.

## AI Partner Rules

The AI should act as a senior technical partner.

The AI should:

- understand and verify before changing;
- question weak decisions;
- explain trade-offs;
- protect previous agreements.

The AI should not:

- blindly generate code;
- change approved architecture without discussion;
- pretend to know what was not checked;
- hide uncertainty or silently expand scope.

## Role Separation

Architecture ownership, change authorship, independent review, and integration are distinct responsibilities, even when one person performs different roles at different times.

Authors verify their own work, but independent approval must come from a separate review of the actual change. Findings are remediated explicitly and reviewed again before integration continues.

## Engineering Philosophy

Prefer:

- simple solutions;
- clear domain boundaries;
- explicit responsibilities.

Avoid:

- premature complexity;
- unnecessary abstractions.

Evidence from the current repository, diffs, tests, and checks is more reliable than a confident summary.

## Team Spirit

Questions and corrections improve the project.

Stopping to verify is a strength.

Finding a problem early is success. Hiding uncertainty or silently expanding scope is not.

The goal is not to be right. The goal is to build the best Creastrix.

## Final Principle

Creastrix is a bridge:

Idea → Design → Manufacturing → Physical Product

while preserving:

Creator → Ownership → Reward
