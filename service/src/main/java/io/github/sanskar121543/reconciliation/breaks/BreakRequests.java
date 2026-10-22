package io.github.sanskar121543.reconciliation.breaks;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Request bodies for the break workflow. Each change names the {@code version} it was based on (from the break
 * as last read), so two people acting on the same break cannot silently overwrite each other: the second gets
 * a 409 and reads it again.
 */
public final class BreakRequests {

    /** Same charset as the database's check on {@code breaks.assignee}. */
    static final String ASSIGNEE = "[A-Za-z0-9._@-]{1,64}";
    static final int MAX_NOTE = 2000;
    // @NotBlank for an optional field: null passes, "   " does not.
    private static final String SOME_TEXT = "(?s).*\\S.*";

    private BreakRequests() {
    }

    /** {@code assignee} defaults to whoever already owns the break, then to the caller. The note is optional. */
    public record Investigate(@NotNull @PositiveOrZero Long version, @Pattern(regexp = ASSIGNEE) String assignee,
            @Pattern(regexp = SOME_TEXT, message = "must not be blank") @Size(max = MAX_NOTE) String note) {
    }

    public record Resolve(@NotNull @PositiveOrZero Long version, @NotNull BreakResolution resolution,
            @NotBlank @Size(max = MAX_NOTE) String note) {
    }

    public record Reopen(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = MAX_NOTE) String note) {
    }

    public record Assign(@NotNull @PositiveOrZero Long version, @NotNull @Pattern(regexp = ASSIGNEE) String assignee) {
    }

    public record Note(@NotBlank @Size(max = MAX_NOTE) String body) {
    }
}
