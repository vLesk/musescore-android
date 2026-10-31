package io.github.vlesk.stave.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;

import io.github.vlesk.stave.audio.MidiWriter;
import io.github.vlesk.stave.audio.Timeline;
import io.github.vlesk.stave.engrave.DisplayList;
import io.github.vlesk.stave.engrave.Layout;
import io.github.vlesk.stave.io.Mscz;
import io.github.vlesk.stave.io.MusicXmlWriter;
import io.github.vlesk.stave.model.Score;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Export formats: MuseScore (.mscz), MusicXML, MIDI and paginated A4 PDF. */
public final class Exporter {
    private Exporter() {}

    public static final int MSCZ = 0, MUSICXML = 1, MIDI = 2, PDF = 3;
    public static final String[] EXT = {".mscz", ".musicxml", ".mid", ".pdf"};
    public static final String[] MIME = {"application/x-musescore", "application/vnd.recordare.musicxml+xml", "audio/midi", "application/pdf"};

    public static void write(Context c, Score s, int format, OutputStream out) throws IOException {
        switch (format) {
            case MSCZ:
                Mscz.write(s, out, thumbnail(c, s, 360));
                break;
            case MUSICXML:
                out.write(MusicXmlWriter.write(s).getBytes("UTF-8"));
                break;
            case MIDI:
                out.write(MidiWriter.write(s, Timeline.build(s), 1.0));
                break;
            case PDF:
                pdf(c, s, out);
                break;
            default:
                throw new IOException("unknown format");
        }
        out.flush();
    }

    public static String fileName(Score s, int format) {
        String base = s.title == null || s.title.trim().isEmpty() ? "score" : s.title.trim();
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (base.length() > 80) base = base.substring(0, 80);
        return base + EXT[format];
    }

    /** A4 pages at MuseScore's default staff size (1.75 mm). */
    private static void pdf(Context c, Score s, OutputStream out) throws IOException {
        final float ptPerMm = 72f / 25.4f;
        final float sp = 1.75f * ptPerMm;
        final int pw = 595, ph = 842;
        ScoreRenderer r = new ScoreRenderer(c);
        Layout.Options o = new Layout.Options();
        o.width = pw / sp;
        o.marginX = 15 * ptPerMm / sp;
        o.marginTop = 12 * ptPerMm / sp;
        o.pageHeight = ph / sp;
        o.marginBottom = 15 * ptPerMm / sp;
        DisplayList dl = Layout.layout(s, r, o);
        int pages = Math.max(1, Math.round(dl.height / o.pageHeight));
        PdfDocument doc = new PdfDocument();
        try {
            for (int i = 0; i < pages; i++) {
                PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, i + 1).create());
                Canvas cv = page.getCanvas();
                cv.translate(0, -i * o.pageHeight * sp);
                cv.clipRect(0, i * o.pageHeight * sp, pw, (i + 1) * o.pageHeight * sp);
                r.draw(cv, dl, sp, i * o.pageHeight, (i + 1) * o.pageHeight, null);
                doc.finishPage(page);
            }
            doc.writeTo(out);
        } finally {
            doc.close();
        }
    }

    /** PNG thumbnail of the first system(s) for the library and the .mscz container. */
    public static byte[] thumbnail(Context c, Score s, int widthPx) {
        try {
            ScoreRenderer r = new ScoreRenderer(c);
            Layout.Options o = new Layout.Options();
            o.width = 60;
            DisplayList dl = Layout.layout(s, r, o);
            float sp = widthPx / o.width;
            int h = (int) (widthPx * 0.62f);
            Bitmap bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bmp);
            cv.drawColor(0xFFFFFFFF);
            r.draw(cv, dl, sp, 0, h / sp, null);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bo);
            bmp.recycle();
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }
}
