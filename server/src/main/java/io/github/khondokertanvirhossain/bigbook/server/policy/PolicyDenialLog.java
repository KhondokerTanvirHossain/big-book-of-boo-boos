package io.github.khondokertanvirhossain.bigbook.server.policy;

import io.github.khondokertanvirhossain.bigbook.core.policy.CompiledPolicy;
import io.github.khondokertanvirhossain.bigbook.core.tenant.ProjectContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One structured line per denial: project, user, resource, interaction, policies (BB-R-006, issue #7).
 *
 * <p>Also the home of the <b>narrowing backstop</b>. Every search is narrowed pre-query, so a resource that
 * reaches {@code PREACCESS} and fails its criteria on a <i>primary query entry</i> means the narrowing missed
 * — the request is still safe, because the drop catches it, but the narrowing is defective and that is a bug
 * worth a WARN (ADR-001's paging rule).
 */
public class PolicyDenialLog {

    private static final Logger log = LoggerFactory.getLogger(PolicyDenialLog.class);

    /** A denied interaction: 403 on a write, 404 on a single read, silence in a collection. */
    public void denied(ProjectContext caller, String resourceType, String resourceId, String interaction,
            CompiledPolicy policy, String because) {
        log.info("policy denial project={} user={} resource={}/{} interaction={} policies={} reason={}",
                caller == null ? "-" : caller.project().id(),
                caller == null ? "-" : caller.membership().userId(),
                resourceType, resourceId, interaction,
                policy == null ? "-" : policy.policyIds(), because);
    }

    /**
     * A primary-query result that the criteria rejected. The drop already protected the caller; this says the
     * pre-query narrowing did not do its job, which is a defect in the narrowing and not in the policy.
     */
    public void narrowingMissed(String resourceType, String resourceId, String criteria) {
        // counted here, not by the caller: a count that can drift from the log is worse than no count
        narrowingMissedCount++;
        log.warn("narrowing defect: {}/{} reached PREACCESS as a primary-query result but failed its criteria ({})."
                + " The drop protected the caller; the pre-query narrowing should have excluded it.",
                resourceType, resourceId, criteria);
    }

    /** True when the backstop has fired at least once; the backstop test asserts on this. */
    public boolean narrowingEverMissed() {
        return narrowingMissedCount > 0;
    }

    public long narrowingMissedCount() {
        return narrowingMissedCount;
    }

    private volatile long narrowingMissedCount;
}
