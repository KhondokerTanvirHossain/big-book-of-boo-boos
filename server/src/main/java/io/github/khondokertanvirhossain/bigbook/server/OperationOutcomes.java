package io.github.khondokertanvirhossain.bigbook.server;

import ca.uhn.fhir.context.FhirContext;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.hl7.fhir.r4.model.OperationOutcome;

/** Every refusal outside HAPI's own servlet leaves as an OperationOutcome too (ADR-007, BB-R-014). */
public class OperationOutcomes {

    private final FhirContext fhirContext;

    public OperationOutcomes(FhirContext fhirContext) {
        this.fhirContext = fhirContext;
    }

    public void write(HttpServletResponse response, int status, String message) throws IOException {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(code(status))
                .setDiagnostics(message);
        response.setStatus(status);
        response.setContentType("application/fhir+json;charset=UTF-8");
        fhirContext.newJsonParser().encodeResourceToWriter(outcome, response.getWriter());
    }

    private static OperationOutcome.IssueType code(int status) {
        return switch (status) {
            case 400 -> OperationOutcome.IssueType.INVALID;
            case 403 -> OperationOutcome.IssueType.FORBIDDEN;
            case 404 -> OperationOutcome.IssueType.NOTFOUND;
            case 409 -> OperationOutcome.IssueType.CONFLICT;
            default -> OperationOutcome.IssueType.EXCEPTION;
        };
    }
}
