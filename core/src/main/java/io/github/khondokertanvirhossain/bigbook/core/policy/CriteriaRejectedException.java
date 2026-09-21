package io.github.khondokertanvirhossain.bigbook.core.policy;

/**
 * A criterion Big Book will not accept, thrown by the validator at write time (D15). The message names the
 * parameter, because "invalid criteria" tells an author nothing about which one to fix.
 */
public class CriteriaRejectedException extends RuntimeException {

    private final String parameter;

    public CriteriaRejectedException(String parameter, String message) {
        super(message);
        this.parameter = parameter;
    }

    /** The offending parameter, e.g. {@code status} or {@code subject.name}; null when it is the whole URL. */
    public String parameter() {
        return parameter;
    }
}
