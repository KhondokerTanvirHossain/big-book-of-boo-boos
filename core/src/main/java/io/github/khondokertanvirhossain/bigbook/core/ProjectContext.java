package io.github.khondokertanvirhossain.bigbook.core;

/**
 * Who is calling and which project the call acts on, resolved once per request from the bearer token.
 *
 * @param project the project acted on: the caller's own, or for a super-admin the one it selected
 * @param membership the caller's seat, always in the project its token is bound to
 * @param superAdmin the caller's seat is in the super-admin project; the only bypass there is (T1)
 */
public record ProjectContext(Project project, Membership membership, boolean superAdmin) {

    /** Request attribute name; a literal because annotations need a constant. */
    public static final String ATTRIBUTE = "io.github.khondokertanvirhossain.bigbook.core.ProjectContext";

    public boolean mayAdminister(java.util.UUID projectId) {
        return superAdmin || (membership.admin() && membership.projectId().equals(projectId));
    }
}
