package com.creastrix.platform.user.application;

import java.util.UUID;

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
 * status. Authentication/authorization infrastructure is not part of this
 * implementation slice, so this service enforces lifecycle semantics only and
 * does not prove actor authorization. Authorizing a future caller remains a
 * separate implementation concern.
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
        return users.findById(id).orElseThrow(() -> new UserNotFoundException(id));
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
        return updated;
    }
}
