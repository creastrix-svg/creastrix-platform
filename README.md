# Creastrix

> AI Design & Manufacturing Platform

Status: Active early development focused on the backend domain foundation

Version: 0.1.0

## Vision

Creastrix transforms user ideas into manufacturable products using artificial intelligence.

## Current State

Specification approval and implementation coverage are tracked separately.

### APPROVED Domain Specifications

User, User Profile, Organization, Organization Membership, Workspace, Workspace Membership, and Ready-Made Product have APPROVED domain specifications. Ready-Made Product is currently APPROVED 1.1. Approval records the accepted architecture for those entities; it does not mean that every approved rule is already implemented.

### Implemented Backend Foundations

The current backend foundation covers:

- the executable backend bootstrap;
- the User and mandatory User Profile foundation;
- the User repository port with an explicit JDBC persistence adapter;
- the Organization and Organization Membership foundation;
- the Workspace and Workspace Membership structural foundation, including
  User-owned and Organization-owned creation with an atomic initial `ACTIVE`
  `ADMIN` Membership;
- the Workspace repository port with an explicit JDBC persistence adapter and
  V4 PostgreSQL structural-invariant enforcement;
- the Ready-Made Product structural foundation as partial implementation
  coverage of APPROVED 1.1: a stable UUID identity, exactly one immutable
  Workspace, exactly one immutable Created By User, stored `ACTIVE` / `ARCHIVED`
  state, and a non-negative integer available quantity including zero;
- atomic initial `ACTIVE` Ready-Made Product creation with creation-time
  validation of an `ACTIVE` creator User and effective Workspace-layer
  `READY_MADE_PRODUCTS` write authorization;
- the Ready-Made Product repository port, explicit JDBC adapter, Flyway V6,
  independent PostgreSQL creation gate and structural enforcement, with
  concurrency and rollback boundaries covered by integration tests;
- bounded Ready-Made Product archive and activate operations for `ACTIVE` →
  `ARCHIVED` and `ARCHIVED` → `ACTIVE` through separate public application
  methods, without a generic caller-supplied target status;
- lifecycle authorization for an `ACTIVE` actor User with effective Workspace
  `READY_MADE_PRODUCTS` write access, a final persisted-status decision after
  locking the Product row, expected-state updates, and Flyway V7 structural
  transition enforcement, including same-state rejection and rollback and
  concurrency integration coverage.

This is a partial foundation, not a complete Ready-Made Product or MVP implementation or full delivery of every behavior in the approved specifications.

IMPLEMENTATION 005A and IMPLEMENTATION 005B remain partial implementation
coverage of Ready-Made Product APPROVED 1.1.
It does not provide authentication or proven external caller identity. Raw SQL
is structurally constrained but not actor-authorized, migration/table-owner
privileges are not separated from the runtime database role, and global
deadlock freedom or a general SQLSTATE `40P01` retry policy is not established.
Deferred behavior includes generic editing, manual quantity delta with durable
composite command identity `(Product ID, Command ID)` and internal idempotency
persistence, confirmation-time allocation, eligible pre-dispatch exact release,
dispatch accounting and serialization with Shipment `SHIPPED`, Listing, Order,
Order Item, Shipment, Payment and other commerce integrations, list, search and
paging, and an HTTP API. IMPLEMENTATION 005C has not started, and this
documentation step does not select a next implementation slice.

### Remaining DRAFT Domain Areas

The downstream Listing, Order Item, Shipment, and other remaining DRAFT domain
areas are unimplemented and require their own independent specification
approval before ordinary production implementation. Neither the Workspace and
Workspace Membership structural foundation nor Ready-Made Product approval
approves any downstream DRAFT specification.

## Architecture and Technology

- Java 25 and Spring Boot;
- modular monolith;
- Maven Wrapper;
- PostgreSQL with Flyway migrations;
- explicit JDBC repositories;
- PostgreSQL integration tests based on Testcontainers.

## Repository Guide

- [Project context](creastrix-project-context.md)
- [Team code](creastrix-team-code.md)
- [Domain specifications](docs/domain/README.md)
- [Backend](backend/README.md)
