package io.github.khondokertanvirhossain.bigbook.server.policy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.khondokertanvirhossain.bigbook.core.policy.PolicyCompiler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Loads {@code AccessPolicy} documents for a membership, <b>always scoped to that membership's project</b>.
 *
 * <p>The project scope is not a convenience filter: it is the tenancy boundary for this table. Every query
 * here takes {@code projectId} and puts it in the {@code WHERE} clause, so a membership of project A cannot
 * load a policy of project B even if something hands it B's id. A policy reference that does not resolve
 * inside the caller's project is treated as missing, and a missing policy is a refusal — never a skip.
 */
public class AccessPolicyStore {

    private static final Logger log = LoggerFactory.getLogger(AccessPolicyStore.class);
    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AccessPolicyStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @param references {@code AccessPolicy/<id>} references, in attachment order — order matters because
     *     the compiler concatenates entries and the first match for a type wins
     * @return one document per reference that resolved; a reference naming a policy this project does not have
     *     yields {@link PolicyCompiler#document} with no resources, which grants nothing
     */
    public List<PolicyCompiler.PolicyDocument> load(UUID projectId, List<String> references) {
        List<PolicyCompiler.PolicyDocument> documents = new ArrayList<>();
        for (String reference : references) {
            UUID id = idOf(reference);
            if (id == null) {
                // a reference that is not AccessPolicy/<uuid> cannot be resolved, so it grants nothing
                log.warn("access policy reference {} is not an AccessPolicy id; it grants nothing", reference);
                documents.add(empty(reference));
                continue;
            }
            documents.add(loadOne(projectId, id, reference));
        }
        return documents;
    }

    private PolicyCompiler.PolicyDocument loadOne(UUID projectId, UUID id, String reference) {
        // project_id in the WHERE clause is the tenancy boundary, not an optimisation
        return jdbc.sql("SELECT document FROM bigbook.access_policy WHERE project_id = ? AND id = ?")
                .params(projectId, id)
                .query(String.class)
                .optional()
                .map(document -> parse(reference, document))
                .orElseGet(() -> {
                    log.warn("access policy {} is not in project {}; it grants nothing", reference, projectId);
                    return empty(reference);
                });
    }

    @SuppressWarnings("unchecked")
    private PolicyCompiler.PolicyDocument parse(String reference, String document) {
        try {
            Map<String, Object> parsed = json.readValue(document, DOCUMENT);
            Object resources = parsed.get("resource");
            if (!(resources instanceof List<?> list)) {
                return empty(reference);
            }
            List<Map<String, Object>> rules = new ArrayList<>();
            for (Object rule : list) {
                if (rule instanceof Map<?, ?> map) {
                    rules.add(new LinkedHashMap<>((Map<String, Object>) map));
                }
            }
            return PolicyCompiler.document(reference, rules);
        } catch (com.fasterxml.jackson.core.JacksonException unreadable) {
            // a stored document that will not parse grants nothing (D14). The only exit from this handler is
            // an empty document — see the fail-closed standard pinned in PolicyArchitectureTest rule 4.
            log.warn("access policy {} is not readable; it grants nothing", reference, unreadable);
            return empty(reference);
        }
    }

    /** Stores a new policy in this project. The id is server-assigned, as for every other Big Book resource. */
    public void save(UUID projectId, UUID id, String name, String document) {
        jdbc.sql("INSERT INTO bigbook.access_policy (id, project_id, name, document, version_id) VALUES (?, ?, ?, ?::jsonb, '1')")
                .params(id, projectId, name, document)
                .update();
    }

    /**
     * Replaces a policy <b>within one project</b>. The project_id in the WHERE clause is what makes a
     * cross-project update impossible rather than merely unlikely.
     *
     * @return false when this project has no such policy, which the provider turns into a 404 — never a 403,
     *     because a 403 would confirm that the policy exists in some other project
     */
    public boolean replace(UUID projectId, UUID id, String name, String document) {
        return jdbc.sql("UPDATE bigbook.access_policy SET name = ?, document = ?::jsonb,"
                        + " version_id = (version_id::int + 1)::text, last_updated = now()"
                        + " WHERE project_id = ? AND id = ?")
                .params(name, document, projectId, id)
                .update() > 0;
    }

    /** Reads one policy's stored document, scoped to the project. */
    public java.util.Optional<String> read(UUID projectId, UUID id) {
        return jdbc.sql("SELECT document FROM bigbook.access_policy WHERE project_id = ? AND id = ?")
                .params(projectId, id)
                .query(String.class)
                .optional();
    }

    /** A policy that grants nothing: present in the list, so the caller's other attachments still apply. */
    private static PolicyCompiler.PolicyDocument empty(String reference) {
        return PolicyCompiler.document(reference, List.of());
    }

    private static UUID idOf(String reference) {
        String text = reference.startsWith("AccessPolicy/") ? reference.substring("AccessPolicy/".length()) : reference;
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}
