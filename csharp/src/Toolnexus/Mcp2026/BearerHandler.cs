namespace Toolnexus.Mcp2026;

/// <summary>
/// SPEC §2A.1a "Frozen fallback + OAuth": attaches the current bearer to every request the MCP SDK's HTTP
/// transport sends, and on a 401 / 403 insufficient_scope carrying WWW-Authenticate runs the same OAuth flow
/// once and re-sends (the server rejected the request before dispatch). A pause is answered through
/// <c>waitFor</c>; without one the request fails with <c>authorization pending</c>.
/// </summary>
internal sealed class BearerHandler(OAuthClient auth, string resource, Func<Request, Task<Answer>>? waitFor) : DelegatingHandler
{
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        byte[]? body = request.Content != null ? await request.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false) : null;
        var contentHeaders = request.Content?.Headers.ToList();
        var sent = await auth.BearerAsync(resource, ct).ConfigureAwait(false);
        if (sent != "") request.Headers.Authorization = new("Bearer", sent);
        if (body != null) request.Content = Clone(body, contentHeaders!);
        var resp = await base.SendAsync(request, ct).ConfigureAwait(false);
        var st = (int)resp.StatusCode;
        var www = resp.Headers.NonValidated.TryGetValues("WWW-Authenticate", out var v) ? string.Join(", ", v) : "";
        if ((st != 401 && st != 403) || www == "") return resp;
        try
        {
            await auth.ChallengeAsync(resource, st, www, sent, ct).ConfigureAwait(false);
        }
        catch (PendingAuthorizationException pa)
        {
            if (waitFor == null) throw new HttpRequestException("authorization pending");
            var answer = await waitFor(pa.Request).ConfigureAwait(false);
            if (answer == null || !answer.Ok)
                throw new HttpRequestException("authorization denied: " + (string.IsNullOrEmpty(answer?.Reason) ? "cancelled" : answer!.Reason));
            await auth.ResumeAsync(pa.Request.Id, answer.Data?.Get("callbackUrl") as string ?? "", ct).ConfigureAwait(false);
        }
        catch (WireException e) { throw new HttpRequestException(e.Message); }
        resp.Dispose();
        var retry = new HttpRequestMessage(request.Method, request.RequestUri) { Version = request.Version };
        foreach (var h in request.Headers) if (h.Key != "Authorization") retry.Headers.TryAddWithoutValidation(h.Key, h.Value);
        var bearer = await auth.BearerAsync(resource, ct).ConfigureAwait(false);
        if (bearer != "") retry.Headers.Authorization = new("Bearer", bearer);
        if (body != null) retry.Content = Clone(body, contentHeaders!);
        return await base.SendAsync(retry, ct).ConfigureAwait(false);
    }

    private static ByteArrayContent Clone(byte[] body, List<KeyValuePair<string, IEnumerable<string>>> headers)
    {
        var c = new ByteArrayContent(body);
        foreach (var h in headers) c.Headers.TryAddWithoutValidation(h.Key, h.Value);
        return c;
    }
}
