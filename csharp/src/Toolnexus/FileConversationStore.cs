using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace Toolnexus;

/// <summary>
/// Durable <see cref="IConversationStore"/> (SPEC § Conversation memory): one file per id,
/// <c>&lt;root&gt;/&lt;id&gt;.json</c> holding <c>{"version":1,"messages":[...]}</c>.
/// <para>
/// <see cref="SaveAsync"/> writes a temp file in root (0600 where supported), fsyncs, and renames it
/// over the old file, so a reader sees the old or the new transcript, never a torn one. Power-loss
/// durability of the rename is best-effort. Same-id saves are serialized in-process; across
/// processes the last rename wins.
/// </para>
/// Ids must match <c>^[A-Za-z0-9._-]{1,128}$</c> and not be <c>.</c>/<c>..</c>; anything else is
/// rejected by every operation.
/// </summary>
/// <summary>
/// Durability mode of <see cref="FileConversationStore"/> (SPEC § "Durability mode"). Every mode keeps
/// temp-then-rename, so a process crash never tears a file; they differ only in what survives power loss.
/// </summary>
public enum FileStoreSync
{
    /// <summary>Default: <c>Flush(true)</c> (on macOS the runtime issues <c>F_FULLFSYNC</c>), then a best-effort directory fsync.</summary>
    Full,
    /// <summary>A plain <c>fsync(2)</c> (via libc off Windows), no directory fsync.</summary>
    Fsync,
    /// <summary>No flush.</summary>
    None,
}

public sealed partial class FileConversationStore : IListableConversationStore
{
    private const int Version = 1;
    private readonly string _root;
    private readonly FileStoreSync _sync;
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _locks = new();

    [GeneratedRegex("^[A-Za-z0-9._-]{1,128}$")]
    private static partial Regex IdPattern();

    /// <param name="root">Directory holding the transcripts. Required; created on first save.</param>
    public FileConversationStore(string root) : this(root, FileStoreSync.Full) { }

    /// <param name="root">Directory holding the transcripts. Required; created on first save.</param>
    /// <param name="sync">Durability mode; an undefined value throws.</param>
    public FileConversationStore(string root, FileStoreSync sync)
    {
        if (string.IsNullOrEmpty(root))
            throw new ArgumentException("FileConversationStore: root is required", nameof(root));
        if (!Enum.IsDefined(sync))
            throw new ArgumentException($"FileConversationStore: unknown sync mode {sync}", nameof(sync));
        _root = Path.GetFullPath(root);
        _sync = sync;
    }

    /// <param name="root">Directory holding the transcripts. Required; created on first save.</param>
    /// <param name="sync"><c>"full"</c> | <c>"fsync"</c> | <c>"none"</c>; anything else throws.</param>
    public FileConversationStore(string root, string sync) : this(root, ParseSync(sync)) { }

    private static FileStoreSync ParseSync(string? sync) => sync switch
    {
        "full" => FileStoreSync.Full,
        "fsync" => FileStoreSync.Fsync,
        "none" => FileStoreSync.None,
        _ => throw new ArgumentException($"FileConversationStore: unknown sync mode \"{sync}\"", nameof(sync)),
    };

    /// <summary>The durability mode.</summary>
    public FileStoreSync Sync => _sync;

    [DllImport("libc", EntryPoint = "fsync", SetLastError = true)]
    private static extern int SysFsync(SafeHandle fd);

    [DllImport("libc", EntryPoint = "fsync", SetLastError = true)]
    private static extern int SysFsyncFd(int fd);

    [DllImport("libc", EntryPoint = "open", SetLastError = true)]
    private static extern int SysOpen(string path, int flags);

    [DllImport("libc", EntryPoint = "close", SetLastError = true)]
    private static extern int SysClose(int fd);

    private static void PlainFsync(FileStream fs)
    {
        if (OperatingSystem.IsWindows()) { fs.Flush(flushToDisk: true); return; }
        if (SysFsync(fs.SafeFileHandle) != 0)
            throw new IOException($"FileConversationStore: fsync failed (errno {Marshal.GetLastPInvokeError()})");
    }

    // Best-effort: fsync the directory so the rename itself survives power loss.
    private void FsyncDir()
    {
        if (OperatingSystem.IsWindows()) return;
        try
        {
            var fd = SysOpen(_root, 0 /* O_RDONLY */);
            if (fd < 0) return;
            SysFsyncFd(fd);
            SysClose(fd);
        }
        catch { }
    }

    /// <summary>The resolved root directory.</summary>
    public string Root => _root;

    private static bool IsValidId(string? id) => id != null && id != "." && id != ".." && IdPattern().IsMatch(id);

    private string PathFor(string id)
    {
        if (!IsValidId(id))
            throw new ArgumentException($"FileConversationStore: invalid conversation id \"{id}\"", nameof(id));
        return Path.Combine(_root, id + ".json");
    }

    /// <inheritdoc />
    public async Task<List<object?>?> GetAsync(string id)
    {
        var path = PathFor(id);
        string text;
        try
        {
            text = await File.ReadAllTextAsync(path).ConfigureAwait(false);
        }
        catch (FileNotFoundException) { return null; }
        catch (DirectoryNotFoundException) { return null; }

        Dictionary<string, object?> doc;
        try
        {
            doc = Json.ToMap(text);
        }
        catch (Exception e)
        {
            throw new InvalidDataException($"FileConversationStore: corrupt conversation \"{id}\": {e.Message}", e);
        }
        var version = doc.Get("version") switch { long l => l, int i => i, double d => d, _ => (object?)null };
        if (version is not (1L or 1 or 1.0))
            throw new InvalidDataException($"FileConversationStore: conversation \"{id}\" has unsupported version {Json.Stringify(doc.Get("version"))}");
        if (doc.Get("messages") is not List<object?> messages)
            throw new InvalidDataException($"FileConversationStore: corrupt conversation \"{id}\": messages is not an array");
        return messages;
    }

    /// <inheritdoc />
    public async Task SaveAsync(string id, List<object?> messages)
    {
        var path = PathFor(id);
        var gate = _locks.GetOrAdd(id, _ => new SemaphoreSlim(1, 1));
        await gate.WaitAsync().ConfigureAwait(false);
        try
        {
            EnsureRoot();
            var body = Json.Stringify(new Dictionary<string, object?>
            {
                ["version"] = Version,
                ["messages"] = messages,
            });
            var tmp = Path.Combine(_root, $".{id}.{Convert.ToHexString(RandomNumberGenerator.GetBytes(8)).ToLowerInvariant()}.tmp");
            try
            {
                var opts = new FileStreamOptions { Mode = FileMode.CreateNew, Access = FileAccess.Write, Share = FileShare.None };
                if (!OperatingSystem.IsWindows())
                    opts.UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite;
                await using (var fs = new FileStream(tmp, opts))
                {
                    var bytes = Encoding.UTF8.GetBytes(body);
                    await fs.WriteAsync(bytes).ConfigureAwait(false);
                    await fs.FlushAsync().ConfigureAwait(false);
                    if (_sync == FileStoreSync.Full) fs.Flush(flushToDisk: true);
                    else if (_sync == FileStoreSync.Fsync) PlainFsync(fs);
                }
                File.Move(tmp, path, overwrite: true);
            }
            catch
            {
                try { File.Delete(tmp); } catch { }
                throw;
            }
            if (_sync == FileStoreSync.Full) FsyncDir();
        }
        finally
        {
            gate.Release();
        }
    }

    /// <summary>
    /// Every stored conversation as <see cref="ConversationInfo"/>, newest first (file mtime),
    /// ties by id ascending. Bodies are not parsed; temp files and invalid names are skipped.
    /// </summary>
    public Task<List<ConversationInfo>> ListAsync()
    {
        var result = new List<ConversationInfo>();
        if (Directory.Exists(_root))
        {
            foreach (var file in Directory.EnumerateFiles(_root, "*.json"))
            {
                var id = Path.GetFileNameWithoutExtension(file);
                if (!IsValidId(id)) continue;
                DateTime mtime;
                try { mtime = File.GetLastWriteTimeUtc(file); } catch { continue; }
                result.Add(new ConversationInfo(id, new DateTimeOffset(mtime, TimeSpan.Zero)));
            }
        }
        result.Sort((a, b) =>
        {
            var c = b.UpdatedAt.CompareTo(a.UpdatedAt);
            return c != 0 ? c : string.CompareOrdinal(a.Id, b.Id);
        });
        return Task.FromResult(result);
    }

    /// <summary>Remove a conversation's file; succeeds when it is absent.</summary>
    public Task DeleteAsync(string id)
    {
        var path = PathFor(id);
        try { File.Delete(path); } catch (DirectoryNotFoundException) { }
        return Task.CompletedTask;
    }

    private void EnsureRoot()
    {
        if (Directory.Exists(_root)) return;
        if (OperatingSystem.IsWindows())
            Directory.CreateDirectory(_root);
        else
            Directory.CreateDirectory(_root, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
    }
}
