package io.github.vlesk.stave;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import io.github.vlesk.stave.io.ScoreStore;
import io.github.vlesk.stave.model.Instruments;
import io.github.vlesk.stave.model.Part;
import io.github.vlesk.stave.model.Score;
import io.github.vlesk.stave.ui.EditorActivity;
import io.github.vlesk.stave.ui.HomeActivity;
import io.github.vlesk.stave.ui.NewScoreActivity;
import io.github.vlesk.stave.ui.PianoView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Launches every screen under Robolectric, drives the main interactions and saves screenshots. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class UiSmokeTest {
    static final String SHOTS = System.getProperty("shots", "/tmp/stave-shots");

    static void layout(Activity a, int w, int h) {
        View d = a.getWindow().getDecorView();
        d.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        d.layout(0, 0, w, h);
        ShadowLooper.idleMainLooper();
    }

    static void shot(Activity a, String name) throws Exception {
        View d = a.getWindow().getDecorView();
        layout(a, d.getWidth() > 0 ? d.getWidth() : 1233, d.getHeight() > 0 ? d.getHeight() : 2673);
        Bitmap b = Bitmap.createBitmap(d.getWidth(), d.getHeight(), Bitmap.Config.ARGB_8888);
        d.draw(new Canvas(b));
        new File(SHOTS).mkdirs();
        FileOutputStream out = new FileOutputStream(new File(SHOTS, name + ".png"));
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();
    }

    static <T extends View> List<T> find(View root, Class<T> cls) {
        List<T> out = new ArrayList<T>();
        collect(root, cls, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    static <T extends View> void collect(View v, Class<T> cls, List<T> out) {
        if (cls.isInstance(v)) out.add((T) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), cls, out);
        }
    }

    static View byDesc(View root, String desc) {
        for (View v : find(root, View.class)) {
            if (desc.contentEquals(String.valueOf(v.getContentDescription()))) return v;
        }
        return null;
    }

    String createScore(Activity ctx) throws Exception {
        ScoreStore store = new ScoreStore(ctx.getFilesDir());
        List<Part> parts = new ArrayList<Part>();
        parts.add(Instruments.byId("voice").create());
        parts.add(Instruments.byId("piano").create());
        Score s = Score.create("Smoke Test", "Robo", parts, 4, 4, -1, 96, 12);
        String id = store.newId();
        store.save(id, s, null);
        return id;
    }

    @Test
    public void homeAndNewScore() throws Exception {
        ActivityController<HomeActivity> hc = Robolectric.buildActivity(HomeActivity.class).setup();
        layout(hc.get(), 1233, 2673);
        shot(hc.get(), "01-home-empty");
        createScore(hc.get());
        hc.pause().resume();
        Thread.sleep(300);
        ShadowLooper.idleMainLooper();
        shot(hc.get(), "02-home-list");

        ActivityController<NewScoreActivity> nc = Robolectric.buildActivity(NewScoreActivity.class).setup();
        layout(nc.get(), 1233, 2673);
        shot(nc.get(), "03-new-score");
        View create = null;
        for (android.widget.TextView tv : find(nc.get().getWindow().getDecorView(), android.widget.TextView.class)) {
            if ("Create".contentEquals(tv.getText())) create = tv;
        }
        assertNotNull(create);
        create.performClick();
        Intent started = Shadows.shadowOf(nc.get()).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(EditorActivity.class.getName(), started.getComponent().getClassName());
    }

    @Test
    public void editorFlow() throws Exception {
        Activity tmp = Robolectric.buildActivity(HomeActivity.class).setup().get();
        String id = createScore(tmp);
        Intent i = new Intent(tmp, EditorActivity.class).putExtra(EditorActivity.EXTRA_ID, id);
        ActivityController<EditorActivity> ec = Robolectric.buildActivity(EditorActivity.class, i).setup();
        EditorActivity a = ec.get();
        layout(a, 1233, 2673);
        shot(a, "04-editor");
        View root = a.getWindow().getDecorView();
        // enter note input with the FAB and play some notes on the piano
        View fab = byDesc(root, "Note input");
        assertNotNull(fab);
        fab.performClick();
        layout(a, 1233, 2673);
        Method pianoKey = EditorActivity.class.getDeclaredMethod("pianoKey", int.class);
        pianoKey.setAccessible(true);
        int[] melody = {65, 67, 69, 70, 72, 74, 72, 70, 69, 67, 65, 64, 65};
        for (int p : melody) pianoKey.invoke(a, p);
        byDesc(root, "Eighth").performClick();
        for (int p : new int[]{72, 70, 69, 67}) pianoKey.invoke(a, p);
        byDesc(root, "Triplet").performClick();
        for (int p : new int[]{65, 67, 69}) pianoKey.invoke(a, p);
        byDesc(root, "Triplet").performClick();
        byDesc(root, "Quarter").performClick();
        byDesc(root, "Augmentation dot").performClick();
        pianoKey.invoke(a, 70);
        byDesc(root, "Augmentation dot").performClick();
        byDesc(root, "Sharp").performClick();
        byDesc(root, "Rest").performClick();
        byDesc(root, "Tie").performClick();
        byDesc(root, "Voice 2").performClick();
        byDesc(root, "Voice 1").performClick();
        byDesc(root, "Add to chord").performClick();
        pianoKey.invoke(a, 60);
        byDesc(root, "Add to chord").performClick();
        layout(a, 1233, 2673);
        shot(a, "05-note-input");
        // hardware keyboard shortcuts
        a.onKeyDown(KeyEvent.KEYCODE_C, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C));
        a.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
        a.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
        a.onKeyDown(KeyEvent.KEYCODE_N, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N));
        // palettes: open and apply every cell
        byDesc(root, "Palettes").performClick();
        layout(a, 1233, 2673);
        shot(a, "06-palette");
        View sheetRoot = a.getWindow().getDecorView();
        io.github.vlesk.stave.ui.PaletteView pv = find(sheetRoot, io.github.vlesk.stave.ui.PaletteView.class).get(0);
        int cats = 9;
        for (int c = 0; c < cats; c++) {
            pv.show(c);
            layout(a, 1233, 2673);
            if (c == 4) shot(a, "07-palette-articulations");
            // tap a few cells per category (skip ones that open dialogs requiring text input)
            List<View> cells = new ArrayList<View>();
            for (View v : find(pv, View.class)) if (v.isClickable() && v.getClass().getSimpleName().equals("CellView")) cells.add(v);
            for (int k = 0; k < Math.min(cells.size(), 4); k++) cells.get(k).performClick();
            ShadowLooper.idleMainLooper();
        }
        layout(a, 1233, 2673);
        shot(a, "08-after-palettes");
        // back closes palette / input / selection
        a.onBackPressed();
        a.onBackPressed();
        a.onBackPressed();
        layout(a, 1233, 2673);
        shot(a, "09-editor-final");
        // undo everything
        View undo = byDesc(root, "Undo");
        for (int k = 0; k < 200 && undo.isEnabled(); k++) undo.performClick();
        // rotation / configuration change rebuilds the toolbars
        android.content.res.Configuration cfg = new android.content.res.Configuration(a.getResources().getConfiguration());
        cfg.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        cfg.screenWidthDp = 891;
        cfg.screenHeightDp = 411;
        a.onConfigurationChanged(cfg);
        layout(a, 2673, 1233);
        shot(a, "10-landscape");
        ec.pause().stop().destroy();
    }

    @Test
    public void importMuseScoreFile() throws Exception {
        String path = System.getProperty("importFile");
        if (path == null) return;
        Activity tmp = Robolectric.buildActivity(HomeActivity.class).setup().get();
        Intent i = new Intent(Intent.ACTION_VIEW).setClass(tmp, EditorActivity.class).setData(android.net.Uri.fromFile(new File(path)));
        ActivityController<EditorActivity> ec = Robolectric.buildActivity(EditorActivity.class, i).setup();
        for (int k = 0; k < 50; k++) {
            Thread.sleep(100);
            ShadowLooper.idleMainLooper();
        }
        layout(ec.get(), 1233, 2673);
        shot(ec.get(), "11-imported");
        assertTrue(true);
    }
}
