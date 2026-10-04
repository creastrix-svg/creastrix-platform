package com.creastrix.platform.user.application;

import java.util.UUID;

import com.creastrix.platform.observability.TransactionDiagnostics;
import com.creastrix.platform.observability.TransactionDiagnostics.Operation;
import com.creastrix.platform.user.application.port.UserRepository;
import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserNotFoundException;
import com.creastrix.platform.user.domain.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the User identity and status lifecycle.
 *
 * <p>Authorization boundary: the approved User specification allows only an
 * applicable authorized account, security, or platform workflow to change User
 * status. These domain operations are not exposed through an authenticated
 * HTTP API; the separate authentication pilot does not authorize their callers.
 * This service enforces lifecycle semantics only and does not prove actor
 * authorization. Authorizing a future caller remains a separate concern.
 */
@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    /**
     * Creates a User together with its mandatory User Profile in one
     * transaction. The initial ACTIVE status is established by the database.
     */
    @Transactional
    public User createUser() {
        UUID id = UUID.randomUUID();
        users.create(id);
        return TransactionDiagnostics.returned(Operation.USER_CREATE,
                users.findById(id).orElseThrow(() -> new UserNotFoundException(id)));
    }

    @Transactional(readOnly = true)
    public User findUser(UUID id) {
        return users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }

    /**
     * Transitions User status. The current row is locked for the duration of the
     * transaction to avoid a read/modify/write race.
     */
    @Transactional
    public User changeStatus(UUID id, UserStatus targetStatus) {
        User current = users.findByIdForUpdate(id).orElseThrow(() -> new UserNotFoundException(id));
        User updated = current.transitionTo(targetStatus);
        users.updateStatus(updated.id(), updated.status());
        return TransactionDiagnostics.returned(Operation.USER_STATUS_CHANGE, updated);
    }
}
