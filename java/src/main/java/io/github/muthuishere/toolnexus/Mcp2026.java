package io.github.muthuishere.toolnexus;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Package-private helpers of the MCP 2026-07-28 wire on {@link McpHandler} (SPEC §7C.2): header
 * validation, skill URI containment, the MRTR requestState, MRTR ↔ §10 mapping, and
 * {@code loadMcpSkills}. Mirrors {@code golang/mcphandler2026.go}.
 */
final class Mcp2026 {
    private Mcp2026() {}

    static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_CLIENT_CAPS = "io.modelcontextprotocol/clientCapabilities";
    static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";
    static final int CODE_HEADER_MISMATCH = -32020;
    static final int CODE_UNSUPPORTED = -32022;

    private static String header(HttpExchange ex, String name) {
        String v = ex.getRequestHeaders().getFirst(name);
        return v == null ? "" : v;
    }

    private static McpHandler.Rpc26Error mismatch(String m) {
        return new McpHandler.Rpc26Error(400, CODE_HEADER_MISMATCH, "Header mismatch: " + m, null);
    }

    /** SPEC §7C.2 header validation, in order; null ⇒ valid. */
    static McpHandler.Rpc26Error validate(HttpExchange ex, String method, Map<String, Object> p, Map<String, Object> meta) {
        String hv = header(ex, "MCP-Protocol-Version");
        if (hv.isEmpty()) return mismatch("MCP-Protocol-Version header missing");
        String hm = header(ex, "Mcp-Method");
        if (!hm.equals(method)) {
            return mismatch("Mcp-Method header value '" + hm + "' does not match body value '" + method + "'");
        }
        if (method.equals("tools/call") || method.equals("prompts/get") || method.equals("resources/read")) {
            String key = method.equals("resources/read") ? "uri" : "name";
            String want = p != null && p.get(key) instanceof String s ? s : "";
            String[] got = decodeHeaderValue(header(ex, "Mcp-Name"));
            if (got[1] == null || !got[0].equals(want)) {
                return mismatch("Mcp-Name header value '" + got[0] + "' does not match body value '" + want + "'");
            }
        }
        String mv = meta != null && meta.get(META_VERSION) instanceof String s ? s : "";
        if (!mv.equals(hv)) {
            return mismatch("MCP-Protocol-Version header value '" + hv + "' does not match _meta value '" + mv + "'");
        }
        if (!mv.equals(McpHandler.PROTOCOL_2026)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("supported", List.of(McpHandler.PROTOCOL_2026));
            data.put("requested", mv);
            return new McpHandler.Rpc26Error(400, CODE_UNSUPPORTED, "Unsupported protocol version", data);
        }
        return null;
    }

    /**
     * Decodes the {@code =?base64?…?=} sentinel (SPEC §2.3). Returns {value, ok} where ok is non-null
     * when the value is usable (a non-empty plain value or a well-formed sentinel).
     */
    static String[] decodeHeaderValue(String v) {
        if (v.startsWith("=?base64?") && v.endsWith("?=") && v.length() >= 11) {
            try {
                return new String[]{new String(Base64.getDecoder().decode(v.substring(9, v.length() - 2)), StandardCharsets.UTF_8), "ok"};
            } catch (IllegalArgumentException e) {
                return new String[]{"", null};
            }
        }
        return new String[]{v, v.isEmpty() ? null : "ok"};
    }

    /** Compares by Unicode code point (not UTF-16 unit), matching Go's byte-wise string order. */
    static int compareCodePoints(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i), cb = b.codePointAt(j);
            if (ca != cb) return Integer.compare(ca, cb);
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    static boolean validUtf8(byte[] b) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    // ── skills ───────────────────────────────────────────────────────────

    static Map<String, Object> frontmatter(McpHandler.Skill s) {
        if (s.frontmatter() != null) return s.frontmatter();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        return m;
    }

    static Map<String, Object> skillEntry(McpHandler.Skill s) {
        List<McpHandler.SkillFile> files = new ArrayList<>(s.files() == null ? List.of() : s.files());
        files.sort((a, b) -> compareCodePoints(a.path(), b.path()));
        List<Map<String, Object>> res = new ArrayList<>();
        for (McpHandler.SkillFile f : files) {
            byte[] b = (f.text() == null ? "" : f.text()).getBytes(StandardCharsets.UTF_8);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uri", "skill://" + s.name() + "/" + f.path());
            m.put("digest", "sha256:" + HexFormat.of().formatHex(sha256(b)));
            m.put("size", b.length);
            res.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uri", "skill://" + s.name() + "/SKILL.md");
        out.put("frontmatter", frontmatter(s));
        out.put("resources", res);
        return out;
    }

    static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Validates {@code skill://<name>/<path>} (SPEC §2.7 containment). Returns {name, path};
     * throws IllegalArgumentException with the pinned reason.
     */
    static String[] parseSkillUri(String uri) {
        if (!uri.startsWith("skill://")) throw new IllegalArgumentException("not a skill:// URI");
        String dec = percentDecode(uri.substring("skill://".length()));
        if (dec.indexOf('%') >= 0 || dec.indexOf('\\') >= 0 || dec.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("skill URI has encoded, backslash or NUL characters");
        }
        String[] segs = dec.split("/", -1);
        if (segs.length < 2) throw new IllegalArgumentException("skill URI needs skill://<name>/<path>");
        for (String s : segs) {
            if (s.isEmpty() || s.equals(".") || s.equals("..") || s.contains(":")) {
                throw new IllegalArgumentException("skill URI path escapes the skill (empty, '.', '..' or ':' segment)");
            }
        }
        return new String[]{segs[0], String.join("/", java.util.Arrays.copyOfRange(segs, 1, segs.length))};
    }

    /** Go's url.PathUnescape: %XX only ('+' is literal); a malformed escape fails. */
    private static String percentDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < in.length; i++) {
            if (in[i] == '%') {
                if (i + 2 >= in.length || Character.digit(in[i + 1], 16) < 0 || Character.digit(in[i + 2], 16) < 0) {
                    throw new IllegalArgumentException("bad percent-encoding");
                }
                out.write(Character.digit(in[i + 1], 16) * 16 + Character.digit(in[i + 2], 16));
                i += 2;
            } else {
                out.write(in[i]);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    static List<McpHandler.Skill> loadSkills(Path dir) throws IOException {
        List<McpHandler.Skill> out = new ArrayList<>();
        List<Path> manifests;
        try (Stream<Path> st = Files.walk(dir)) {
            manifests = st.filter(f -> f.getFileName() != null && f.getFileName().toString().equals("SKILL.md")
                    && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        }
        for (Path manifest : manifests) {
            Map<String, Object> parsed = SkillSource.parseFrontmatter(Files.readString(manifest));
            if (Boolean.TRUE.equals(parsed.get("malformed"))) continue;
            @SuppressWarnings("unchecked")
            Map<String, String> data = (Map<String, String>) parsed.get("data");
            String name = data == null ? null : data.get("name");
            if (name == null || name.isEmpty()) continue;
            String desc = data.get("description");
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("name", name);
            fm.put("description", desc == null ? "" : desc);
            Path root = manifest.getParent();
            List<McpHandler.SkillFile> files = new ArrayList<>();
            List<Path> all;
            try (Stream<Path> st = Files.walk(root)) {
                all = st.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)).toList();
            }
            for (Path f : all) {
                byte[] b = Files.readAllBytes(f);
                if (!validUtf8(b)) continue; // binary files are skipped: the wire is text
                String rel = root.relativize(f).toString().replace(java.io.File.separatorChar, '/');
                files.add(new McpHandler.SkillFile(rel, new String(b, StandardCharsets.UTF_8)));
            }
            files.sort((a, b) -> compareCodePoints(a.path(), b.path()));
            out.add(new McpHandler.Skill(name, fm, List.copyOf(files)));
        }
        out.sort((a, b) -> compareCodePoints(a.name(), b.name()));
        return out;
    }

    // ── MRTR ─────────────────────────────────────────────────────────────

    /** The opaque requestState: which tool the round belongs to and whether a confirm was accepted. */
    record State(String tool, boolean confirmed) {}

    static String encodeState(String tool, boolean confirmed) {
        String json = "{\"tool\":" + Json.stringify(tool) + (confirmed ? ",\"confirmed\":true" : "") + "}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    static State decodeState(Object v) {
        if (!(v instanceof String s)) return null;
        try {
            byte[] b = Base64.getUrlDecoder().decode(s);
            Map<String, Object> m = Json.toMap(new String(b, StandardCharsets.UTF_8));
            Object tool = m.get("tool"), confirmed = m.get("confirmed");
            if ((tool != null && !(tool instanceof String)) || (confirmed != null && !(confirmed instanceof Boolean))) return null;
            return new State(tool == null ? "" : (String) tool, Boolean.TRUE.equals(confirmed));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One MRTR input response as a §10 Answer; null when not an object. */
    @SuppressWarnings("unchecked")
    static Answer inputResponse(String key, Object v) {
        if (!(v instanceof Map<?, ?> m)) return null;
        String action = m.get("action") instanceof String a ? a : "";
        boolean ok = "accept".equals(action);
        Map<String, Object> data = m.get("content") instanceof Map<?, ?> c ? (Map<String, Object>) c : null;
        String reason = ok ? null : "decline".equals(action) ? "declined" : "cancelled";
        return new Answer(key, ok, data, reason);
    }

    static boolean canElicit(Map<String, Object> meta) {
        return meta != null && meta.get(META_CLIENT_CAPS) instanceof Map<?, ?> caps && caps.containsKey("elicitation");
    }

    /** A §10 Request as an MRTR input request: authorization+url ⇒ URL mode, else form. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> inputRequest(Request r) {
        Map<String, Object> params = new LinkedHashMap<>();
        if ("authorization".equals(r.kind()) && r.url() != null && !r.url().isEmpty()) {
            params.put("mode", "url");
            params.put("message", r.prompt() == null ? "" : r.prompt());
            params.put("url", r.url());
            params.put("elicitationId", r.id());
        } else {
            Object schema = r.data() == null ? null : r.data().get("schema");
            Map<String, Object> s;
            if (schema instanceof Map<?, ?> sm) {
                s = (Map<String, Object>) sm;
            } else {
                s = new LinkedHashMap<>();
                s.put("type", "object");
                s.put("properties", new LinkedHashMap<>());
            }
            params.put("mode", "form");
            params.put("message", r.prompt() == null ? "" : r.prompt());
            params.put("requestedSchema", s);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("method", "elicitation/create");
        out.put("params", params);
        return out;
    }
}
