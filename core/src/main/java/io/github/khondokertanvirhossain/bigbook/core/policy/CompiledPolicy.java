package io.github.khondokertanvirhossain.bigbook.core.policy;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the enforcement path needs for <b>one membership</b>, already resolved: the only thing a hook
 * is ever given (ADR-001, and #7's seam).
 *
 * <p>The point of the record is what it makes impossible. There is no {@code Optional<AccessPolicy>}, no
 * "have the parameters been substituted yet?", no reachable half-compiled state: if you hold a
 * {@code CompiledPolicy}, substitution has happened, every criterion has been validated or turned into
 * {@link Criteria.Never}, and the interaction sets are closed. A hook cannot ask a question whose answer
 * would depend on compilation having finished.
 *
 * @param bypass the caller is a super-admin, the only bypass there is (T1) — every other field is then unread
 * @param entries one per {@code AccessPolicy.resource[]} entry, after substitution and validation
 * @param policyIds the {@code AccessPolicy} ids this was compiled from, for the denial log and {@code basedOn}
 */
public record CompiledPolicy(boolean bypass, List<Entry> entries, List<String> policyIds) {

    /**
     * One resource-type rule.
     *
     * @param resourceType the FHIR type, or {@code *} — which never covers the project-admin types (T1)
     * @param criteria what a resource must satisfy; {@link Criteria.Never} denies, {@link Criteria.Always} does not restrict
     * @param interactions which interactions are allowed at all
     * @param hiddenFields FHIRPath-ish field paths removed on the way out
     * @param readonlyFields field paths restored from the stored version on the way in
     * @param compartment optional compartment the resource must be in
     */
    public record Entry(
            String resourceType,
            Criteria criteria,
            Set<Interaction> interactions,
            List<String> hiddenFields,
            List<String> readonlyFields,
            String compartment) {

        public Entry {
            // a record's constructor is the one place these can be made unrepresentable
            resourceType = resourceType == null ? "*" : resourceType;
            criteria = criteria == null ? new Criteria.Never("no criteria compiled") : criteria;
            interactions = interactions == null || interactions.isEmpty() ? Set.of(Interaction.values()) : Set.copyOf(interactions);
            hiddenFields = hiddenFields == null ? List.of() : List.copyOf(hiddenFields);
            readonlyFields = readonlyFields == null ? List.of() : List.copyOf(readonlyFields);
        }

        /**
         * T1: {@code *} covers every type <b>except</b> the project-admin types. A policy saying "everything"
         * must not hand out {@code Project} or {@code User}; those come only from an entry naming them, which
         * is what {@code AdminTypeRules.forAdminMembership()} injects for an admin membership.
         *
         * <p>This lives on the record rather than in the compiler because it is an invariant: a caller that
         * builds a {@code CompiledPolicy} by hand must not be able to route around it.
         *
         * <p><b>Counting (ruled 2026-09-22):</b> this line sits on both sides of the rule — it prevents an
         * invalid state <i>and</i> it decides access. It is <b>excluded</b>, because T1 makes "a wildcard that
         * includes {@code Project}" invalid by definition, so removing the line would make an invalid state
         * representable. The call is written here rather than left silent.
         */
        public boolean covers(String type) {
            if (resourceType.equals(type)) {
                return true;
            }
            return "*".equals(resourceType) && !AdminTypeRules.isProjectAdminType(type);
        }

        public boolean permits(Interaction interaction) {
            return interactions.contains(interaction);
        }
    }

    public CompiledPolicy {
        entries = entries == null ? List.of() : List.copyOf(entries);
        policyIds = policyIds == null ? List.of() : List.copyOf(policyIds);
    }

    /** The entries that could apply to a type and interaction; empty means the request is denied. */
    public List<Entry> applicable(String resourceType, Interaction interaction) {
        return entries.stream()
                .filter(entry -> entry.covers(resourceType) && entry.permits(interaction))
                .toList();
    }

    /**
     * What a resource of this type must satisfy: the OR of every applicable entry's criteria (T2). Returns
     * {@link Criteria.Never} when no entry applies, so a caller that forgets to check {@link #applicable}
     * still denies rather than allows.
     */
    public Criteria criteriaFor(String resourceType, Interaction interaction) {
        if (bypass) {
            return new Criteria.Always();
        }
        List<Entry> applicable = applicable(resourceType, interaction);
        if (applicable.isEmpty()) {
            return new Criteria.Never("no policy entry covers " + resourceType + " for " + interaction);
        }
        return Criteria.anyOf(applicable.stream().map(Entry::criteria).toList());
    }

    /** Field paths to strip from a returned resource, from every entry that applies to a read. */
    public List<String> hiddenFieldsFor(String resourceType) {
        if (bypass) {
            return List.of();
        }
        return applicable(resourceType, Interaction.READ).stream()
                .flatMap(entry -> entry.hiddenFields().stream())
                .distinct()
                .toList();
    }

    /** Field paths to restore from the stored version on a write. */
    public List<String> readonlyFieldsFor(String resourceType) {
        if (bypass) {
            return List.of();
        }
        return entries.stream()
                .filter(entry -> entry.covers(resourceType))
                .flatMap(entry -> entry.readonlyFields().stream())
                .distinct()
                .toList();
    }

    /** For the denial log: which policies produced this decision. */
    public Map<String, Object> describe() {
        return Map.of("bypass", bypass, "entries", entries.size(), "policies", policyIds);
    }
}
