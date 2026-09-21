package io.github.khondokertanvirhossain.bigbook.core.tenant;

import java.util.UUID;

/**
 * What {@code POST /admin/projects/:id/invite} asks for (BB-R-005.4). Big Book's payload in v0.1;
 * Medplum byte-compatibility is v0.2 (BB-R-014.4), which is why this is a record and not Medplum's JSON.
 *
 * @param resourceType profile type to create: {@code Practitioner}, {@code Patient} or {@code RelatedPerson}
 * @param externalId an identity from elsewhere; its presence forces scope {@code project} (T6)
 * @param patient the {@code Patient} a {@code RelatedPerson} relates to
 * @param scope {@code server} or {@code project}; defaulted per profile type when absent (BB-R-005.2)
 * @param password set now; when absent the invitee sets it through the email link
 * @param sendEmail send Keycloak's invite email — needs SMTP, which is issue #11
 * @param mfaRequired pre-provision the TOTP required action (BB-R-004.3)
 * @param admin deprecated top-level field, still accepted (T5); {@code membership} wins
 */
public record InviteRequest(
        String resourceType,
        String firstName,
        String lastName,
        String email,
        String externalId,
        String patient,
        String scope,
        String password,
        boolean sendEmail,
        boolean mfaRequired,
        Boolean admin,
        String accessPolicy,
        String access,
        String userConfiguration,
        UUID invitedBy) {

    /** Practitioner and RelatedPerson default to {@code server}, Patient to {@code project} (BB-R-005.2). */
    public String effectiveScope() {
        if (externalId != null && !externalId.isBlank()) {
            // T6: an externalId is meaningless outside the project that issued it
            return "project";
        }
        if (scope != null && !scope.isBlank()) {
            return scope;
        }
        return "Patient".equals(resourceType) ? "project" : "server";
    }

    public boolean effectiveAdmin() {
        return admin != null && admin;
    }
}
