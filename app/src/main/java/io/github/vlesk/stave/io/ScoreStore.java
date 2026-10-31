package io.github.vlesk.stave.io;

import io.github.vlesk.stave.model.Score;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The on-device score library: every score is an .mscz file in the app's private storage,
 * with a small sidecar (.meta) for fast listing and a PNG thumbnail.
 */
public final class ScoreStore {
    public static final class Entry {
        public String id;
        public String title;
        public String composer;
        public String instruments;
        public long modified;
        public File file, thumb;
    }

    private final File dir;

    public ScoreStore(File baseDir) {
        dir = new File(baseDir, "scores");
        if (!dir.exists()) dir.mkdirs();
    }

    public File fileFor(String id) {
        return new File(dir, id + ".mscz");
    }

    public File thumbFor(String id) {
        return new File(dir, id + ".png");
    }

    private File metaFor(String id) {
        return new File(dir, id + ".meta");
    }

    public String newId() {
        return "score-" + System.currentTimeMillis();
    }

    public List<Entry> list() {
        File[] fs = dir.listFiles();
        List<Entry> out = new ArrayList<Entry>();
        if (fs == null) return out;
        for (File f : fs) {
            if (!f.getName().endsWith(".mscz")) continue;
            String id = f.getName().substring(0, f.getName().length() - 5);
            Entry e = new Entry();
            e.id = id;
            e.file = f;
            e.thumb = thumbFor(id);
            e.modified = f.lastModified();
            e.title = id;
            e.composer = "";
            e.instruments = "";
            try {
                String meta = new String(Mscz.readAll(new FileInputStream(metaFor(id))), "UTF-8");
                String[] parts = meta.split("\n", -1);
                if (parts.length > 0) e.title = parts[0];
                if (parts.length > 1) e.composer = parts[1];
                if (parts.length > 2) e.instruments = parts[2];
            } catch (IOException ignored) {
                try {
                    Score s = load(id);
                    e.title = s.title;
                    e.composer = s.composer;
                } catch (Exception ignored2) {
                }
            }
            out.add(e);
        }
        Entry[] arr = out.toArray(new Entry[0]);
        Arrays.sort(arr, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.modified == b.modified ? 0 : (a.modified > b.modified ? -1 : 1);
            }
        });
        return new ArrayList<Entry>(Arrays.asList(arr));
    }

    public Score load(String id) throws Exception {
        byte[] data = Mscz.readAll(new FileInputStream(fileFor(id)));
        return Mscz.load(data, id + ".mscz").score;
    }

    public synchronized void save(String id, Score s, byte[] thumbPng) throws IOException {
        File tmp = new File(dir, id + ".mscz.tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            Mscz.write(s, out, thumbPng);
            out.getFD().sync();
        } finally {
            out.close();
        }
        File target = fileFor(id);
        if (!tmp.renameTo(target)) throw new IOException("Could not save score");
        StringBuilder inst = new StringBuilder();
        for (int i = 0; i < s.parts.size(); i++) {
            if (i > 0) inst.append(", ");
            inst.append(s.parts.get(i).name);
        }
        FileOutputStream mo = new FileOutputStream(metaFor(id));
        mo.write((oneLine(s.title) + "\n" + oneLine(s.composer) + "\n" + oneLine(inst.toString())).getBytes("UTF-8"));
        mo.close();
        if (thumbPng != null) {
            FileOutputStream to = new FileOutputStream(thumbFor(id));
            to.write(thumbPng);
            to.close();
        }
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ');
    }

    public void delete(String id) {
        fileFor(id).delete();
        thumbFor(id).delete();
        metaFor(id).delete();
    }

    public String duplicate(String id, String newTitle) throws Exception {
        Score s = load(id);
        s.title = newTitle;
        String nid = newId();
        byte[] thumb = null;
        File t = thumbFor(id);
        if (t.exists()) thumb = Mscz.readAll(new FileInputStream(t));
        save(nid, s, thumb);
        return nid;
    }
}
