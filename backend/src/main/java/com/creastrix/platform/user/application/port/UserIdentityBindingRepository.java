package com.creastrix.platform.user.application.port;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.creastrix.platform.user.domain.User;

/** Exact external identity persistence; this port neither verifies OIDC nor grants admission. */
public interface UserIdentityBindingRepository {

    Optional<User> findCurrentUser(Identity identity);

    /** Participates in the caller's transaction; never commits User or binding separately. */
    void insert(Identity identity, UUID userId);

    record Identity(String issuer, String subject) {
        public Identity {
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(subject, "subject");
            if (issuer.isBlank() || subject.isBlank()) {
                throw new IllegalArgumentException("Identity components must be nonblank");
            }
        }

        // Identity is sensitive and must not accidentally enter framework/application logs.
        @Override
        public String toString() {
            return "Identity[redacted]";
        }
    }

    /** Only the immediate unique constraint on the exact identity pair may produce this signal. */
    final class PairAlreadyBoundException extends RuntimeException {
        public PairAlreadyBoundException(Throwable cause) {
            super("Identity pair already bound", cause);
        }
    }
}
