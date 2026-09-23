package io.github.khondokertanvirhossain.bigbook.core.policy;

import java.util.List;
import java.util.Set;

/**
 * The three policy sets Big Book grants without an {@code AccessPolicy} resource behind them.
 *
 * <p>Separated from {@link CompiledPolicy} deliberately: that record's invariants prevent an illegal state
 * and are not counted against #7's tripwire, but <b>these are policy decisions</b> — each one says who gets
 * access — so they are counted wherever they live (CONTRIBUTING §4, ruled 2026-09-22).
 *
 * <ul>
 *   <li>{@link #superAdmin()} — only {@code Project.superAdmin} bypasses; {@code membership.admin} does not (T1).
 *   <li>{@link #fullProjectAccess()} — a membership with no policy at all sees its whole project (T1, BB-R-006.2).
 *   <li>{@link #denyAll(String)} — what an unreadable or wholly refused policy set becomes: fails closed (D14).
 * </ul>
 */
public final class PolicyDefaults {

    private PolicyDefaults() {
    }

    /** A super-admin: {@code allowAll}, and nothing else in this record is consulted (T1). */
    public static CompiledPolicy superAdmin() {
        return new CompiledPolicy(true, List.of(), List.of());
    }

    /** A membership with no policy at all compiles to full project access (T1, BB-R-006.2). */
    public static CompiledPolicy fullProjectAccess() {
        return new CompiledPolicy(false,
                List.of(new CompiledPolicy.Entry("*", new Criteria.Always(), Set.of(Interaction.values()), List.of(), List.of(), null)),
                List.of());
    }

    /** Nothing is permitted. What an unreadable or wholly refused policy set becomes — fails closed (D14). */
    public static CompiledPolicy denyAll(String because) {
        return new CompiledPolicy(false,
                List.of(new CompiledPolicy.Entry("*", new Criteria.Never(because), Set.of(), List.of(), List.of(), null)),
                List.of());
    }
}
