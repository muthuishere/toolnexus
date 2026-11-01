package io.github.muthuishere.toolnexus;

import java.util.List;

/**
 * The host's search provider for the opt-in {@code websearch} builtin (SPEC §4C). The library
 * ships none. Pass it as the {@code webSearch} key of the {@code builtins} config map.
 * Cancellation reaches it as a thread interrupt, like every other tool.
 */
@FunctionalInterface
public interface WebSearch {
    /** Return up to {@code limit} hits for {@code query}. A throw becomes an error result. */
    List<Result> search(String query, int limit) throws Exception;

    /** One hit. {@code snippet} may be empty. */
    record Result(String title, String url, String snippet) {}
}
