package io.github.khondokertanvirhossain.bigbook.core.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parameter substitution over a policy, per membership (BB-R-006.3).
 *
 * <p>Built-ins are {@code %profile} and {@code %patient} — the latter defaulting to the profile — plus
 * whatever {@code ProjectMembership.access[].parameter} names. <b>There is no {@code %requestor}</b> (T3):
 * an unknown parameter is a rejection, not an empty string, because substituting nothing into a criterion is
 * exactly the fail-open case V5 found ({@code subject=} matches everything).
 *
 * <p>Substitution is textual over the policy JSON and <b>{@code %name.id} is replaced before {@code %name}</b>,
 * or {@code %patient.id} would become {@code Patient/123.id}.
 */
public class PolicyParameters {

    /** A reference like {@code Patient/123}, and the same with {@code .id} asking for the bare id. */
    private static final Pattern PLACEHOLDER = Pattern.compile("%([A-Za-z][A-Za-z0-9_]*)(\\.id)?");

    private final Map<String, String> references;

    private PolicyParameters(Map<String, String> references) {
        this.references = references;
    }

    /**
     * @param profile the membership's profile reference, e.g. {@code Practitioner/abc}; may be null before
     *     the invite flow has created one (issue #6), in which case {@code %profile} cannot be substituted
     * @param access the {@code access[].parameter} entries, name → reference
     */
    public static PolicyParameters forMembership(String profile, Map<String, String> access) {
        Map<String, String> references = new LinkedHashMap<>();
        if (profile != null && !profile.isBlank()) {
            references.put("profile", profile);
            // %patient defaults to the profile (BB-R-006.3); an explicit access parameter overrides it below
            references.put("patient", profile);
        }
        if (access != null) {
            access.forEach((name, reference) -> {
                if (name != null && reference != null && !reference.isBlank()) {
                    references.put(name.startsWith("%") ? name.substring(1) : name, reference);
                }
            });
        }
        return new PolicyParameters(Map.copyOf(references));
    }

    /**
     * @return the text with every placeholder replaced
     * @throws CriteriaRejectedException when a placeholder has no value — never substitute nothing
     */
    public String substitute(String text) {
        if (text == null || text.indexOf('%') < 0) {
            return text;
        }
        List<String> unresolved = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            boolean idOnly = matcher.group(2) != null;
            String reference = references.get(name);
            if (reference == null) {
                unresolved.add("%" + name + (idOnly ? ".id" : ""));
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            // .id is handled here rather than by a second pass, which is what "longest first" means in practice
            String value = idOnly ? reference.substring(reference.indexOf('/') + 1) : reference;
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        if (!unresolved.isEmpty()) {
            throw new CriteriaRejectedException(unresolved.get(0),
                    ("This policy uses %s, which this membership has no value for. Substituting nothing would widen "
                            + "access rather than narrow it, so the policy is refused. Known parameters: %s.")
                            .formatted(String.join(", ", unresolved), known()));
        }
        return out.toString();
    }

    /** Names this membership can resolve, for the rejection message. */
    public String known() {
        return references.isEmpty() ? "(none — this membership has no profile and no access parameters)"
                : references.keySet().stream().map(name -> "%" + name).sorted().reduce((a, b) -> a + ", " + b).orElseThrow();
    }

    public boolean canResolve(String name) {
        return references.containsKey(name.startsWith("%") ? name.substring(1) : name);
    }
}
