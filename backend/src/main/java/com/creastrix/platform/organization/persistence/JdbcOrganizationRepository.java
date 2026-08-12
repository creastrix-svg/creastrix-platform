package com.creastrix.platform.organization.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.creastrix.platform.organization.application.port.OrganizationRepository;
import com.creastrix.platform.organization.domain.Organization;
import com.creastrix.platform.organization.domain.OrganizationMembership;
import com.creastrix.platform.organization.domain.OrganizationMembershipStatus;
import com.creastrix.platform.organization.domain.OrganizationRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Explicit SQL persistence for Organization and Organization Membership.
 *
 * <p>Only the operations required by this slice exist. There are deliberately no
 * delete or mutation operations and no generic mapping infrastructure.
 */
@Repository
public class JdbcOrganizationRepository implements OrganizationRepository {

    private static final RowMapper<Organization> ORGANIZATION_ROW_MAPPER = (rs, rowNum) ->
            new Organization(rs.getObject("id", UUID.class));

    private static final RowMapper<OrganizationMembership> MEMBERSHIP_ROW_MAPPER = (rs, rowNum) ->
            new OrganizationMembership(
                    rs.getObject("organization_id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    OrganizationRole.valueOf(rs.getString("role")),
                    OrganizationMembershipStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbcTemplate;

    public JdbcOrganizationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts an Organization and its creator's initial ACTIVE OWNER
     * Organization Membership.
     *
     * <p>No transaction is started here: this runs inside the calling
     * application service transaction, so both rows commit atomically and the
     * deferred structural owner invariant is satisfied at commit.
     */
    @Override
    public void create(UUID organizationId, UUID creatorUserId) {
        jdbcTemplate.update("INSERT INTO organizations (id) VALUES (?)", organizationId);
        jdbcTemplate.update(
                "INSERT INTO organization_memberships (organization_id, user_id, role, status) "
                        + "VALUES (?, ?, 'OWNER', 'ACTIVE')",
                organizationId, creatorUserId);
    }

    @Override
    public Optional<Organization> findById(UUID organizationId) {
        return jdbcTemplate
                .query("SELECT id FROM organizations WHERE id = ?", ORGANIZATION_ROW_MAPPER, organizationId)
                .stream()
                .findFirst();
    }

    @Override
    public List<OrganizationMembership> findMembershipsByOrganizationId(UUID organizationId) {
        return jdbcTemplate.query(
                "SELECT organization_id, user_id, role, status FROM organization_memberships "
                        + "WHERE organization_id = ?",
                MEMBERSHIP_ROW_MAPPER, organizationId);
    }
}
