package io.github.muthuishere.toolnexus.mcp2026;

/** Decides how the user reaches the authorization URL and how the callback comes back. */
public interface Authorizer {
    Session begin() throws Exception;

    interface Session extends AutoCloseable {
        String redirectUri();

        /** Returns the full callback URL, or throws {@link Pause} to hand a Pending to the caller. */
        String await(Pending p) throws Exception;

        @Override
        void close();
    }

    /** What a pausing session throws from {@link Session#await}. */
    final class Pause extends Exception {
        public Pause() { super("authorization paused", null, false, false); }
    }
}
