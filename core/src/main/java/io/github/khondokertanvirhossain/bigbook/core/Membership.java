package io.github.khondokertanvirhossain.bigbook.core;

import java.util.UUID;

/** A seat in a project. {@code userId} is the Keycloak user id, the token {@code sub}. */
public record Membership(
        UUID id, UUID projectId, String email, String userId, String profile, boolean admin, String status,
        String accessPolicy, String access, String userConfiguration, String invitedBy) {

    public boolean active() {
        return Project.ACTIVE.equals(status);
    }
}
