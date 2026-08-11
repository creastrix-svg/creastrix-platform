-- V1 is an intentional bootstrap migration.
-- It exists only to prove that the Flyway migration pipeline runs against a real
-- PostgreSQL database. It deliberately creates NO Creastrix domain/business schema.
-- The real relational schema will be introduced in a later implementation task
-- derived from domain invariants.
SELECT 1;
