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

        public boolean covers(String type) {
            return "*".equals(resourceType) || resourceType.equals(type);
        }

        public boolean permits(Interaction interaction) {
            return interactions.contains(interaction);
        }
    }

    public CompiledPolicy {
        entries = entries == null ? List.of() : List.copyOf(entries);
        policyIds = policyIds == null ? List.of() : List.copyOf(policyIds);
    }

    /** A super-admin: {@code allowAll}, and nothing else in this record is consulted (T1). */
    public static CompiledPolicy superAdmin() {
        return new CompiledPolicy(true, List.of(), List.of());
    }

    /** A membership with no policy at all compiles to full project access (T1, BB-R-006.2). */
    public static CompiledPolicy fullProjectAccess() {
        return new CompiledPolicy(false,
                List.of(new Entry("*", new Criteria.Always(), Set.of(Interaction.values()), List.of(), List.of(), null)),
                List.of());
    }

    /** Nothing is permitted. What an unreadable or wholly refused policy set becomes — fails closed (D14). */
    public static CompiledPolicy denyAll(String because) {
        return new CompiledPolicy(false,
                List.of(new Entry("*", new Criteria.Never(because), Set.of(), List.of(), List.of(), null)),
                List.of());
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
