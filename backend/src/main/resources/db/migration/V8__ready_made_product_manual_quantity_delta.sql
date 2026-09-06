CREATE TABLE ready_made_product_manual_quantity_delta_commands (
    product_id UUID NOT NULL,
    command_id UUID NOT NULL,
    delta BIGINT NOT NULL,
    state TEXT NOT NULL,
    resulting_available_quantity BIGINT,
    rejection_reason TEXT,
    observed_available_quantity BIGINT,
    CONSTRAINT rmp_manual_quantity_delta_commands_pk
        PRIMARY KEY (product_id, command_id),
    CONSTRAINT rmp_manual_quantity_delta_commands_product_fk
        FOREIGN KEY (product_id)
        REFERENCES ready_made_products (id)
        ON DELETE RESTRICT,
    CONSTRAINT rmp_manual_quantity_delta_commands_delta_non_zero
        CHECK (delta <> 0),
    CONSTRAINT rmp_manual_quantity_delta_commands_state_set
        CHECK (state IN ('REGISTERED', 'APPLIED', 'REJECTED')),
    CONSTRAINT rmp_manual_quantity_delta_commands_reason_set
        CHECK (rejection_reason IS NULL OR rejection_reason IN ('UNDERFLOW', 'OVERFLOW')),
    CONSTRAINT rmp_manual_quantity_delta_commands_reason_matches_delta
        CHECK (rejection_reason IS NULL
            OR (delta < 0 AND rejection_reason = 'UNDERFLOW')
            OR (delta > 0 AND rejection_reason = 'OVERFLOW')),
    CONSTRAINT rmp_manual_quantity_delta_commands_outcome_shape
        CHECK (
            (state = 'REGISTERED'
                AND resulting_available_quantity IS NULL
                AND rejection_reason IS NULL
                AND observed_available_quantity IS NULL)
            OR
            (state = 'APPLIED'
                AND resulting_available_quantity IS NOT NULL
                AND resulting_available_quantity >= 0
                AND rejection_reason IS NULL
                AND observed_available_quantity IS NULL)
            OR
            (state = 'REJECTED'
                AND resulting_available_quantity IS NULL
                AND rejection_reason IS NOT NULL
                AND observed_available_quantity IS NOT NULL
                AND observed_available_quantity >= 0)
        )
);

COMMENT ON TABLE ready_made_product_manual_quantity_delta_commands IS
    'Internal durable Product-scoped manual quantity-delta commands. The supported Java/JDBC path revalidates the represented actor before command lookup; privileged or raw SQL is not actor-authorized. Authentication is not implemented, runtime and migration-owner roles are not separated, global deadlock freedom is not claimed, and no general SQLSTATE 40P01 retry policy exists.';

CREATE FUNCTION validate_rmp_manual_quantity_delta_command_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.product_id IS DISTINCT FROM OLD.product_id
            OR NEW.command_id IS DISTINCT FROM OLD.command_id
            OR NEW.delta IS DISTINCT FROM OLD.delta THEN
        RAISE EXCEPTION 'Ready-Made Product manual quantity-delta command binding is immutable'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.state <> 'REGISTERED'
            OR NEW.state NOT IN ('APPLIED', 'REJECTED') THEN
        RAISE EXCEPTION 'Ready-Made Product manual quantity-delta transition is not permitted'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_rmp_manual_quantity_delta_command_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.state <> 'REGISTERED' THEN
        RAISE EXCEPTION 'Ready-Made Product manual quantity-delta command must start REGISTERED'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION prevent_rmp_manual_quantity_delta_command_removal()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Ready-Made Product manual quantity-delta command records are permanent'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER rmp_manual_quantity_delta_validate_update
BEFORE UPDATE ON ready_made_product_manual_quantity_delta_commands
FOR EACH ROW
EXECUTE FUNCTION validate_rmp_manual_quantity_delta_command_update();

CREATE TRIGGER rmp_manual_quantity_delta_validate_insert
BEFORE INSERT ON ready_made_product_manual_quantity_delta_commands
FOR EACH ROW
EXECUTE FUNCTION validate_rmp_manual_quantity_delta_command_insert();

CREATE TRIGGER rmp_manual_quantity_delta_prevent_delete
BEFORE DELETE ON ready_made_product_manual_quantity_delta_commands
FOR EACH ROW
EXECUTE FUNCTION prevent_rmp_manual_quantity_delta_command_removal();

CREATE TRIGGER rmp_manual_quantity_delta_prevent_truncate
BEFORE TRUNCATE ON ready_made_product_manual_quantity_delta_commands
FOR EACH STATEMENT
EXECUTE FUNCTION prevent_rmp_manual_quantity_delta_command_removal();
