package com.creastrix.platform.user.persistence;

import java.util.Optional;
import java.util.UUID;

import com.creastrix.platform.user.domain.User;
import com.creastrix.platform.user.domain.UserStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Explicit SQL persistence for User and the mandatory User Profile row.
 *
 * <p>Only the operations required by this slice exist. There are deliberately no
 * delete operations and no generic mapping infrastructure.
 */
@Repository
public class JdbcUserStore {

    private static final RowMapper<User> USER_ROW_MAPPER = (rs, rowNum) ->
            new User(rs.getObject("id", UUID.class), UserStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Inserts a User. The database default establishes the initial ACTIVE status. */
    public void insertUser(UUID id) {
        jdbcTemplate.update("INSERT INTO users (id) VALUES (?)", id);
    }

    public void insertUserProfile(UUID userId) {
        jdbcTemplate.update("INSERT INTO user_profiles (user_id) VALUES (?)", userId);
    }

    public Optional<User> findUser(UUID id) {
        return jdbcTemplate.query("SELECT id, status FROM users WHERE id = ?", USER_ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    /**
     * Loads the User row with a PostgreSQL row lock, so a status transition
     * cannot lose a concurrent update. Must be called inside a transaction.
     */
    public Optional<User> findUserForUpdate(UUID id) {
        return jdbcTemplate
                .query("SELECT id, status FROM users WHERE id = ? FOR UPDATE", USER_ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    public void updateUserStatus(UUID id, UserStatus status) {
        jdbcTemplate.update("UPDATE users SET status = ? WHERE id = ?", status.name(), id);
    }
}
