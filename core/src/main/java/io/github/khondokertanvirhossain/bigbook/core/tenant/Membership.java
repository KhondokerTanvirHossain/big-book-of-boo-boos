package io.github.khondokertanvirhossain.bigbook.core.tenant;

import java.util.UUID;

/** A seat in a project. {@code userId} is the Keycloak user id, the token {@code sub}. */
public record Membership(
        UUID id, UUID projectId, String email, String userId, String profile, boolean admin, String status,
        String accessPolicy, String access, String userConfiguration, String invitedBy,
        String userType, String userScope, UUID invitedByMembership) {

    /** {@code ClientApplication} and {@code Bot} memberships are self-referential: user === profile (T7). */
    public boolean machine() {
        return !"User".equals(userType);
    }

    public boolean active() {
        return Project.ACTIVE.equals(status);
    }
}
