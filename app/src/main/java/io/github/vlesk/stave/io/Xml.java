package io.github.vlesk.stave.io;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

/** Small XML writing/reading helpers shared by the file format modules. */
final class Xml {
    private final StringBuilder sb = new StringBuilder();
    private final List<String> stack = new ArrayList<String>();

    Xml() {
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    }

    private void indent() {
        for (int i = 0; i < stack.size(); i++) sb.append("  ");
    }

    Xml raw(String s) {
        sb.append(s);
        return this;
    }

    /** Opens a tag; attrs are name/value pairs. */
    Xml open(String tag, String... attrs) {
        indent();
        sb.append('<').append(tag);
        appendAttrs(attrs);
        sb.append(">\n");
        stack.add(tag);
        return this;
    }

    Xml close() {
        String tag = stack.remove(stack.size() - 1);
        indent();
        sb.append("</").append(tag).append(">\n");
        return this;
    }

    Xml tag(String tag, String value, String... attrs) {
        indent();
        sb.append('<').append(tag);
        appendAttrs(attrs);
        sb.append('>').append(esc(value)).append("</").append(tag).append(">\n");
        return this;
    }

    Xml tag(String tag, int value) {
        return tag(tag, String.valueOf(value));
    }

    /** Writes an element whose content is already-escaped XML. */
    Xml tagRaw(String tag, String xml) {
        indent();
        sb.append('<').append(tag).append('>').append(xml).append("</").append(tag).append(">\n");
        return this;
    }

    Xml empty(String tag, String... attrs) {
        indent();
        sb.append('<').append(tag);
        appendAttrs(attrs);
        sb.append("/>\n");
        return this;
    }

    private void appendAttrs(String[] attrs) {
        for (int i = 0; i + 1 < attrs.length; i += 2) {
            sb.append(' ').append(attrs[i]).append("=\"").append(esc(attrs[i + 1])).append('"');
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder o = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<':
                    o.append("&lt;");
                    break;
                case '>':
                    o.append("&gt;");
                    break;
                case '&':
                    o.append("&amp;");
                    break;
                case '"':
                    o.append("&quot;");
                    break;
                default:
                    o.append(c);
            }
        }
        return o.toString();
    }

    @Override
    public String toString() {
        return sb.toString();
    }

    // ------------------------------------------------------------------ DOM reading helpers

    static List<Element> children(Element e) {
        List<Element> out = new ArrayList<Element>();
        if (e == null) return out;
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) out.add((Element) n);
        }
        return out;
    }

    static List<Element> children(Element e, String name) {
        List<Element> out = new ArrayList<Element>();
        for (Element c : children(e)) if (c.getTagName().equals(name)) out.add(c);
        return out;
    }

    static Element child(Element e, String name) {
        if (e == null) return null;
        for (Element c : children(e)) if (c.getTagName().equals(name)) return c;
        return null;
    }

    static String text(Element e, String name) {
        Element c = child(e, name);
        return c == null ? null : c.getTextContent().trim();
    }

    static int intText(Element e, String name, int def) {
        String t = text(e, name);
        if (t == null || t.isEmpty()) return def;
        try {
            return (int) Math.round(Double.parseDouble(t));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    static double doubleText(Element e, String name, double def) {
        String t = text(e, name);
        if (t == null || t.isEmpty()) return def;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** Plain text of a MuseScore rich-text element, dropping formatting tags and symbols. */
    static String plainText(Element e) {
        if (e == null) return null;
        StringBuilder sb = new StringBuilder();
        collectText(e, sb);
        return sb.toString().trim();
    }

    private static void collectText(Node n, StringBuilder sb) {
        NodeList nl = n.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node c = nl.item(i);
            if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(c.getNodeValue());
            } else if (c.getNodeType() == Node.ELEMENT_NODE) {
                String tag = ((Element) c).getTagName();
                if (tag.equals("sym")) {
                    String sym = c.getTextContent();
                    if (sym.startsWith("metNoteQuarter")) sb.append('♩');
                    else if (sym.startsWith("metNoteHalf")) sb.append("𝅗𝅥");
                    else if (sym.startsWith("metNote8th")) sb.append('♪');
                } else {
                    collectText(c, sb);
                }
            }
        }
    }

    /** Parses "a/b" into ticks (whole note = 1920). */
    static int fractionTicks(String f) {
        if (f == null) return 0;
        f = f.trim();
        int slash = f.indexOf('/');
        try {
            if (slash < 0) return Integer.parseInt(f) * 1920;
            long a = Long.parseLong(f.substring(0, slash).trim());
            long b = Long.parseLong(f.substring(slash + 1).trim());
            if (b == 0) return 0;
            return (int) (a * 1920 / b);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String ticksFraction(int ticks) {
        int a = ticks, b = 1920;
        int g = gcd(Math.abs(a), b);
        if (g == 0) return "0/1";
        return (a / g) + "/" + (b / g);
    }

    static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }
}
