package io.github.khondokertanvirhossain.bigbook.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A conditional update that matches nothing creates the resource, and Medplum refuses that when the body
 * carries an `id`: 400 "Cannot perform create as update with client-assigned ID" (parity row a2). The
 * client's id would be silently discarded otherwise, which is the surprise the client-assigned-id rule
 * exists to prevent (issue #8, BB-R-014.1).
 *
 * <p>It is a filter rather than an interceptor because HAPI's {@code UpdateMethodBinding} calls
 * {@code theResource.setId(null)} on a conditional update <em>before</em> the first pointcut fires
 * (8.12.1), so no interceptor can see the id the client sent.
 */
public class ConditionalUpdateIdFilter extends OncePerRequestFilter {

    private static final int MAX_PEEKED_BODY = 1024 * 1024;

    private final OperationOutcomes outcomes;
    private final ObjectMapper json;

    public ConditionalUpdateIdFilter(OperationOutcomes outcomes, ObjectMapper json) {
        this.outcomes = outcomes;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // PUT <base>/<Type>?<query> — a conditional update: one path segment after the FHIR base, and a query
        return !"PUT".equals(request.getMethod())
                || request.getQueryString() == null
                || request.getRequestURI().split("/" + FhirServerConfig.FHIR_PATH.substring(1) + "/").length != 2
                || request.getRequestURI().replaceAll(".*/" + FhirServerConfig.FHIR_PATH.substring(1) + "/", "").contains("/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_PEEKED_BODY);
        String id = idIn(body);
        if (id != null) {
            outcomes.write(response, 400, "Cannot perform create as update with client-assigned ID: remove 'id' from"
                    + " the body, or PUT to " + request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1)
                    + "/" + id + ".");
            return;
        }
        chain.doFilter(new BodyCarryingRequest(request, body), response);
    }

    private String idIn(byte[] body) {
        try {
            JsonNode resource = json.readTree(body);
            JsonNode id = resource.get("id");
            // a malformed body is HAPI's to reject, with its own message
            return id != null && id.isTextual() && !id.asText().isBlank() ? id.asText() : null;
        } catch (IOException notJson) {
            return null;
        }
    }

    /** The body was consumed to look at it, so hand HAPI a request that can be read again. */
    private static final class BodyCarryingRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BodyCarryingRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return source.read();
                }
            };
        }
    }
}
