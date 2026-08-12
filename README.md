# Creastrix

> AI Design & Manufacturing Platform

Status: Active early development focused on the backend domain foundation

Version: 0.1.0

## Vision

Creastrix transforms user ideas into manufacturable products using artificial intelligence.

## Current State

Specification approval and implementation coverage are tracked separately.

### APPROVED Domain Specifications

User, User Profile, Organization, Organization Membership, Workspace, and Workspace Membership have APPROVED domain specifications. Approval records the accepted architecture for those entities; it does not mean that every approved rule is already implemented.

### Implemented Backend Foundations

The current backend foundation covers:

- the executable backend bootstrap;
- the User and mandatory User Profile foundation;
- the User repository port with an explicit JDBC persistence adapter;
- the Organization and Organization Membership foundation.

This is a partial foundation, not a complete product implementation or full delivery of every behavior in the approved specifications.

### Approved but Unimplemented and DRAFT Domain Areas

Workspace and Workspace Membership now have APPROVED 1.0 domain specifications, but their backend foundations are not implemented. Implementing their backend foundation is the next implementation step. Other DRAFT domain areas remain unimplemented and require their own independent specification approval before ordinary production implementation.

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
