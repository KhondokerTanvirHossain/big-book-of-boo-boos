package io.github.khondokertanvirhossain.bigbook.core.tenant;

/** A tenancy refusal with the HTTP status it maps to; rendered as an OperationOutcome at the edge. */
public class TenantException extends RuntimeException {

    private final int status;

    public TenantException(int status, String message) {
        super(message);
        this.status = status;
    }

    public TenantException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
