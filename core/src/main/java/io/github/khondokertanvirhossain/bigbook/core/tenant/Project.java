package io.github.khondokertanvirhossain.bigbook.core.tenant;

import java.util.UUID;

/**
 * One tenant: a Big Book row, a Keycloak organisation whose alias and name are {@code id}, and a HAPI
 * partition whose name is {@code id} (ADR-007, BB-R-005.14). The display name lives only here.
 *
 * @param settings the Medplum-shaped Project fields other than id and name, as JSON (BB-R-005.6)
 */
public record Project(UUID id, int partitionId, String name, boolean superAdmin, String status, String settings) {

    public static final String PROVISIONING = "provisioning";
    public static final String ACTIVE = "active";

    public boolean active() {
        return ACTIVE.equals(status);
    }
}
