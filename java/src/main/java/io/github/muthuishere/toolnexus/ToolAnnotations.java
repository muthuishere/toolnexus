package io.github.muthuishere.toolnexus;

/**
 * MCP tool annotations advertised by the embeddable MCP handler (SPEC §1, §7C.1), verbatim on the
 * wire. Every field is optional: a {@code null} field is omitted. When a tool has no annotations,
 * the handler derives {@code readOnlyHint}/{@code destructiveHint}/{@code openWorldHint} from
 * {@link Tool#hints()}, else omits {@code annotations}.
 */
public record ToolAnnotations(String title, Boolean readOnlyHint, Boolean destructiveHint,
                              Boolean idempotentHint, Boolean openWorldHint) {
    /** Only a title. */
    public static ToolAnnotations titled(String title) {
        return new ToolAnnotations(title, null, null, null, null);
    }
}
