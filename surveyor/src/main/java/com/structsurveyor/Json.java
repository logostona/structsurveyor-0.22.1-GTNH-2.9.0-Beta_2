package com.structsurveyor;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Map;

/**
 * Minimal JSON writer.
 *
 * Deliberately dependency-free: 1.7.10 does ship Gson, but pinning ourselves to
 * whatever version happens to be on the classpath in a 245-mod pack is a worse
 * trade than twenty lines of serialisation.
 */
public final class Json {

    private Json() {}

    public static void writeFile(File file, Object value) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }
        Writer w = new OutputStreamWriter(new java.io.FileOutputStream(file), Charset.forName("UTF-8"));
        try {
            write(w, value, 0);
            w.write('\n');
        } finally {
            w.close();
        }
    }

    private static void write(Writer w, Object v, int indent) throws IOException {
        if (v == null) {
            w.write("null");
        } else if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) { w.write("{}"); return; }
            w.write("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(w, indent + 1);
                writeString(w, String.valueOf(e.getKey()));
                w.write(": ");
                write(w, e.getValue(), indent + 1);
                if (++i < m.size()) w.write(',');
                w.write('\n');
            }
            pad(w, indent);
            w.write('}');
        } else if (v instanceof Collection) {
            Collection<?> c = (Collection<?>) v;
            if (c.isEmpty()) { w.write("[]"); return; }
            w.write("[\n");
            int i = 0;
            for (Object o : c) {
                pad(w, indent + 1);
                write(w, o, indent + 1);
                if (++i < c.size()) w.write(',');
                w.write('\n');
            }
            pad(w, indent);
            w.write(']');
        } else if (v instanceof Number || v instanceof Boolean) {
            w.write(v.toString());
        } else {
            writeString(w, v.toString());
        }
    }

    private static void pad(Writer w, int n) throws IOException {
        for (int i = 0; i < n; i++) w.write("  ");
    }

    private static void writeString(Writer w, String s) throws IOException {
        w.write('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  w.write("\\\""); break;
                case '\\': w.write("\\\\"); break;
                case '\n': w.write("\\n");  break;
                case '\r': w.write("\\r");  break;
                case '\t': w.write("\\t");  break;
                default:
                    if (c < 0x20) w.write(String.format("\\u%04x", (int) c));
                    else w.write(c);
            }
        }
        w.write('"');
    }
}
