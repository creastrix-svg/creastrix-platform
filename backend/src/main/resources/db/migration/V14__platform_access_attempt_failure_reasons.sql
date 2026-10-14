-- Extend only the finite confirmed-attempt-failure vocabulary. Existing event
-- shapes, immutable rows, success/outcome guards and V1-V13 remain unchanged.
ALTER TABLE platform_access_audit_events
    DROP CONSTRAINT platform_access_audit_events_event_shape,
    ADD CONSTRAINT platform_access_audit_events_event_shape CHECK (
        (event_kind = 'INTENT_RECORDED' AND outcome = 'REGISTERED' AND linked_event_id IS NULL
            AND actor_user_id = initiator_user_id AND (
                (action = 'USER_SECURITY_READ' AND projection = 'USER_SECURITY' AND projection IS NOT NULL
                    AND reason IS NOT NULL AND reason IN ('USER_REQUESTED_SUPPORT', 'SECURITY_REVIEW'))
                OR (action = 'SUPPORT_REVOKE' AND permission = 'STAFF_GRANT_SUSPEND_REVOKE'
                    AND projection IS NULL AND reason IS NULL)))
        OR (event_kind = 'READ_ADMITTED' AND outcome = 'ADMITTED' AND projection IS NOT NULL
            AND reason IS NOT NULL AND (
                (action = 'USER_SECURITY_READ' AND projection IN ('USER_SECURITY', 'OPERATION_RECEIPT')
                    AND reason IN ('USER_REQUESTED_SUPPORT', 'SECURITY_REVIEW'))
                OR (action = 'SUPPORT_REVOKE' AND permission = 'STAFF_GRANTS_READ'
                    AND projection = 'OPERATION_RECEIPT' AND reason = 'ACCESS_REVIEW')))
        OR (event_kind = 'GRANT_REVOKED' AND outcome = 'COMMITTED' AND action = 'SUPPORT_REVOKE'
            AND permission = 'STAFF_GRANT_SUSPEND_REVOKE' AND projection IS NULL AND reason IS NULL
            AND actor_user_id = initiator_user_id AND linked_event_id IS NULL)
        OR (event_kind = 'ACCESS_DENIED' AND outcome = 'DENIED' AND projection IS NULL
            AND linked_event_id IS NULL AND reason IS NOT NULL AND reason IN (
                'MISSING_FACTS', 'INCONSISTENT_FACTS', 'UNKNOWN_VERSION', 'INACTIVE_ACTOR',
                'INVALID_GRANT', 'SLOT_MISMATCH', 'INVALID_SCOPE', 'INVALID_VALIDITY',
                'NOT_YET_VALID', 'EXPIRED', 'PERMISSION_DENIED', 'ACTION_MISMATCH',
                'RESOURCE_MISMATCH', 'REASON_DENIED', 'ASSURANCE_REQUIRED', 'STAMP_MISMATCH',
                'TIME_INVALID', 'ASSURANCE_EXPIRED', 'STEP_UP_REQUIRED', 'STEP_UP_EXPIRED',
                'SELF_TARGET', 'OWNER_TARGET', 'INACTIVE_TARGET', 'OCCUPIED_SLOT',
                'STATE_DENIED', 'STALE_REVISION', 'NO_CHANGE', 'REVISION_OVERFLOW',
                'INVITATION_MISMATCH', 'INTENT_ABSENT', 'INTENT_MISMATCH',
                'INTENT_UNRESOLVED', 'HISTORICAL_INITIATOR_UNAVAILABLE', 'ID_UNAVAILABLE',
                'INVALID_INPUT', 'TERMINAL_RECEIPT_REQUIRED'))
        OR (event_kind = 'OPERATION_FAILED' AND outcome = 'CONFIRMED_FAILED' AND projection IS NULL
            AND linked_event_id IS NULL AND reason IS NOT NULL AND reason IN (
                'AUDIT_UNAVAILABLE', 'DATABASE_UNAVAILABLE', 'DEADLINE_EXCEEDED',
                'INTERRUPTED', 'UNEXPECTED_FAILURE', 'LOCK_NOT_AVAILABLE', 'STATEMENT_CANCELLED')));
