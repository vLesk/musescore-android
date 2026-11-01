package io.github.vlesk.stave;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader for the simulation input. */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String s) {
        return new Json(s).value();
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        ws();
        char c = s.charAt(i);
        if (c == '{') {
            i++;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = (String) value();
                ws();
                i++; // :
                m.put(k, value());
                ws();
                if (s.charAt(i++) == '}') return m;
            }
        }
        if (c == '[') {
            i++;
            List<Object> l = new ArrayList<Object>();
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (s.charAt(i++) == ']') return l;
            }
        }
        if (c == '"') {
            StringBuilder b = new StringBuilder();
            i++;
            while (s.charAt(i) != '"') {
                char ch = s.charAt(i++);
                if (ch == '\\') {
                    char e = s.charAt(i++);
                    if (e == 'u') { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    else if (e == 'n') b.append('\n');
                    else if (e == 't') b.append('\t');
                    else b.append(e);
                } else b.append(ch);
            }
            i++;
            return b.toString();
        }
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        return Double.parseDouble(s.substring(st, i));
    }
}
