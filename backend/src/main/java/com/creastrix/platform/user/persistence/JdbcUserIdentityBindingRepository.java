package com.creastrix.platform.user.persistence;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import com.creastrix.platform.user.application.port.UserIdentityBindingRepository;
import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserIdentityBindingRepository implements UserIdentityBindingRepository {

    private final JdbcTemplate jdbc;

    public JdbcUserIdentityBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<User> findCurrentUser(Identity identity) {
        return jdbc.query("""
                SELECT u.id, u.status FROM user_identity_bindings b
                JOIN users u ON u.id = b.user_id
                WHERE b.issuer = ? AND b.subject = ?
                """, (rs, row) -> new User(rs.getObject("id", UUID.class),
                UserStatus.valueOf(rs.getString("status"))), identity.issuer(), identity.subject())
                .stream().findFirst();
    }

    @Override
    public void insert(Identity identity, UUID userId) {
        String schema = jdbc.execute((ConnectionCallback<String>) connection -> connection.getSchema());
        try {
            jdbc.update("INSERT INTO user_identity_bindings (issuer, subject, user_id) VALUES (?, ?, ?)",
                    identity.issuer(), identity.subject(), userId);
        } catch (DataAccessException failure) {
            if (isExactPairConflict(failure, schema)) {
                throw new PairAlreadyBoundException(failure);
            }
            throw failure;
        }
    }

    private static boolean isExactPairConflict(Throwable failure, String schema) {
        // PostgreSQL stays runtime-scoped. Read structured driver metadata reflectively,
        // never infer a constraint from a localized/server-controlled message substring.
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())
                    && "org.postgresql.util.PSQLException".equals(cause.getClass().getName())) {
                try {
                    Object detail = cause.getClass().getMethod("getServerErrorMessage").invoke(cause);
                    if (detail == null || schema == null) {
                        return false;
                    }
                    Class<?> type = detail.getClass();
                    return schema.equals(type.getMethod("getSchema").invoke(detail))
                            && "user_identity_bindings".equals(type.getMethod("getTable").invoke(detail))
                            && "user_identity_bindings_pk".equals(
                                    type.getMethod("getConstraint").invoke(detail));
                } catch (ReflectiveOperationException inaccessibleMetadata) {
                    return false;
                }
            }
        }
        return false;
    }
}
