
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical JSON: keys sorted recursively (ASCII), compact separators, UTF-8, no trailing
 * newline. Hand-rolled because the port's Jackson emits keys sorted (ORDER_MAP_ENTRIES_BY_KEYS)
 * but NOT canonical numbers: {@code 0.0}, {@code 1.0E21}, {@code 1.6716E-5}.
 */
final class Canon {
    private Canon() {}

    static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        emit(v, sb);
        return sb.toString();
    }

    private static void emit(Object v, StringBuilder sb) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> string(s, sb);
            case Boolean b -> sb.append(b);
            case Number n -> sb.append(number(n));
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : new TreeMap<String, Object>(cast(m)).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    string((String) e.getKey(), sb);
                    sb.append(':');
                    emit(e.getValue(), sb);
                }
                sb.append('}');
            }
            case List<?> l -> {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) sb.append(',');
                    emit(l.get(i), sb);
                }
                sb.append(']');
            }
            default -> throw new IllegalArgumentException("not JSON: " + v.getClass());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) { return (Map<String, Object>) m; }

    /** ECMAScript-shortest number text: {@code 0}, {@code 1.21}, {@code 0.000016716}. */
    static String number(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
            return n.toString();
        }
        double d = n.doubleValue();
        if (!Double.isFinite(d)) throw new IllegalArgumentException("non-finite number");
        if (d == Math.rint(d) && Math.abs(d) < 1e21) {
            return BigDecimal.valueOf(d).setScale(0, java.math.RoundingMode.UNNECESSARY).toBigInteger().toString();
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
