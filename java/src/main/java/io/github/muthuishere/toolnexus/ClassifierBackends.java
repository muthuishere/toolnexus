package io.github.muthuishere.toolnexus;

import io.github.muthuishere.toolnexus.Classifier.ChoiceAnswer;
import io.github.muthuishere.toolnexus.Classifier.ChoiceQuestion;
import io.github.muthuishere.toolnexus.Classifier.ClassifierException;
import io.github.muthuishere.toolnexus.Classifier.Decision;
import io.github.muthuishere.toolnexus.Classifier.DecisionAnswer;
import io.github.muthuishere.toolnexus.Classifier.NoulAnswer;
import io.github.muthuishere.toolnexus.Classifier.Question;
import io.github.muthuishere.toolnexus.Classifier.RawAnswer;
import io.github.muthuishere.toolnexus.Classifier.ScoreAnswer;
import io.github.muthuishere.toolnexus.Classifier.ScoreQuestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The implementation half of SPEC.md §8B "Backends, images, cache and validation" (OpenSpec
 * change {@code add-classifier-backends}, ADR 0038). The public surface lives on
 * {@link Classifier}; this class holds the pure helpers it delegates to. Every default is a
 * PROPOSED default, owner to confirm (design.md PD-1..PD-15).
 */
final class ClassifierBackends {
    private ClassifierBackends() {}

    // ------------------------------------------------------------------ ${NAME} expansion

    private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** Expand {@code ${NAME}} only (never {@code $NAME}), at call time; unset ⇒ empty. */
    static String expandEnvRefs(String s, Function<String, String> env) {
        if (s == null || !s.contains("${")) return s;
        Matcher m = ENV_REF.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = env.apply(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ image lifting

    /** One lifted image: its reference and the System One asset. {@code format} is the MIME type
     * until {@link #checkImages} normalises it to {@code png}/{@code jpeg}. */
    static final class Lifted {
        final String ref;
        String format;
        final String data;

        Lifted(String ref, String format, String data) {
            this.ref = ref;
            this.format = format;
            this.data = data;
        }
    }

    /** The state to send plus the images, in collection order. */
    record LiftResult(Object state, List<Lifted> images) {}

    /**
     * The §8B lifting walk. A state with no {@link ContentPart} anywhere is returned VERBATIM (the
     * same object), so a text-only state's bytes do not change.
     */
    static LiftResult lift(Object state) {
        if (!containsPart(state)) return new LiftResult(state, List.of());
        Set<String> used = new HashSet<>();
        List<Lifted> images = new ArrayList<>();
        Object out = walk(state, null, used, images);
        return new LiftResult(out, images);
    }

    private static Object walk(Object v, String key, Set<String> used, List<Lifted> images) {
        if (v instanceof ContentPart p) {
            switch (p.type()) {
                case ContentPart.TEXT -> {
                    return p.text() == null ? "" : p.text();
                }
                case ContentPart.IMAGE -> {
                    if (p.data() == null || p.data().isEmpty()) {
                        throw new ClassifierException(
                                "classifier: state: an image part needs inline data (a URL image cannot be sent)");
                    }
                    String ref = key;
                    if (ref == null || ref.isEmpty() || used.contains(ref)) {
                        for (int n = 1; ; n++) {
                            String name = "image" + n;
                            if (!used.contains(name)) { ref = name; break; }
                        }
                    }
                    used.add(ref);
                    images.add(new Lifted(ref, p.mimeType(), p.data()));
                    return ref;
                }
                default -> throw new ClassifierException("classifier: state: " + p.type() + " parts are not supported");
            }
        }
        if (v instanceof Map<?, ?> m) {
            TreeMap<String, Object> sorted = new TreeMap<>(); // ASCII / code-point order
            for (Map.Entry<?, ?> e : m.entrySet()) sorted.put(String.valueOf(e.getKey()), e.getValue());
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                out.put(e.getKey(), walk(e.getValue(), e.getKey(), used, images));
            }
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object e : l) out.add(walk(e, null, used, images));
            return out;
        }
        return v;
    }

    private static boolean containsPart(Object v) {
        if (v instanceof ContentPart) return true;
        if (v instanceof Map<?, ?> m) {
            for (Object e : m.values()) if (containsPart(e)) return true;
        } else if (v instanceof List<?> l) {
            for (Object e : l) if (containsPart(e)) return true;
        }
        return false;
    }

    /** Format, per-image and per-request limits, in collection order. Normalises the format. */
    static void checkImages(List<Lifted> images) {
        long total = 0;
        for (Lifted im : images) {
            String format = switch (im.format == null ? "" : im.format) {
                case "image/png" -> "png";
                case "image/jpeg", "image/jpg" -> "jpeg";
                default -> null;
            };
            if (format == null) {
                throw new ClassifierException("classifier: image \"" + im.ref + "\": type \"" + im.format
                        + "\" is not supported (PNG or JPEG only)");
            }
            im.format = format;
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(im.data);
            } catch (IllegalArgumentException e) {
                throw new ClassifierException("classifier: image \"" + im.ref + "\": data is not base64");
            }
            if (raw.length > Classifier.IMAGE_MAX_BYTES) {
                throw new ClassifierException("classifier: image \"" + im.ref + "\": " + raw.length
                        + " bytes, over 16 MiB");
            }
            int[] wh = imageSize(format, raw);
            if (wh == null) {
                throw new ClassifierException("classifier: image \"" + im.ref + "\": not a readable "
                        + format.toUpperCase(Locale.ROOT));
            }
            int w = wh[0], h = wh[1];
            int lo = Classifier.IMAGE_MIN_SIDE, hi = Classifier.IMAGE_MAX_SIDE;
            if (w < lo || w > hi || h < lo || h > hi) {
                throw new ClassifierException("classifier: image \"" + im.ref + "\": " + w + "x" + h
                        + "; each side must be " + lo + ".." + hi + " px");
            }
            total += raw.length;
        }
        if (total > Classifier.REQUEST_IMAGE_BYTES) {
            throw new ClassifierException("classifier: image \"" + images.get(images.size() - 1).ref
                    + "\": request images total " + total + " bytes, over 32 MiB");
        }
    }

    /** Width/height from a PNG IHDR or the first JPEG SOF marker; {@code null} when unreadable. */
    static int[] imageSize(String format, byte[] b) {
        if ("png".equals(format)) {
            byte[] sig = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
            if (b.length < 24) return null;
            for (int i = 0; i < 8; i++) if (b[i] != sig[i]) return null;
            if (b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') return null;
            return new int[]{be32(b, 16), be32(b, 20)};
        }
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return null;
        int i = 2;
        while (i + 3 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) return null;
            int m = b[i + 1] & 0xFF;
            if (m == 0xFF) { // fill byte
                i++;
                continue;
            }
            if (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                if (i + 8 >= b.length) return null;
                return new int[]{be16(b, i + 7), be16(b, i + 5)};
            }
            i += 2 + be16(b, i + 2);
        }
        return null;
    }

    private static int be16(byte[] b, int i) { return (b[i] & 0xFF) << 8 | (b[i + 1] & 0xFF); }

    private static int be32(byte[] b, int i) {
        return (b[i] & 0xFF) << 24 | (b[i + 1] & 0xFF) << 16 | (b[i + 2] & 0xFF) << 8 | (b[i + 3] & 0xFF);
    }

    // ------------------------------------------------------------------ cloudflare

    /** The System One reply inside a Cloudflare envelope, as canonical JSON. Never echoes the
     * envelope's own errors (they can carry an account id). */
    static String unwrapCloudflare(String body) {
        Object env;
        try {
            env = Json.MAPPER.readValue(body, Object.class);
        } catch (Exception e) {
            throw new ClassifierException("classifier: cloudflare: envelope not successful");
        }
        if (!(env instanceof Map<?, ?> m) || !Boolean.TRUE.equals(m.get("success"))) {
            throw new ClassifierException("classifier: cloudflare: envelope not successful");
        }
        Object res = m.get("result");
        if (res instanceof Map<?, ?> inner && inner.get("state") instanceof String st) {
            if (!"Completed".equals(st)) {
                throw new ClassifierException("classifier: cloudflare: run state \"" + st + "\"");
            }
            res = inner.get("result");
        }
        if (!(res instanceof Map<?, ?>)) {
            throw new ClassifierException("classifier: cloudflare: result is not an object");
        }
        return Classifier.Canon.write(res);
    }

    // ------------------------------------------------------------------ cache keys

    static String sha256Hex(byte[] b) {
        try {
            StringBuilder sb = new StringBuilder(64);
            for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String modelKey(String endpoint, String model) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("endpoint", endpoint);
        m.put("kind", "model");
        m.put("model", model);
        return sha256Hex(Classifier.Canon.write(m).getBytes(StandardCharsets.UTF_8));
    }

    static String decisionKey(String endpoint, String served, Object state, Map<String, ?> assets,
                              Map<String, Object> questionsWire) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (assets != null && !assets.isEmpty()) m.put("assets", assets);
        m.put("endpoint", endpoint);
        m.put("kind", "decision");
        m.put("model", served);
        m.put("questions", questionsWire);
        m.put("state", state);
        return sha256Hex(Classifier.Canon.write(m).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ V1

    private static ClassifierException bad(String msg) {
        return new ClassifierException("classifier: invalid decision: " + msg);
    }

    private static boolean in01(double p) { return p >= 0 && p <= 1; } // NaN fails both

    /** V1, fail-closed, first failure wins in the §8B order; {@code null} = valid. */
    static ClassifierException validate(Map<String, Question> questions, Decision d) {
        List<String> keys = new ArrayList<>(new TreeMap<>(questions).keySet());
        Map<String, DecisionAnswer> answers = d.answers();
        for (String k : keys) {
            if (!answers.containsKey(k)) return bad("no answer \"" + k + "\"");
        }
        for (String k : new TreeMap<>(answers).keySet()) {
            if (!questions.containsKey(k)) return bad("unexpected answer \"" + k + "\"");
        }
        for (String k : keys) {
            Question q = questions.get(k);
            DecisionAnswer a = answers.get(k);
            if (!a.type().equals(q.type())) {
                return bad("answer \"" + k + "\" is a " + a.type() + " answer, not " + q.type());
            }
            switch (a) {
                case NoulAnswer n -> {
                    if (!in01(n.noul())) return bad("answer \"" + k + "\": noul " + n.noul() + " outside [0,1]");
                }
                case ChoiceAnswer c -> {
                    Map<String, String> crit = q instanceof ChoiceQuestion cq ? cq.criteria() : Map.of();
                    if (c.choice() == null || !crit.containsKey(c.choice())) {
                        return bad("answer \"" + k + "\": choice \"" + c.choice() + "\" was not offered");
                    }
                    ClassifierException e = dist(k, c.probabilities(), crit::containsKey);
                    if (e != null) return e;
                    if (!in01(c.confidence())) {
                        return bad("answer \"" + k + "\": confidence " + c.confidence() + " outside [0,1]");
                    }
                }
                case ScoreAnswer s -> {
                    int n = q instanceof ScoreQuestion sq ? sq.criteria().size() : 0;
                    if (!(s.score() >= 0 && s.score() <= n - 1)) {
                        return bad("answer \"" + k + "\": score " + s.score() + " outside [0," + (n - 1) + "]");
                    }
                    ClassifierException e = dist(k, s.probabilities(), o -> {
                        for (int i = 0; i < n; i++) if (o.equals(Integer.toString(i))) return true;
                        return false;
                    });
                    if (e != null) return e;
                    if (!in01(s.confidence())) {
                        return bad("answer \"" + k + "\": confidence " + s.confidence() + " outside [0,1]");
                    }
                }
                case RawAnswer r -> { }
            }
        }
        return null;
    }

    private static ClassifierException dist(String k, Map<String, Double> probs,
                                            java.util.function.Predicate<String> offered) {
        double sum = 0;
        for (Map.Entry<String, Double> e : new TreeMap<>(probs).entrySet()) {
            if (!offered.test(e.getKey())) {
                return bad("answer \"" + k + "\": probability for \"" + e.getKey() + "\", which was not offered");
            }
            double p = e.getValue() == null ? Double.NaN : e.getValue();
            if (!in01(p)) return bad("answer \"" + k + "\": probability " + p + " outside [0,1]");
            sum += p;
        }
        if (!probs.isEmpty() && !(Math.abs(sum - 1) <= 0.02)) {
            return bad("answer \"" + k + "\": probabilities sum to " + String.format(Locale.ROOT, "%.4f", sum));
        }
        return null;
    }
}
