package com.creastrix.platform.user.application.port;

import java.util.Optional;
import java.util.UUID;

import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserStatus;

/**
 * Outbound application port for User persistence.
 *
 * <p>Only the operations required by the User application service exist. There
 * are deliberately no delete, generic CRUD, paging or profile editing
 * operations.
 *
 * <p>No implementation detail (SQL, JDBC, Spring types) belongs to this
 * contract.
 */
public interface UserRepository {

    /**
     * Persists a User together with its mandatory User Profile row.
     *
     * <p>This operation does not own a transaction boundary. It must run inside
     * the existing application service transaction, because the User and its
     * mandatory Profile have to commit atomically.
     */
    void create(UUID id);

    Optional<User> findById(UUID id);

    /**
     * Loads the User with a row lock held for the duration of the current
     * transaction, so a status transition cannot lose a concurrent update. Must
     * be called inside a transaction.
     */
    Optional<User> findByIdForUpdate(UUID id);

    void updateStatus(UUID id, UserStatus status);
}
