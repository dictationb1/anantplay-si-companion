package lk.techworld.anantplay.sicomp;

/* A minimal JSON parser for the bridge's compact payloads — plain Java, zero Android
 * dependencies, so the SAME class runs inside the app and inside the verification lab
 * (lab/verify_companion_sync.java) on a bare JDK.
 *
 * Supports everything the worker emits: objects, arrays, strings (with quote, backslash,
 * slash, b/f/n/r/t and backslash-u-XXXX escapes), numbers, true/false/null. Not a
 * general-purpose library — the worker's shapes are known — but defensive against
 * malformed input (throws IllegalArgumentException with a position, never returns
 * half-parsed data). */
public final class MiniJson {

    private final String src;
    private int at;

    private MiniJson(String src) {
        this.src = src == null ? "" : src;
    }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.at != p.src.length()) throw p.err("trailing characters");
        return v;
    }

    // ---- typed accessors (null/absent tolerant) ----

    public static String str(Object o, String key) {
        Object v = objGet(o, key);
        return v instanceof String ? (String) v : null;
    }

    public static Number num(Object o, String key) {
        Object v = objGet(o, key);
        return v instanceof Number ? (Number) v : null;
    }

    public static long lng(Object o, String key, long dflt) {
        Number n = num(o, key);
        return n == null ? dflt : n.longValue();
    }

    public static Object objGet(Object o, String key) {
        if (!(o instanceof java.util.Map)) return null;
        Object v = ((java.util.Map<?, ?>) o).get(key);
        return v;
    }

    public static java.util.List<Object> arr(Object o) {
        if (o instanceof java.util.List) return (java.util.List<Object>) o;
        return null;
    }

    // ---- parser ----

    private Object value() {
        if (at >= src.length()) throw err("unexpected end");
        char c = src.charAt(at);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("unexpected '" + c + "'");
        }
    }

    private java.util.Map<String, Object> object() {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        at++; // {
        ws();
        if (peek() == '}') { at++; return m; }
        for (;;) {
            ws();
            if (peek() != '"') throw err("expected object key");
            String key = string();
            ws();
            if (peek() != ':') throw err("expected ':'");
            at++;
            ws();
            m.put(key, value());
            ws();
            char c = peek();
            if (c == ',') { at++; continue; }
            if (c == '}') { at++; return m; }
            throw err("expected ',' or '}'");
        }
    }

    private java.util.List<Object> array() {
        java.util.ArrayList<Object> l = new java.util.ArrayList<>();
        at++; // [
        ws();
        if (peek() == ']') { at++; return l; }
        for (;;) {
            ws();
            l.add(value());
            ws();
            char c = peek();
            if (c == ',') { at++; continue; }
            if (c == ']') { at++; return l; }
            throw err("expected ',' or ']'");
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        at++; // "
        for (;;) {
            if (at >= src.length()) throw err("unterminated string");
            char c = src.charAt(at++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (at >= src.length()) throw err("bad escape");
                char e = src.charAt(at++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (at + 4 > src.length()) throw err("bad \\u escape");
                        try {
                            sb.append((char) Integer.parseInt(src.substring(at, at + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw err("bad \\u escape");
                        }
                        at += 4;
                        break;
                    default: throw err("bad escape '\\" + e + "'");
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Number number() {
        int start = at;
        if (peek() == '-') at++;
        while (at < src.length() && Character.digit(src.charAt(at), 10) >= 0) at++;
        boolean dbl = false;
        if (at < src.length() && src.charAt(at) == '.') {
            dbl = true;
            at++;
            while (at < src.length() && Character.digit(src.charAt(at), 10) >= 0) at++;
        }
        if (at < src.length() && (src.charAt(at) == 'e' || src.charAt(at) == 'E')) {
            dbl = true;
            at++;
            if (at < src.length() && (src.charAt(at) == '+' || src.charAt(at) == '-')) at++;
            while (at < src.length() && Character.digit(src.charAt(at), 10) >= 0) at++;
        }
        String t = src.substring(start, at);
        if (t.isEmpty() || t.equals("-")) throw err("bad number");
        try {
            return dbl ? (Number) Double.parseDouble(t) : (Number) Long.parseLong(t);
        } catch (NumberFormatException ex) {
            throw err("bad number '" + t + "'");
        }
    }

    private void expect(String word) {
        if (!src.startsWith(word, at)) throw err("expected '" + word + "'");
        at += word.length();
    }

    private char peek() {
        if (at >= src.length()) throw err("unexpected end");
        return src.charAt(at);
    }

    private void ws() {
        while (at < src.length()) {
            char c = src.charAt(at);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') at++;
            else break;
        }
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("JSON error at " + at + ": " + msg);
    }
}
