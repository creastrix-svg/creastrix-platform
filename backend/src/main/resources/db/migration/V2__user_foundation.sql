-- V2 introduces the first real Creastrix business schema: the User identity and
-- account status lifecycle, plus the mandatory one-to-one User Profile.
--
-- Source of truth: docs/domain/user.md (APPROVED 1.8) and
-- docs/domain/user-profile.md (APPROVED 1.0).
--
-- No authentication data is stored here. Authentication, login, and credential
-- concerns are deliberately out of scope for this migration.

CREATE TABLE users (
    id     UUID NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT users_pk PRIMARY KEY (id),
    CONSTRAINT users_status_allowed CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED'))
);

-- The User Profile is identified by its User. Exactly one Profile exists per
-- User, and a Profile cannot exist without its User. No independent Profile
-- identity is defined by the approved specification, so none is invented here.
-- Concrete personal fields are intentionally absent until specified.
CREATE TABLE user_profiles (
    user_id UUID NOT NULL,
    CONSTRAINT user_profiles_pk PRIMARY KEY (user_id),
    CONSTRAINT user_profiles_user_fk FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE RESTRICT
);

-- Invariant: a newly created User starts ACTIVE.
-- Enforced in the database so no caller can bypass it through direct SQL.
CREATE FUNCTION users_enforce_initial_status() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'A newly created User must start ACTIVE, got %', NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER users_enforce_initial_status
    BEFORE INSERT ON users
    FOR EACH ROW
    EXECUTE FUNCTION users_enforce_initial_status();

-- Invariant: supported User status transitions are exactly
--   ACTIVE      -> SUSPENDED
--   ACTIVE      -> DEACTIVATED
--   SUSPENDED   -> ACTIVE
--   SUSPENDED   -> DEACTIVATED
-- Ordinary reactivation from DEACTIVATED is not supported. A same-state command
-- is not a lifecycle transition and is rejected rather than silently accepted.
CREATE FUNCTION users_enforce_status_transition() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF NOT (
            (OLD.status = 'ACTIVE' AND NEW.status IN ('SUSPENDED', 'DEACTIVATED'))
            OR (OLD.status = 'SUSPENDED' AND NEW.status IN ('ACTIVE', 'DEACTIVATED'))
        ) THEN
            RAISE EXCEPTION 'Unsupported User status transition % -> %', OLD.status, NEW.status
                USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        RAISE EXCEPTION 'Unsupported User status transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id THEN
        RAISE EXCEPTION 'Changing User status must not change User identity'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- The trigger is bound to the status column, so a future update of an unrelated
-- User column is not treated as a status lifecycle command. An explicit update
-- of status, including a same-state one, still targets the column and remains
-- subject to lifecycle validation.
CREATE TRIGGER users_enforce_status_transition
    BEFORE UPDATE OF status ON users
    FOR EACH ROW
    EXECUTE FUNCTION users_enforce_status_transition();

-- Invariant: a committed User always has exactly one User Profile.
-- The check is deferred to commit so the legitimate atomic creation transaction
-- (insert User, then insert User Profile) is not rejected between statements.
CREATE FUNCTION users_require_profile() RETURNS TRIGGER AS $$
DECLARE
    profile_count INTEGER;
BEGIN
    SELECT count(*) INTO profile_count FROM user_profiles WHERE user_id = NEW.id;
    IF profile_count <> 1 THEN
        RAISE EXCEPTION 'User % must have exactly one User Profile, found %', NEW.id, profile_count
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER users_require_profile
    AFTER INSERT ON users
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION users_require_profile();

-- Invariant: an existing User cannot be left without its User Profile by
-- deleting or reassigning the Profile row.
CREATE FUNCTION user_profiles_preserve_mandatory() RETURNS TRIGGER AS $$
DECLARE
    profile_count INTEGER;
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE id = OLD.user_id) THEN
        SELECT count(*) INTO profile_count FROM user_profiles WHERE user_id = OLD.user_id;
        IF profile_count <> 1 THEN
            RAISE EXCEPTION 'User % must have exactly one User Profile, found %', OLD.user_id, profile_count
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER user_profiles_preserve_mandatory
    AFTER UPDATE OR DELETE ON user_profiles
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION user_profiles_preserve_mandatory();

-- PostgreSQL does not fire row-level DELETE triggers for TRUNCATE, so the
-- mandatory Profile invariant needs a statement-level safeguard as well.
-- This check is deliberately conditional: it only rejects a TRUNCATE that
-- leaves a surviving User without its mandatory Profile. Physically removing
-- Users and Profiles together leaves no User at all and therefore does not
-- violate the invariant. Hard-deletion policy stays out of scope here.
CREATE FUNCTION user_profiles_preserve_mandatory_on_truncate() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM users u
        WHERE NOT EXISTS (
            SELECT 1
            FROM user_profiles p
            WHERE p.user_id = u.id
        )
    ) THEN
        RAISE EXCEPTION
            'TRUNCATE of user_profiles would leave an existing User without its mandatory User Profile'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER user_profiles_preserve_mandatory_on_truncate
    AFTER TRUNCATE ON user_profiles
    FOR EACH STATEMENT
    EXECUTE FUNCTION user_profiles_preserve_mandatory_on_truncate();
