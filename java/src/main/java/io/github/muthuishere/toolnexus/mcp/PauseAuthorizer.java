package io.github.muthuishere.toolnexus.mcp;

/** Never blocks: every authorization becomes a {@link PendingException}. */
public record PauseAuthorizer(String redirect) implements Authorizer, Authorizer.Session {
    @Override
    public Session begin() { return this; }

    @Override
    public String redirectUri() { return redirect; }

    @Override
    public String await(Pending p) throws Pause { throw new Pause(); }

    @Override
    public void close() {}
}
