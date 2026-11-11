-- V2's conditional AFTER TRUNCATE check remains the mandatory-Profile
-- invariant. A fixed-snapshot transaction can miss a concurrent User/Profile
-- pair committed while TRUNCATE waits for its table lock, so admit this
-- particular write path only at READ COMMITTED, as in V9.
CREATE TRIGGER user_profiles_require_read_committed
    BEFORE TRUNCATE ON public.user_profiles
    FOR EACH STATEMENT
    EXECUTE FUNCTION public.foundation_require_read_committed();
