package io.github.vlesk.stave.io;

import io.github.vlesk.stave.model.Score;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** The compressed MuseScore container (.mscz): a zip with META-INF/container.xml and the .mscx. */
public final class Mscz {
    private Mscz() {}

    public static void write(Score s, OutputStream out, byte[] thumbnailPng) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        String name = "score.mscx";
        put(zip, "META-INF/container.xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<container>\n  <rootfiles>\n"
                + "    <rootfile full-path=\"" + name + "\"/>\n  </rootfiles>\n</container>\n").getBytes("UTF-8"));
        put(zip, name, MscxWriter.write(s).getBytes("UTF-8"));
        if (thumbnailPng != null) put(zip, "Thumbnails/thumbnail.png", thumbnailPng);
        zip.finish();
        zip.flush();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    /** Extracts the main .mscx document from an .mscz archive. */
    public static byte[] extractMscx(byte[] mscz) throws IOException {
        Map<String, byte[]> files = new HashMap<String, byte[]>();
        ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(mscz));
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            String n = e.getName();
            if (n.endsWith(".mscx") || n.equals("META-INF/container.xml")) files.put(n, readAll(zin));
        }
        zin.close();
        byte[] container = files.get("META-INF/container.xml");
        if (container != null) {
            String c = new String(container, "UTF-8");
            int i = c.indexOf("full-path=\"");
            while (i >= 0) {
                int j = c.indexOf('"', i + 11);
                String path = c.substring(i + 11, j);
                if (path.endsWith(".mscx") && files.containsKey(path)) return files.get(path);
                i = c.indexOf("full-path=\"", j);
            }
        }
        // fall back to the first .mscx at the archive root
        for (Map.Entry<String, byte[]> en : files.entrySet()) {
            if (en.getKey().endsWith(".mscx") && !en.getKey().contains("/")) return en.getValue();
        }
        for (Map.Entry<String, byte[]> en : files.entrySet()) {
            if (en.getKey().endsWith(".mscx") && !en.getKey().startsWith("Excerpts")) return en.getValue();
        }
        throw new IOException("No score found inside the .mscz file");
    }

    /** Loads any supported file type, detected from its content. */
    public static MscxReader.Result load(byte[] data, String nameHint) throws Exception {
        if (data.length > 4 && data[0] == 'P' && data[1] == 'K') {
            // zip: .mscz or compressed MusicXML (.mxl)
            String lower = nameHint == null ? "" : nameHint.toLowerCase();
            if (lower.endsWith(".mxl")) return MusicXmlReader.readMxl(data);
            try {
                return MscxReader.read(extractMscx(data));
            } catch (IOException noMscx) {
                return MusicXmlReader.readMxl(data);
            }
        }
        String head = new String(data, 0, Math.min(data.length, 2048), "UTF-8");
        if (head.contains("<museScore")) return MscxReader.read(data);
        if (head.contains("score-partwise") || head.contains("score-timewise")) return MusicXmlReader.read(data);
        throw new IllegalArgumentException("Unsupported file. Open .mscz, .mscx, .musicxml, .xml or .mxl files.");
    }
}
