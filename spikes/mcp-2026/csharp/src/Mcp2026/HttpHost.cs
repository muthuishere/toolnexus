using System.Collections.Concurrent;
using System.Net;
using System.Net.Security;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace Mcp2026;

/// <summary>
/// A tiny Kestrel host (the C# stand-in for Go's httptest.Server / http.Serve): one RequestDelegate,
/// bound to host:port (0 = random), optionally TLS. <c>Url</c> is the base URL, no trailing slash.
/// </summary>
public sealed class HttpHost : IAsyncDisposable
{
    private readonly WebApplication _app;
    public string Url { get; }
    public X509Certificate2? Certificate { get; }

    private HttpHost(WebApplication app, string url, X509Certificate2? cert) { _app = app; Url = url; Certificate = cert; }

    /// <summary>Serves an MCP <see cref="Server"/>.</summary>
    public static Task<HttpHost> StartAsync(Server server, string host = "127.0.0.1", int port = 0, bool tls = false) =>
        StartAsync(server.HandleAsync, host, port, tls);

    public static async Task<HttpHost> StartAsync(RequestDelegate handler, string host = "127.0.0.1", int port = 0, bool tls = false)
    {
        var cert = tls ? DevTrust.CreateCertificate() : null;
        var b = WebApplication.CreateSlimBuilder(new WebApplicationOptions { Args = [] });
        b.Logging.ClearProviders();
        b.WebHost.ConfigureKestrel(k =>
        {
            k.Limits.MaxRequestBodySize = null; // Server enforces its own cap (413)
            k.Listen(IPAddress.Parse(host == "localhost" ? "127.0.0.1" : host), port, lo => { if (cert != null) lo.UseHttps(cert); });
        });
        var app = b.Build();
        app.Run(handler);
        await app.StartAsync();
        var addr = app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.First();
        return new HttpHost(app, addr.TrimEnd('/'), cert);
    }

    /// <summary>Blocks until the host stops (CLI server mode).</summary>
    public Task WaitAsync() => Microsoft.Extensions.Hosting.HostingAbstractionsHostExtensions.WaitForShutdownAsync(_app);

    public async ValueTask DisposeAsync()
    {
        try { using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(1)); await _app.StopAsync(cts.Token); } catch { /* already stopped */ }
        await _app.DisposeAsync();
    }
}

/// <summary>
/// DEV/TEST ONLY: self-signed 127.0.0.1 certificates that every default HttpClient in this process
/// trusts (the C# counterpart of Go's trustDevCert), so DevAuthServer needs no HTTP wiring.
/// </summary>
public static class DevTrust
{
    private static readonly ConcurrentDictionary<string, bool> Trusted = new();

    public static X509Certificate2 CreateCertificate()
    {
        using var key = RSA.Create(2048);
        var req = new CertificateRequest("CN=127.0.0.1", key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        var san = new SubjectAlternativeNameBuilder();
        san.AddIpAddress(IPAddress.Loopback);
        san.AddDnsName("localhost");
        req.CertificateExtensions.Add(san.Build());
        using var tmp = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddDays(7));
        var cert = X509CertificateLoader.LoadPkcs12(tmp.Export(X509ContentType.Pfx), null);
        Trusted[cert.Thumbprint] = true;
        return cert;
    }

    internal static bool Validate(X509Certificate? cert, SslPolicyErrors errors) =>
        errors == SslPolicyErrors.None || (cert != null && Trusted.ContainsKey(new X509Certificate2(cert).Thumbprint));

    /// <summary>A handler that trusts system roots plus dev certificates.</summary>
    public static SocketsHttpHandler Handler(bool followRedirects = true, CookieContainer? cookies = null) => new()
    {
        AllowAutoRedirect = followRedirects,
        UseCookies = cookies != null,
        CookieContainer = cookies ?? new CookieContainer(),
        MaxConnectionsPerServer = 256,
        PooledConnectionIdleTimeout = TimeSpan.FromSeconds(30),
        SslOptions = new SslClientAuthenticationOptions { RemoteCertificateValidationCallback = (_, c, _, e) => Validate(c, e) },
    };

    /// <summary>The shared default client: 30 s timeout, trusts in-process dev certificates.</summary>
    public static readonly HttpClient Shared = new(Handler()) { Timeout = McpClient.DefaultTimeout };
}
