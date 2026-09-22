package io.github.khondokertanvirhossain.bigbook.server.policy;

import io.github.khondokertanvirhossain.bigbook.core.policy.CompiledPolicy;
import io.github.khondokertanvirhossain.bigbook.core.tenant.Membership;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Compiled policies, cached per membership and <b>invalidated by content, not by time</b>.
 *
 * <p>Compilation parses every criterion through HAPI's {@code MatchUrlService} on every request, which is the
 * expensive part of the enforcement path. ADR-001 says "cached per membership on policy versions", and the
 * "on policy versions" half is the part that matters for correctness: a cache keyed on the membership alone
 * would keep serving the old policy after an author edits it. That is not a staleness annoyance — it is a
 * caller retaining access that has been revoked.
 *
 * <p>So the key is the membership's identity <i>plus a fingerprint of everything that changes the compiled
 * result</i>: its own policy attachments, its admin flag and profile, and the {@code version_id} of every
 * {@code AccessPolicy} it references. Edit a policy and its version changes, the fingerprint changes, and the
 * next request compiles afresh. Nothing has to remember to call an invalidate method — there isn't one, because
 * an invalidation that can be forgotten is a hole.
 *
 * <p>Bounded by eviction on insert rather than by a scheduled sweep: the map is a request-path structure and a
 * project with thousands of memberships must not be able to grow it without limit. Eviction costs a
 * recompilation, never an access decision.
 */
public class CompiledPolicyCache {

    /** Generous enough that a busy project never thrashes, small enough to bound memory. */
    private static final int MAX_ENTRIES = 2_048;

    private final Map<Key, CompiledPolicy> compiled = new ConcurrentHashMap<>();

    /**
     * @param versions the stored {@code version_id} of each referenced policy, in reference order — the part of
     *     the key that makes an edited policy recompile
     * @param compile called only on a miss; must be a pure function of the same inputs the key covers
     */
    public CompiledPolicy get(Membership membership, List<String> versions, Supplier<CompiledPolicy> compile) {
        Key key = keyFor(membership, versions);
        CompiledPolicy hit = compiled.get(key);
        if (hit != null) {
            return hit;
        }
        CompiledPolicy fresh = compile.get();
        if (compiled.size() >= MAX_ENTRIES) {
            // crude on purpose: an LRU here would need per-hit bookkeeping on the request path to protect a
            // structure whose miss costs one recompilation. Clearing is O(1) amortised and cannot serve stale.
            compiled.clear();
        }
        compiled.put(key, fresh);
        return fresh;
    }

    /** Test seam: proves a hit was a hit rather than a recompilation that happened to agree. */
    public int size() {
        return compiled.size();
    }

    private static Key keyFor(Membership membership, List<String> versions) {
        return new Key(membership.id(), membership.accessPolicy(), membership.access(), membership.admin(),
                membership.profile(), List.copyOf(versions));
    }

    /**
     * Every field here changes what {@code compile} returns, and nothing here is absent from it. A field that
     * affects compilation but is missing from this record would be a stale-policy bug, so the two must be read
     * together: {@code PolicyBinder.compile} is the other half.
     */
    private record Key(
            java.util.UUID membershipId,
            String accessPolicy,
            String access,
            boolean admin,
            String profile,
            List<String> policyVersions) {
    }
}
