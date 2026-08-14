# Creastrix

> AI Design & Manufacturing Platform

Status: Active early development focused on the backend domain foundation

Version: 0.1.0

## Vision

Creastrix transforms user ideas into manufacturable products using artificial intelligence.

## Current State

Specification approval and implementation coverage are tracked separately.

### APPROVED Domain Specifications

User, User Profile, Organization, Organization Membership, Workspace, Workspace Membership, and Ready-Made Product have APPROVED domain specifications. Approval records the accepted architecture for those entities; it does not mean that every approved rule is already implemented.

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
  coverage of APPROVED 1.0: a stable UUID identity, exactly one immutable
  Workspace, exactly one immutable Created By User, stored `ACTIVE` / `ARCHIVED`
  state, and a non-negative integer available quantity including zero;
- atomic initial `ACTIVE` Ready-Made Product creation with creation-time
  validation of an `ACTIVE` creator User and effective Workspace-layer
  `READY_MADE_PRODUCTS` write authorization;
- the Ready-Made Product repository port, explicit JDBC adapter, Flyway V6,
  independent PostgreSQL creation gate and structural enforcement, with
  concurrency and rollback boundaries covered by integration tests.

This is a partial foundation, not a complete product implementation or full delivery of every behavior in the approved specifications.

Ready-Made Product implementation remains partial coverage of APPROVED 1.0.
Deferred behavior includes `ACTIVE` ↔ `ARCHIVED` lifecycle transition
operations, generic editing,
manual quantity delta with stable command identity and idempotency persistence,
confirmation-time allocation and decrement, eligible pre-dispatch release,
serialization with Shipment `SHIPPED`, Listing, Order, Order Item, Shipment and
other commerce integrations, list, search and paging, an HTTP API, and
authentication with proven caller identity.

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
