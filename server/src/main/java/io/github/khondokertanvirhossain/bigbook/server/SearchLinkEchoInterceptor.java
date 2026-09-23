package io.github.khondokertanvirhossain.bigbook.server;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;

/**
 * Paging links echo the caller's {@code _summary} (BB-R-002, D17).
 *
 * <p>HAPI's {@code next} link is an opaque {@code _getpages} cursor, and it propagates <i>most</i> of the
 * caller's parameters into it — measured on 8.12.1 (#9, V6): {@code _count}, {@code _elements} and
 * {@code _include} are carried, <b>{@code _summary} is not</b>.
 *
 * <p>That is a correctness problem rather than cosmetics. A caller who asks for {@code _summary=true} gets a
 * summary first page and <i>full</i> resources on page 2, and per D44 an untagged partial resource poisons
 * {@code @medplum/core}'s read cache — the SDK refuses to cache a {@code SUBSETTED} resource, so it will happily
 * cache the full page-2 copies and serve them as if complete. Medplum re-serialises its links and drops
 * {@code _summary} too; this is the divergence D17 names, and Big Book fixes it rather than reproducing it.
 *
 * <p>{@code _sort} is also absent from HAPI's links and is deliberately <b>not</b> added back: the cursor
 * already holds the result order, so re-stating it would change nothing, and adding a parameter HAPI does not
 * expect to a cursor URL is a way to break paging rather than fix it.
 */
@Interceptor
public class SearchLinkEchoInterceptor {

    /** The link relations that carry a cursor a caller will follow. {@code self} describes the request made. */
    private static final List<String> PAGED_RELATIONS = List.of("next", "previous", "prev");

    @Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
    public void echoSummaryInPagingLinks(RequestDetails request, IBaseResource response) {
        if (!(response instanceof Bundle bundle)) {
            return;
        }
        String[] summary = request.getParameters().get(Constants.PARAM_SUMMARY);
        if (summary == null || summary.length == 0 || summary[0] == null || summary[0].isBlank()) {
            return;
        }
        for (Bundle.BundleLinkComponent link : bundle.getLink()) {
            if (PAGED_RELATIONS.contains(link.getRelation()) && link.hasUrl()) {
                link.setUrl(withSummary(link.getUrl(), summary[0]));
            }
        }
    }

    /** Appends {@code _summary} unless the link already carries one, so a re-run cannot double it. */
    private static String withSummary(String url, String summary) {
        if (url.contains(Constants.PARAM_SUMMARY + "=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + Constants.PARAM_SUMMARY + "="
                + java.net.URLEncoder.encode(summary, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Only the parameters this interceptor repairs; kept for the test to assert against rather than guess. */
    static Map<String, Boolean> echoedByHapi() {
        return Map.of("_count", true, "_elements", true, "_include", true, "_summary", false, "_sort", false);
    }
}
