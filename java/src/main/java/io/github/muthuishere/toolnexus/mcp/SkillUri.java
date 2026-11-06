package io.github.muthuishere.toolnexus.mcp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Skill URI safety (python-sdk #3685): a skill:// URI is percent-decoded ONCE, then rejected if it
 * still contains '%' (double encoding), a backslash, NUL, an absolute/empty path, or a "."/".."/":"
 * segment. Both the server (before lookup) and the client (before writing files) use it.
 */
record SkillUri(String name, String rel) {

    static SkillUri parse(String uri) {
        if (uri == null || !uri.startsWith("skill://")) throw new IllegalArgumentException("not a skill:// URI");
        String dec = pathUnescape(uri.substring("skill://".length()));
        if (dec.contains("%") || dec.contains("\\") || dec.contains("\0")) // '%' left after one decode = double encoding
            throw new IllegalArgumentException("skill URI has encoded, backslash or NUL characters");
        String[] segs = dec.split("/", -1);
        if (segs.length < 2) throw new IllegalArgumentException("skill URI needs skill://<name>/<path>");
        for (String s : segs) {
            if (s.isEmpty() || s.equals(".") || s.equals("..") || s.contains(":"))
                throw new IllegalArgumentException("skill URI path escapes the skill (empty, '.', '..' or ':' segment)");
        }
        return new SkillUri(segs[0], dec.substring(segs[0].length() + 1));
    }

    /** Go's url.PathUnescape: %XX only ('+' stays '+'); a malformed escape is an error. */
    private static String pathUnescape(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length())
                    throw new IllegalArgumentException("bad percent-encoding");
                int hi = Character.digit(s.charAt(i + 1), 16), lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad percent-encoding");
                out.write(hi * 16 + lo);
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                if (Character.isHighSurrogate(c) && i + 1 < s.length()) b = s.substring(i, ++i + 1).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
