package com.creastrix.platform.readymadeproduct.persistence;

import java.util.Optional;
import java.util.UUID;

import com.creastrix.platform.readymadeproduct.application.port.ReadyMadeProductRepository;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProduct;
import com.creastrix.platform.readymadeproduct.domain.ReadyMadeProductStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Explicit SQL persistence for the Ready-Made Product foundation.
 *
 * <p>Only the operations required by this slice exist. There are deliberately
 * no lifecycle or quantity mutation operations, no deletion, and no generic
 * mapping infrastructure. Every value, including the initial ACTIVE lifecycle
 * state, is written explicitly: the database has no defaults and never
 * generates the identity.
 */
@Repository
public class JdbcReadyMadeProductRepository implements ReadyMadeProductRepository {

    private static final RowMapper<ReadyMadeProduct> READY_MADE_PRODUCT_ROW_MAPPER =
            (rs, rowNum) -> new ReadyMadeProduct(
                    rs.getObject("id", UUID.class),
                    rs.getObject("workspace_id", UUID.class),
                    rs.getObject("created_by_user_id", UUID.class),
                    ReadyMadeProductStatus.valueOf(rs.getString("status")),
                    rs.getLong("available_quantity"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcReadyMadeProductRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts exactly one new Ready-Made Product row.
     *
     * <p>No transaction is started here: this runs inside the calling
     * application service transaction, so the deferred database creation gate
     * validates the row against committed state at that transaction's commit.
     */
    @Override
    public void create(
            UUID readyMadeProductId,
            UUID workspaceId,
            UUID createdByUserId,
            long availableQuantity) {
        jdbcTemplate.update(
                "INSERT INTO ready_made_products "
                        + "(id, workspace_id, created_by_user_id, status, available_quantity) "
                        + "VALUES (?, ?, ?, 'ACTIVE', ?)",
                readyMadeProductId, workspaceId, createdByUserId, availableQuantity);
    }

    @Override
    public Optional<ReadyMadeProduct> findById(UUID readyMadeProductId) {
        return jdbcTemplate
                .query("SELECT id, workspace_id, created_by_user_id, status, available_quantity "
                                + "FROM ready_made_products WHERE id = ?",
                        READY_MADE_PRODUCT_ROW_MAPPER, readyMadeProductId)
                .stream()
                .findFirst();
    }
}
