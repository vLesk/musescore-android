package io.github.vlesk.stave.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal read-only content provider exposing exported files in cache/share so they can be
 * sent through the Android share sheet (a dependency-free stand-in for AndroidX FileProvider).
 */
public final class ShareProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.vlesk.stave.share";

    public static File shareDir(Context c) {
        File d = new File(c.getCacheDir(), "share");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static Uri uriFor(File f) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(f.getName()));
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException();
        File f = new File(shareDir(getContext()), name);
        if (!f.exists()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    public static String mimeFor(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".mid")) return "audio/midi";
        if (n.endsWith(".musicxml")) return "application/vnd.recordare.musicxml+xml";
        if (n.endsWith(".mxl")) return "application/vnd.recordare.musicxml";
        if (n.endsWith(".mscz")) return "application/x-musescore";
        if (n.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    @Override
    public String getType(Uri uri) {
        return mimeFor(uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment());
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        throw new UnsupportedOperationException();
    }
}
