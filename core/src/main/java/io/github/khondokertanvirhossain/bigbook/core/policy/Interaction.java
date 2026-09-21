package io.github.khondokertanvirhossain.bigbook.core.policy;

import java.util.Locale;
import java.util.Optional;

/**
 * The interactions an {@code AccessPolicy.resource[].interaction[]} entry can name (BB-R-006.1). An enum
 * rather than a string, so an unknown interaction is refused when the policy is written and a hook can
 * switch on it exhaustively.
 */
public enum Interaction {
    CREATE,
    READ,
    UPDATE,
    DELETE,
    SEARCH,
    HISTORY,
    VREAD;

    /** Reads are the interactions {@code hiddenFields} applies to and a criteria drop can filter. */
    public boolean read() {
        return this == READ || this == SEARCH || this == HISTORY || this == VREAD;
    }

    public boolean write() {
        return !read();
    }

    /** @return empty when the name is not one Medplum defines, which the validator turns into a 400 */
    public static Optional<Interaction> of(String name) {
        if (name == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(name.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException notAnInteraction) {
            return Optional.empty();
        }
    }
}
