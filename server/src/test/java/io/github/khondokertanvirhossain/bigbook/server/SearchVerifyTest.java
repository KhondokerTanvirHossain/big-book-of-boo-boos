package io.github.khondokertanvirhossain.bigbook.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Issue #9's verify-first block (V2, V4, V6, V9), measured on HAPI 8.12.1 rather than assumed. Each one changes
 * what the task is:
 *
 * <ul>
 *   <li><b>V2</b> — which {@code _filter} operators work, and whether dotted paths do. Decides whether
 *       BB-R-002.5 stays "wire HAPI" or needs glue.
 *   <li><b>V4</b> — are runtime {@code SearchParameter} resources enabled by default? A free gain over
 *       Medplum's boot-time bundles if so.
 *   <li><b>V6</b> — the {@code _count} cap, the default {@code _total} mode, and the page-link shape, for
 *       {@code medplum-parity.md}.
 *   <li><b>V9</b> — do {@code _summary}/{@code _elements} results carry {@code meta.tag} {@code SUBSETTED}?
 *       D44: {@code @medplum/core} refuses to cache tagged resources, and without the tag a partial resource
 *       poisons its read cache.
 * </ul>
 *
 * <p>Prints {@code VERIFY-*} lines; assertions are deliberately loose where the point is to <i>record</i>
 * behaviour rather than require it.
 */
class SearchVerifyTest extends LiteStackTest {

    static UUID project;
    static String user;

    @BeforeEach
    void oneProjectOneSeat() {
        if (project != null) {
            return;
        }
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/admin/projects", superAdminToken(),
                Map.of("name", "SearchVerify"), JsonNode.class);
        project = UUID.fromString(created.getBody().path("id").asText());
        user = "Search-" + UUID.randomUUID() + "@bigbook.test";
        seedSeat(project, user, seedUser(user), true);
    }

    /** V2: the `_filter` operator set, and dotted paths. */
    @Test
    void v2FilterOperators() {
        String token = tokenFor(user, project);
        create(token, "Patient", Map.of("resourceType", "Patient",
                "name", List.of(Map.of("family", "Simpson", "given", List.of("Homer"))),
                "birthDate", "1956-05-12"));

        record Probe(String label, String query) {}
        for (Probe probe : List.of(
                new Probe("eq", "name eq \"Simpson\""),
                new Probe("co", "name co \"impso\""),
                new Probe("sw", "name sw \"Sim\""),
                new Probe("ne", "name ne \"Flanders\""),
                new Probe("gt on date", "birthdate gt 1950-01-01"),
                new Probe("and", "name eq \"Simpson\" and birthdate gt 1950-01-01"),
                new Probe("or", "name eq \"Simpson\" or name eq \"Flanders\""),
                new Probe("not", "not (name eq \"Flanders\")"),
                new Probe("not, no space", "not(name eq \"Flanders\")"),
                new Probe("ne as the negation", "name ne \"Flanders\""),
                new Probe("dotted path (Medplum recurses)", "subject.name eq \"Simpson\""),
                new Probe("parenthesised", "(name eq \"Simpson\") and (birthdate gt 1950-01-01)"))) {
            ResponseEntity<JsonNode> response = call(HttpMethod.GET,
                    "/fhir/R4/Patient?_filter=" + encode(probe.query()), token, null, JsonNode.class,
                    "Cache-Control", "no-cache");
            String outcome = response.getStatusCode().is2xxSuccessful()
                    ? "OK total=" + response.getBody().path("total").asInt()
                            + " entries=" + response.getBody().path("entry").size()
                    : response.getStatusCode() + " " + diagnostics(response);
            System.out.println("VERIFY-V2 " + probe.label() + " [" + probe.query() + "] -> " + outcome);
        }
    }

    /** V4: is a runtime-registered custom SearchParameter accepted and honoured? */
    @Test
    void v4RuntimeSearchParameter() {
        String token = tokenFor(user, project);
        ResponseEntity<JsonNode> registered = call(HttpMethod.POST, "/fhir/R4/SearchParameter", token,
                Map.of("resourceType", "SearchParameter",
                        "url", "http://bigbook.test/SearchParameter/patient-eye-colour",
                        "name", "eyecolour", "status", "active", "code", "eye-colour",
                        "base", List.of("Patient"), "type", "token",
                        "expression", "Patient.extension('http://bigbook.test/eye-colour').value"),
                JsonNode.class);
        System.out.println("VERIFY-V4 register custom SearchParameter -> " + registered.getStatusCode()
                + (registered.getStatusCode().is2xxSuccessful() ? "" : " " + diagnostics(registered)));

        if (!registered.getStatusCode().is2xxSuccessful()) {
            return;
        }
        create(token, "Patient", Map.of("resourceType", "Patient",
                "name", List.of(Map.of("family", "EyeProbe")),
                "extension", List.of(Map.of("url", "http://bigbook.test/eye-colour", "valueString", "green"))));
        ResponseEntity<JsonNode> searched = call(HttpMethod.GET, "/fhir/R4/Patient?eye-colour=green", token, null,
                JsonNode.class, "Cache-Control", "no-cache");
        System.out.println("VERIFY-V4 search by custom param -> " + searched.getStatusCode()
                + " entries=" + searched.getBody().path("entry").size()
                + " (0 entries may mean it needs $reindex)");
    }

    /** V6: the _count cap, default _total mode, and page-link shape. */
    @Test
    void v6CountCapTotalModeAndLinks() {
        String token = tokenFor(user, project);
        for (int i = 0; i < 3; i++) {
            create(token, "Patient", Map.of("resourceType", "Patient",
                    "name", List.of(Map.of("family", "Paging" + i))));
        }

        ResponseEntity<JsonNode> defaultSearch = call(HttpMethod.GET, "/fhir/R4/Patient", token, null,
                JsonNode.class, "Cache-Control", "no-cache");
        System.out.println("VERIFY-V6 default: total present=" + defaultSearch.getBody().has("total")
                + " total=" + defaultSearch.getBody().path("total").asText("<absent>"));
        System.out.println("VERIFY-V6 default links=" + linkRelations(defaultSearch.getBody()));

        for (String count : List.of("2", "1000", "1001", "5000")) {
            ResponseEntity<JsonNode> capped = call(HttpMethod.GET, "/fhir/R4/Patient?_count=" + count, token, null,
                    JsonNode.class, "Cache-Control", "no-cache");
            System.out.println("VERIFY-V6 _count=" + count + " -> " + capped.getStatusCode()
                    + " entries=" + capped.getBody().path("entry").size()
                    + " links=" + linkRelations(capped.getBody()));
        }

        ResponseEntity<JsonNode> accurate = call(HttpMethod.GET, "/fhir/R4/Patient?_total=accurate", token, null,
                JsonNode.class, "Cache-Control", "no-cache");
        System.out.println("VERIFY-V6 _total=accurate -> total=" + accurate.getBody().path("total").asText("<absent>"));

        // does the next link echo the caller's parameters (D17) or re-serialise them?
        ResponseEntity<JsonNode> paged = call(HttpMethod.GET, "/fhir/R4/Patient?_count=2&_summary=true", token, null,
                JsonNode.class, "Cache-Control", "no-cache");
        System.out.println("VERIFY-V6 next link with _summary=true -> " + nextLink(paged.getBody()));
    }

    /** V9: SUBSETTED tagging on _summary and _elements (D44). */
    @Test
    void v9SubsettedTagging() {
        String token = tokenFor(user, project);
        create(token, "Patient", Map.of("resourceType", "Patient",
                "name", List.of(Map.of("family", "Subsetted")), "birthDate", "1970-01-01"));

        for (String partial : List.of("_summary=true", "_summary=text", "_elements=name")) {
            ResponseEntity<JsonNode> response = call(HttpMethod.GET, "/fhir/R4/Patient?" + partial, token, null,
                    JsonNode.class, "Cache-Control", "no-cache");
            JsonNode first = response.getBody().path("entry").path(0).path("resource");
            boolean tagged = first.path("meta").path("tag").toString().contains("SUBSETTED");
            System.out.println("VERIFY-V9 " + partial + " -> tagged=" + tagged
                    + " meta=" + first.path("meta").toString());
        }
    }

    private String create(String token, String type, Object body) {
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/fhir/R4/" + type, token, body, JsonNode.class);
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        return type + "/" + created.getBody().path("id").asText();
    }

    private static String linkRelations(JsonNode bundle) {
        StringBuilder out = new StringBuilder();
        bundle.path("link").forEach(link -> out.append(link.path("relation").asText()).append(" "));
        return out.toString().trim();
    }

    private static String nextLink(JsonNode bundle) {
        for (JsonNode link : bundle.path("link")) {
            if ("next".equals(link.path("relation").asText())) {
                return link.path("url").asText();
            }
        }
        return "<no next>";
    }

    private static String diagnostics(ResponseEntity<JsonNode> response) {
        return response.getBody() == null ? "" : response.getBody().path("issue").path(0).path("diagnostics").asText();
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
