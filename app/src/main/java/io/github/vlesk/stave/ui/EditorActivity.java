package io.github.vlesk.stave.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import io.github.vlesk.stave.R;
import io.github.vlesk.stave.audio.Player;
import io.github.vlesk.stave.audio.Synth;
import io.github.vlesk.stave.edit.EditorState;
import io.github.vlesk.stave.engrave.Sym;
import io.github.vlesk.stave.io.MscxReader;
import io.github.vlesk.stave.io.Mscz;
import io.github.vlesk.stave.io.ScoreStore;
import io.github.vlesk.stave.model.Articulation;
import io.github.vlesk.stave.model.Chord;
import io.github.vlesk.stave.model.ChordRest;
import io.github.vlesk.stave.model.Clef;
import io.github.vlesk.stave.model.Duration;
import io.github.vlesk.stave.model.Dynamics;
import io.github.vlesk.stave.model.Instruments;
import io.github.vlesk.stave.model.Measure;
import io.github.vlesk.stave.model.Note;
import io.github.vlesk.stave.model.Part;
import io.github.vlesk.stave.model.Pitch;
import io.github.vlesk.stave.model.Pos;
import io.github.vlesk.stave.model.Score;
import io.github.vlesk.stave.model.ScoreEdit;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The score editor: score canvas, note-input toolbar, piano keyboard, palettes and playback. */
public final class EditorActivity extends Activity implements ScoreView.Listener {
    public static final String EXTRA_ID = "score_id";
    private static final int REQ_EXPORT = 10;

    private Theme t;
    private ScoreStore store;
    private String scoreId;
    private EditorState st;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Synth synth;
    private Player player;
    private int savedRevision = 0;
    private int pendingExportFormat = -1;

    // views
    private FrameLayout root;
    private ScoreView view;
    private PianoView piano;
    private View pianoBox;
    private TextView titleView, statusView;
    private ImageButton undoBtn, redoBtn, playBtn, fab, chordBtn, kbdBtn;
    private final TextView[] durBtns = new TextView[6];
    private static final int[] DUR_TYPES = {Duration.N32, Duration.N16, Duration.EIGHTH, Duration.QUARTER, Duration.HALF, Duration.WHOLE};
    private TextView dotBtn, tripletBtn;
    private final TextView[] voiceBtns = new TextView[4];
    private FrameLayout sheetHost;
    private PaletteView palette;
    private boolean pianoVisible = true;
    private Object backCallback;
    private boolean backRegistered;

    private final Runnable autosave = new Runnable() {
        @Override
        public void run() {
            save(false);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        t = new Theme(this);
        store = new ScoreStore(getFilesDir());
        synth = new Synth();
        player = new Player(this);
        buildUi();
        Intent in = getIntent();
        String id = in.getStringExtra(EXTRA_ID);
        if (b != null && b.getString(EXTRA_ID) != null) id = b.getString(EXTRA_ID);
        if (id != null) {
            openId(id);
        } else {
            Uri uri = in.getData();
            if (uri == null && Intent.ACTION_SEND.equals(in.getAction())) uri = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) importUri(uri);
            else finish();
        }
    }

    private void openId(String id) {
        try {
            Score s = store.load(id);
            scoreId = id;
            bindScore(s);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.import_failed, String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void importUri(final Uri uri) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream ins = getContentResolver().openInputStream(uri);
                    byte[] data = Mscz.readAll(ins);
                    ins.close();
                    String name = uri.getLastPathSegment();
                    final MscxReader.Result r = Mscz.load(data, name);
                    final String id = store.newId();
                    store.save(id, r.score, Exporter.thumbnail(EditorActivity.this, r.score, 360));
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            scoreId = id;
                            bindScore(r.score);
                            showWarnings(r.warnings);
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(EditorActivity.this, getString(R.string.import_failed, String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show();
                            finish();
                        }
                    });
                }
            }
        });
    }

    private void showWarnings(List<String> w) {
        if (w == null || w.isEmpty()) return;
        List<String> uniq = new ArrayList<String>();
        for (String s : w) if (!uniq.contains(s)) uniq.add(s);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, uniq.size()); i++) sb.append("• ").append(uniq.get(i)).append('\n');
        if (uniq.size() > 8) sb.append("…");
        new AlertDialog.Builder(this).setTitle(R.string.import_warnings).setMessage(sb.toString().trim())
                .setPositiveButton(R.string.ok, null).show();
    }

    private void bindScore(Score s) {
        st = new EditorState(s);
        savedRevision = st.revision;
        view.bind(st, this);
        piano.centerOn(Clef.middlePitch(s.clefAt(0, 0)) + 2);
        refresh();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (scoreId != null) out.putString(EXTRA_ID, scoreId);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player.isPlaying()) player.stop();
        handler.removeCallbacks(autosave);
        save(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        synth.release();
        io.shutdown();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        // rebuild the toolbars for the new width (one or two rows) and re-flow the score
        boolean input = st != null && st.inputMode;
        if (paletteOpen()) closePalette();
        buildUi();
        if (st != null) {
            view.bind(st, this);
            if (input) setInputMode(true);
            refresh();
        }
    }

    // ------------------------------------------------------------------ UI construction

    private boolean wide() {
        return getResources().getConfiguration().screenWidthDp >= 720;
    }

    private void buildUi() {
        Configuration cfg = getResources().getConfiguration();
        boolean landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean tablet = cfg.smallestScreenWidthDp >= 600;
        root = new FrameLayout(this);
        root.setBackgroundColor(t.surface);
        LinearLayout col = Ui.column(this);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // top app bar
        LinearLayout top = Ui.row(this);
        top.setPadding(t.dp(4), 0, t.dp(4), 0);
        top.setBackgroundColor(t.surface);
        top.setMinimumHeight(t.dp(landscape && !tablet ? 52 : 64));
        top.addView(Ui.icon(this, t, R.drawable.ic_arrow_back, getString(R.string.navigate_up), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }));
        LinearLayout titles = Ui.column(this);
        titles.setPadding(t.dp(4), 0, t.dp(4), 0);
        titleView = Ui.title(this, t, "");
        titleView.setTextSize(20);
        statusView = Ui.text(this, "", 12, t.onSurfaceVariant, false);
        statusView.setSingleLine(true);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(titleView);
        titles.addView(statusView);
        titles.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                propertiesDialog();
            }
        });
        top.addView(titles, Ui.lin(0, -2, 1f));
        undoBtn = Ui.icon(this, t, R.drawable.ic_undo, getString(R.string.undo), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                st.undo();
                refresh();
            }
        });
        redoBtn = Ui.icon(this, t, R.drawable.ic_redo, getString(R.string.redo), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                st.redo();
                refresh();
            }
        });
        playBtn = Ui.icon(this, t, R.drawable.ic_play_arrow, getString(R.string.play), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        final ImageButton more = Ui.icon(this, t, R.drawable.ic_more_vert, getString(R.string.more_options), null);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(more);
            }
        });
        top.addView(undoBtn);
        top.addView(redoBtn);
        top.addView(playBtn);
        top.addView(more);
        col.addView(top, Ui.lin(-1, -2));

        // score area with FAB
        FrameLayout content = new FrameLayout(this);
        view = new ScoreView(this, t);
        content.addView(view, new FrameLayout.LayoutParams(-1, -1));
        fab = new ImageButton(this);
        fab.setImageResource(R.drawable.ic_edit);
        fab.setImageTintList(android.content.res.ColorStateList.valueOf(t.onPrimaryContainer));
        fab.setBackground(Ui.rippleRound(t, t.primaryContainer, 16));
        fab.setElevation(t.dp(6));
        fab.setContentDescription(getString(R.string.note_input));
        if (Build.VERSION.SDK_INT >= 26) fab.setTooltipText(getString(R.string.note_input));
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setInputMode(!st.inputMode);
            }
        });
        FrameLayout.LayoutParams flp = Ui.frame(t.dp(56), t.dp(56), Gravity.BOTTOM | Gravity.END);
        flp.setMargins(0, 0, t.dp(16), t.dp(16));
        content.addView(fab, flp);
        col.addView(content, Ui.lin(-1, 0, 1f));

        // bottom toolbars
        LinearLayout bottom = Ui.column(this);
        bottom.setBackgroundColor(t.surfaceContainer);
        bottom.addView(Ui.divider(this, t));
        LinearLayout rowA = Ui.row(this);
        LinearLayout rowB = Ui.row(this);
        rowA.setPadding(t.dp(4), 0, t.dp(4), 0);
        rowB.setPadding(t.dp(4), 0, t.dp(4), 0);
        buildDurationRow(rowA);
        buildActionRow(rowB);
        if (wide()) {
            LinearLayout both = Ui.row(this);
            both.addView(rowA);
            both.addView(Ui.vDivider(this, t));
            both.addView(rowB);
            bottom.addView(scroll(both));
        } else {
            bottom.addView(scroll(rowA));
            bottom.addView(scroll(rowB));
        }
        LinearLayout pianoRow = Ui.row(this);
        pianoRow.addView(Ui.icon(this, t, R.drawable.ic_chevron_left, getString(R.string.octave_down), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                piano.shiftOctave(-1);
            }
        }), new LinearLayout.LayoutParams(t.dp(40), -1));
        piano = new PianoView(this, t);
        piano.setListener(new PianoView.Listener() {
            @Override
            public void onKey(int pitch) {
                pianoKey(pitch);
            }
        });
        pianoRow.addView(piano, Ui.lin(0, -1, 1f));
        pianoRow.addView(Ui.icon(this, t, R.drawable.ic_chevron_right, getString(R.string.octave_up), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                piano.shiftOctave(1);
            }
        }), new LinearLayout.LayoutParams(t.dp(40), -1));
        int ph = tablet ? 180 : (landscape ? 104 : 140);
        pianoBox = pianoRow;
        pianoBox.setVisibility(View.GONE);
        bottom.addView(pianoRow, Ui.lin(-1, t.dp(ph)));
        col.addView(bottom, Ui.lin(-1, -2));

        // palette sheet host (overlay)
        sheetHost = new FrameLayout(this);
        sheetHost.setVisibility(View.GONE);
        root.addView(sheetHost, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        Ui.edgeToEdge(this, t, top, bottom, col);
    }

    private HorizontalScrollView scroll(View v) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFillViewport(false);
        hs.addView(v);
        return hs;
    }

    private void buildDurationRow(LinearLayout row) {
        int[] glyphs = {Sym.metNote32ndUp, Sym.metNote16thUp, Sym.metNote8thUp, Sym.metNoteQuarterUp, Sym.metNoteHalfUp, Sym.metNoteWhole};
        for (int i = 0; i < DUR_TYPES.length; i++) {
            final int type = DUR_TYPES[i];
            durBtns[i] = Ui.glyphButton(this, t, Sym.str(glyphs[i]), true, 22, Duration.label(type, 0), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (st == null) return;
                    st.setDuration(type);
                    refresh();
                }
            });
            row.addView(durBtns[i]);
        }
        dotBtn = Ui.glyphButton(this, t, Sym.str(Sym.metAugmentationDot), true, 22, getString(R.string.dot), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st == null) return;
                st.toggleDot();
                refresh();
            }
        });
        row.addView(dotBtn);
        row.addView(Ui.vDivider(this, t));
        row.addView(Ui.glyphButton(this, t, Sym.str(Sym.restQuarter), true, 20, getString(R.string.rest), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st == null) return;
                if (st.inputMode) st.enterRest();
                else st.deleteSelection();
                refresh();
                followCursor();
            }
        }));
        row.addView(Ui.glyphButton(this, t, "⁀", false, 26, getString(R.string.tie), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st == null) return;
                boolean ok = st.inputMode ? st.enterTie() : st.toggleTie();
                if (!ok) toast(getString(R.string.nothing_selected));
                refresh();
                followCursor();
            }
        }));
        tripletBtn = Ui.glyphButton(this, t, Sym.str(Sym.tuplet3), true, 22, getString(R.string.triplet), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st == null) return;
                st.triplet = !st.triplet;
                refresh();
            }
        });
        row.addView(tripletBtn);
    }

    private void buildActionRow(LinearLayout row) {
        int[] acc = {Sym.accidentalDoubleFlat, Sym.accidentalFlat, Sym.accidentalNatural, Sym.accidentalSharp, Sym.accidentalDoubleSharp};
        int[] accDesc = {R.string.double_flat, R.string.flat, R.string.natural, R.string.sharp, R.string.double_sharp};
        for (int i = 0; i < acc.length; i++) {
            final int alter = i - 2;
            row.addView(Ui.glyphButton(this, t, Sym.str(acc[i]), true, 20, getString(accDesc[i]), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (st == null) return;
                    if (st.selectedNote() == null) {
                        toast(getString(R.string.nothing_selected));
                        return;
                    }
                    st.applyAccidental(alter);
                    previewSelected();
                    refresh();
                }
            }));
        }
        row.addView(Ui.vDivider(this, t));
        row.addView(Ui.icon(this, t, R.drawable.ic_keyboard_arrow_up, getString(R.string.up), transposeClick(1)));
        row.addView(Ui.icon(this, t, R.drawable.ic_keyboard_arrow_down, getString(R.string.down), transposeClick(-1)));
        row.addView(Ui.icon(this, t, R.drawable.ic_keyboard_double_arrow_up, getString(R.string.octave_up), transposeClick(12)));
        row.addView(Ui.icon(this, t, R.drawable.ic_keyboard_double_arrow_down, getString(R.string.octave_down), transposeClick(-12)));
        row.addView(Ui.vDivider(this, t));
        row.addView(Ui.icon(this, t, R.drawable.ic_chevron_left, getString(R.string.prev), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                move(-1);
            }
        }));
        row.addView(Ui.icon(this, t, R.drawable.ic_chevron_right, getString(R.string.next), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                move(1);
            }
        }));
        row.addView(Ui.icon(this, t, R.drawable.ic_backspace, getString(R.string.delete_selection), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st == null) return;
                st.deleteSelection();
                refresh();
            }
        }));
        row.addView(Ui.vDivider(this, t));
        for (int i = 0; i < 4; i++) {
            final int v = i;
            voiceBtns[i] = Ui.glyphButton(this, t, String.valueOf(i + 1), false, 16, getString(R.string.voice, i + 1), new View.OnClickListener() {
                @Override
                public void onClick(View vv) {
                    if (st == null) return;
                    st.setVoice(v);
                    refresh();
                }
            });
            voiceBtns[i].setLayoutParams(new LinearLayout.LayoutParams(t.dp(40), t.dp(48)));
            row.addView(voiceBtns[i]);
        }
        chordBtn = Ui.icon(this, t, R.drawable.ic_add_box, getString(R.string.chord_mode), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st == null) return;
                st.chordMode = !st.chordMode;
                refresh();
            }
        });
        row.addView(chordBtn);
        kbdBtn = Ui.icon(this, t, R.drawable.ic_piano, getString(R.string.keyboard), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pianoVisible = !pianoVisible;
                refresh();
            }
        });
        row.addView(kbdBtn);
        row.addView(Ui.vDivider(this, t));
        row.addView(Ui.icon(this, t, R.drawable.ic_palette, getString(R.string.palettes), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPalette();
            }
        }));
        row.addView(Ui.icon(this, t, R.drawable.ic_text_fields, getString(R.string.lyrics), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lyricsDialog();
            }
        }));
        row.addView(Ui.icon(this, t, R.drawable.ic_content_copy, getString(R.string.copy), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st != null && st.copy()) toast(getString(R.string.copied));
                else toast(getString(R.string.nothing_selected));
            }
        }));
        row.addView(Ui.icon(this, t, R.drawable.ic_content_paste, getString(R.string.paste), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st != null && st.hasClipboard()) {
                    st.paste();
                    refresh();
                }
            }
        }));
    }

    private View.OnClickListener transposeClick(final int semis) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st == null) return;
                st.transpose(semis);
                previewSelected();
                refresh();
            }
        };
    }

    // ------------------------------------------------------------------ state -> UI

    private void refresh() {
        if (st == null) return;
        view.refresh();
        titleView.setText(st.score.title);
        statusView.setText(statusText());
        undoBtn.setEnabled(st.canUndo());
        undoBtn.setAlpha(st.canUndo() ? 1f : 0.38f);
        redoBtn.setEnabled(st.canRedo());
        redoBtn.setAlpha(st.canRedo() ? 1f : 0.38f);
        for (int i = 0; i < durBtns.length; i++) Ui.setToggled(t, durBtns[i], st.durType == DUR_TYPES[i]);
        Ui.setToggled(t, dotBtn, st.dots > 0);
        Ui.setToggled(t, tripletBtn, st.triplet);
        for (int i = 0; i < 4; i++) {
            Ui.setToggled(t, voiceBtns[i], st.voice == i);
            if (st.voice != i) voiceBtns[i].setTextColor(t.voice[i]);
        }
        Ui.setToggled(t, chordBtn, st.chordMode);
        Ui.setToggled(t, kbdBtn, pianoVisible && st.inputMode);
        pianoBox.setVisibility(st.inputMode && pianoVisible ? View.VISIBLE : View.GONE);
        fab.setImageResource(st.inputMode ? R.drawable.ic_check : R.drawable.ic_edit);
        String fabDesc = getString(st.inputMode ? R.string.exit_note_input : R.string.note_input);
        fab.setContentDescription(fabDesc);
        if (Build.VERSION.SDK_INT >= 26) fab.setTooltipText(fabDesc);
        Note n = st.selectedNote();
        piano.setHighlight(n != null ? n.pitch : -1);
        if (st.revision != savedRevision) {
            handler.removeCallbacks(autosave);
            handler.postDelayed(autosave, 1500);
        }
        updateBackCallback();
    }

    private String statusText() {
        StringBuilder sb = new StringBuilder();
        if (st.inputMode && st.cursor != null) {
            sb.append(getString(R.string.note_input)).append(" · ");
            sb.append("m. ").append(st.score.measureNumber(Math.min(st.cursor.measure, st.score.measures.size() - 1)));
            sb.append(" · ").append(getString(R.string.voice, st.voice + 1));
            sb.append(" · ").append(Duration.label(st.durType, st.dots));
            if (st.triplet) sb.append(" (3)");
            return sb.toString();
        }
        if (st.selKind == EditorState.SEL_RANGE) {
            sb.append("m. ").append(st.score.measureNumber(st.rangeM1));
            if (st.rangeM2 != st.rangeM1) sb.append("–").append(st.score.measureNumber(st.rangeM2));
            Part p = st.score.partOfStaff(st.rangeS1);
            sb.append(" · ").append(p.name);
            return sb.toString();
        }
        ChordRest cr = st.selectedChordRest();
        if (cr != null) {
            Note n = st.selectedNote();
            if (n != null && st.selKind == EditorState.SEL_NOTE) sb.append(Pitch.name(n.pitch, n.tpc)).append(" · ");
            else if (cr instanceof Chord) sb.append(((Chord) cr).notes.size()).append("-note chord · ");
            else sb.append(getString(R.string.rest)).append(" · ");
            sb.append(Duration.label(cr.type, cr.dots)).append(" · m. ").append(st.score.measureNumber(st.selPos.measure));
            sb.append(" · ").append(getString(R.string.voice, st.selPos.voice + 1));
            return sb.toString();
        }
        return getString(R.string.hint_select);
    }

    private void setInputMode(boolean on) {
        if (st == null) return;
        if (on) {
            st.startInput();
            if (st.cursor != null) {
                int clef = st.score.clefAt(st.cursor.staff, st.cursor.measure);
                Note n = st.selectedNote();
                piano.centerOn(n != null ? n.pitch : Clef.middlePitch(clef) + 2);
            }
        } else {
            st.stopInput();
        }
        refresh();
        followCursor();
    }

    private void followCursor() {
        if (st == null) return;
        if (st.inputMode && st.cursor != null) view.ensureVisible(st.cursor.measure, st.cursor.staff, true);
        else if (st.selPos != null) view.ensureVisible(st.selPos.measure, st.selPos.staff, true);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ input actions

    private void pianoKey(int pitch) {
        if (st == null) return;
        if (!st.inputMode) {
            // outside note input the keyboard re-pitches the selected note (or just plays)
            synth.play(pitch);
            return;
        }
        int p = st.enterPitch(pitch, Integer.MIN_VALUE);
        synth.play(p);
        refresh();
        followCursor();
    }

    private void move(int dir) {
        if (st == null) return;
        st.moveCursor(dir);
        previewSelected();
        refresh();
        followCursor();
    }

    private void previewSelected() {
        Note n = st.selectedNote();
        if (n != null) synth.play(n.pitch);
    }

    @Override
    public void onSelectionChanged() {
        if (st.inputMode && st.selPos != null) st.setCursor(st.selPos);
        previewSelected();
        refresh();
    }

    @Override
    public void onStaffNote(Pos pos, int line) {
        int pitch = view.pitchForLine(pos.measure, pos.staff, line);
        ChordRest at = ScoreEdit.elementAt(st.score, pos);
        if (st.chordMode && at instanceof Chord) {
            st.selectNote(pos, -1);
            st.addPitchToSelectedChord(pitch);
        } else {
            st.setCursor(pos);
            st.enterPitch(pitch, Integer.MIN_VALUE);
        }
        synth.play(pitch);
        refresh();
        followCursor();
    }

    @Override
    public void onLongPress() {
        refresh();
    }

    // ------------------------------------------------------------------ playback

    private void togglePlay() {
        if (st == null) return;
        if (player.isPlaying()) {
            player.stop();
            return;
        }
        int m = 0, tick = 0;
        if (st.selKind == EditorState.SEL_RANGE) m = st.rangeM1;
        else if (st.selPos != null) {
            m = st.selPos.measure;
            tick = st.selPos.tick;
        } else if (st.inputMode && st.cursor != null) {
            m = st.cursor.measure;
            tick = st.cursor.tick;
        }
        playBtn.setImageResource(R.drawable.ic_pause);
        playBtn.setContentDescription(getString(R.string.pause));
        player.play(st.score, m, tick, new Player.Listener() {
            @Override
            public void onPosition(int measure, int t2) {
                view.setPlayPosition(measure, t2);
                view.ensureVisible(measure, 0, true);
            }

            @Override
            public void onStopped() {
                playBtn.setImageResource(R.drawable.ic_play_arrow);
                playBtn.setContentDescription(getString(R.string.play));
                view.setPlayPosition(-1, 0);
            }

            @Override
            public void onError(String msg) {
                toast(getString(R.string.playback_error));
            }
        });
    }

    // ------------------------------------------------------------------ saving / export

    private void save(final boolean announce) {
        if (st == null || scoreId == null || st.revision == savedRevision || io.isShutdown()) return;
        final Score copy = st.score.copy();
        final int rev = st.revision;
        final String id = scoreId;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    store.save(id, copy, Exporter.thumbnail(EditorActivity.this, copy, 360));
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            savedRevision = Math.max(savedRevision, rev);
                            if (announce) toast(getString(R.string.saved));
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            toast(getString(R.string.export_failed, String.valueOf(e.getMessage())));
                        }
                    });
                }
            }
        });
    }

    private void exportTo(int format) {
        pendingExportFormat = format;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(Exporter.MIME[format]);
        i.putExtra(Intent.EXTRA_TITLE, Exporter.fileName(st.score, format));
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            toast(getString(R.string.export_failed, e.getMessage()));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EXPORT || res != RESULT_OK || data == null || data.getData() == null || st == null) return;
        final Uri uri = data.getData();
        final int fmt = pendingExportFormat;
        final Score copy = st.score.copy();
        io.execute(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    OutputStream out = getContentResolver().openOutputStream(uri, "wt");
                    Exporter.write(EditorActivity.this, copy, fmt, out);
                    out.close();
                    msg = getString(R.string.exported, Exporter.fileName(copy, fmt));
                } catch (Exception e) {
                    msg = getString(R.string.export_failed, String.valueOf(e.getMessage()));
                }
                final String m = msg;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(m);
                    }
                });
            }
        });
    }

    private void share(final int format) {
        final Score copy = st.score.copy();
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = ShareProvider.shareDir(EditorActivity.this);
                    File[] old = dir.listFiles();
                    if (old != null) for (File f : old) f.delete();
                    final File f = new File(dir, Exporter.fileName(copy, format));
                    FileOutputStream out = new FileOutputStream(f);
                    Exporter.write(EditorActivity.this, copy, format, out);
                    out.close();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Uri uri = ShareProvider.uriFor(f);
                            Intent send = new Intent(Intent.ACTION_SEND);
                            send.setType(Exporter.MIME[format]);
                            send.putExtra(Intent.EXTRA_STREAM, uri);
                            send.putExtra(Intent.EXTRA_SUBJECT, copy.title);
                            send.setClipData(ClipData.newRawUri(f.getName(), uri));
                            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(send, getString(R.string.share)));
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            toast(getString(R.string.export_failed, String.valueOf(e.getMessage())));
                        }
                    });
                }
            }
        });
    }

    // ------------------------------------------------------------------ menu

    private void showMenu(View anchor) {
        if (st == null) return;
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, R.string.score_properties);
        m.add(0, 2, 0, R.string.instruments);
        m.add(0, 3, 0, R.string.mixer);
        m.add(0, 4, 0, R.string.add_measures);
        m.add(0, 5, 0, R.string.insert_measure);
        m.add(0, 6, 0, R.string.delete_measures);
        m.add(0, 7, 0, R.string.select_all);
        Menu exp = m.addSubMenu(0, 8, 0, R.string.export);
        exp.add(1, 100 + Exporter.PDF, 0, R.string.export_pdf);
        exp.add(1, 100 + Exporter.MUSICXML, 0, R.string.export_musicxml);
        exp.add(1, 100 + Exporter.MIDI, 0, R.string.export_midi);
        exp.add(1, 100 + Exporter.MSCZ, 0, R.string.export_mscz);
        Menu sh = m.addSubMenu(0, 9, 0, R.string.share);
        sh.add(2, 200 + Exporter.PDF, 0, R.string.export_pdf);
        sh.add(2, 200 + Exporter.MUSICXML, 0, R.string.export_musicxml);
        sh.add(2, 200 + Exporter.MIDI, 0, R.string.export_midi);
        sh.add(2, 200 + Exporter.MSCZ, 0, R.string.export_mscz);
        m.add(0, 10, 0, R.string.zoom_in);
        m.add(0, 11, 0, R.string.zoom_out);
        m.add(0, 12, 0, R.string.shortcuts);
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                if (id >= 200) share(id - 200);
                else if (id >= 100) exportTo(id - 100);
                else switch (id) {
                        case 1:
                            propertiesDialog();
                            break;
                        case 2:
                            instrumentsDialog();
                            break;
                        case 3:
                            mixerDialog();
                            break;
                        case 4:
                            addMeasuresDialog();
                            break;
                        case 5:
                            st.insertMeasureBefore();
                            refresh();
                            break;
                        case 6:
                            st.deleteSelectedMeasures();
                            refresh();
                            break;
                        case 7:
                            st.selectAll();
                            refresh();
                            break;
                        case 10:
                            view.setZoom(view.getZoom() * 1.25f);
                            break;
                        case 11:
                            view.setZoom(view.getZoom() / 1.25f);
                            break;
                        case 12:
                            shortcutsDialog();
                            break;
                        default:
                            return false;
                    }
                return true;
            }
        });
        pm.show();
    }

    // ------------------------------------------------------------------ palette sheet

    private void openPalette() {
        if (st == null) return;
        sheetHost.removeAllViews();
        View scrim = new View(this);
        scrim.setBackgroundColor(0x52000000);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePalette();
            }
        });
        sheetHost.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout sheet = Ui.column(this);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(t.surfaceContainer);
        float r = t.dp(28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
        sheet.setElevation(t.dp(8));
        sheet.setClickable(true);
        // drag handle
        View handle = new View(this);
        handle.setBackground(Ui.round(t.outline, t.dp(2)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(t.dp(32), t.dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = t.dp(16);
        hlp.bottomMargin = t.dp(8);
        sheet.addView(handle, hlp);
        LinearLayout header = Ui.row(this);
        header.setPadding(t.dp(24), 0, t.dp(8), 0);
        TextView title = Ui.text(this, getString(R.string.palettes), 22, t.onSurface, false);
        header.addView(title, Ui.lin(0, -2, 1f));
        header.addView(Ui.icon(this, t, R.drawable.ic_close, getString(R.string.done), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePalette();
            }
        }));
        sheet.addView(header);
        palette = new PaletteView(this, t);
        palette.setCategories(Palettes.build(this));
        palette.setOnApplied(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        });
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        sheet.addView(palette, Ui.lin(-1, h));
        int nav = 0;
        if (Build.VERSION.SDK_INT >= 23 && root.getRootWindowInsets() != null) nav = root.getRootWindowInsets().getSystemWindowInsetBottom();
        sheet.setPadding(0, 0, 0, nav);
        int maxW = t.dp(720);
        int w = Math.min(maxW, getResources().getDisplayMetrics().widthPixels);
        sheetHost.addView(sheet, Ui.frame(w, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        sheetHost.setVisibility(View.VISIBLE);
        sheet.setTranslationY(h);
        sheet.animate().translationY(0).setDuration(250).start();
        updateBackCallback();
    }

    private void closePalette() {
        sheetHost.setVisibility(View.GONE);
        sheetHost.removeAllViews();
        palette = null;
        updateBackCallback();
    }

    boolean paletteOpen() {
        return sheetHost.getVisibility() == View.VISIBLE;
    }

    /** Palette contents; actions operate on the editor's selection. */
    static final class Palettes {
        static List<PaletteView.Category> build(final EditorActivity a) {
            final EditorState st = a.st;
            List<PaletteView.Category> cats = new ArrayList<PaletteView.Category>();
            PaletteView.Category clefs = new PaletteView.Category("Clefs");
            int[] cg = {Sym.gClef, Sym.fClef, Sym.cClef, Sym.cClef, Sym.gClef8vb, Sym.gClef8va, Sym.fClef8vb, Sym.unpitchedPercussionClef1};
            int[] ct = {Clef.TREBLE, Clef.BASS, Clef.ALTO, Clef.TENOR, Clef.TREBLE_8VB, Clef.TREBLE_8VA, Clef.BASS_8VB, Clef.PERC};
            for (int i = 0; i < cg.length; i++) {
                final int clef = ct[i];
                clefs.add(new PaletteView.Cell(Sym.str(cg[i]), true, Clef.label(clef), Clef.label(clef) + " clef", new Runnable() {
                    @Override
                    public void run() {
                        if (!a.requireSelection()) return;
                        st.setClef(clef);
                    }
                }));
            }
            cats.add(clefs);

            PaletteView.Category keys = new PaletteView.Category("Key signatures");
            for (int k = -7; k <= 7; k++) {
                final int key = k;
                String g;
                if (k == 0) g = Sym.str(Sym.accidentalNatural);
                else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.abs(k); i++) sb.append(Sym.str(k > 0 ? Sym.accidentalSharp : Sym.accidentalFlat));
                    g = sb.toString();
                }
                String name = Pitch.keyName(k).replace(" major", "");
                keys.add(new PaletteView.Cell(g, true, name + " / " + minorName(k), Pitch.keyName(k), new Runnable() {
                    @Override
                    public void run() {
                        st.setKeySig(key);
                    }
                }).scale(0.7f));
            }
            cats.add(keys);

            PaletteView.Category times = new PaletteView.Category("Time signatures");
            int[][] ts = {{2, 4}, {3, 4}, {4, 4}, {5, 4}, {6, 4}, {2, 2}, {3, 8}, {6, 8}, {9, 8}, {12, 8}, {5, 8}, {7, 8}};
            for (final int[] tsi : ts) {
                times.add(new PaletteView.Cell(tsi[0] + "/" + tsi[1], false, "", tsi[0] + "/" + tsi[1] + " time", new Runnable() {
                    @Override
                    public void run() {
                        st.setTimeSig(tsi[0], tsi[1], false);
                    }
                }).scale(1.3f));
            }
            times.add(new PaletteView.Cell(Sym.str(Sym.timeSigCommon), true, "Common", "Common time", new Runnable() {
                @Override
                public void run() {
                    st.setTimeSig(4, 4, true);
                }
            }));
            times.add(new PaletteView.Cell(Sym.str(Sym.timeSigCutCommon), true, "Cut", "Cut time", new Runnable() {
                @Override
                public void run() {
                    st.setTimeSig(2, 2, true);
                }
            }));
            times.add(new PaletteView.Cell("n/d", false, "Custom…", "Custom time signature", new Runnable() {
                @Override
                public void run() {
                    a.customTimeSigDialog();
                }
            }));
            cats.add(times);

            PaletteView.Category accs = new PaletteView.Category("Accidentals");
            int[] ag = {Sym.accidentalDoubleFlat, Sym.accidentalFlat, Sym.accidentalNatural, Sym.accidentalSharp, Sym.accidentalDoubleSharp};
            String[] an = {"Double flat", "Flat", "Natural", "Sharp", "Double sharp"};
            for (int i = 0; i < ag.length; i++) {
                final int alter = i - 2;
                accs.add(new PaletteView.Cell(Sym.str(ag[i]), true, an[i], an[i], new Runnable() {
                    @Override
                    public void run() {
                        if (st.selectedNote() == null) {
                            a.toast(a.getString(R.string.nothing_selected));
                            return;
                        }
                        st.applyAccidental(alter);
                    }
                }));
            }
            cats.add(accs);

            PaletteView.Category arts = new PaletteView.Category("Articulations");
            for (int i = 0; i < Articulation.ALL.length; i++) {
                final String id = Articulation.ALL[i];
                String sym = Articulation.smufl(id, true);
                arts.add(new PaletteView.Cell(Sym.str(Sym.byName(sym)), true, Articulation.LABELS[i], Articulation.LABELS[i], new Runnable() {
                    @Override
                    public void run() {
                        if (!a.requireSelection()) return;
                        st.toggleArticulation(id);
                    }
                }));
            }
            cats.add(arts);

            PaletteView.Category dyn = new PaletteView.Category("Dynamics");
            for (final String d : Dynamics.ALL) {
                dyn.add(new PaletteView.Cell(Sym.str(Sym.byName(Dynamics.smufl(d))), true, "", "Dynamic " + d, new Runnable() {
                    @Override
                    public void run() {
                        if (!a.requireSelection()) return;
                        st.setDynamic(d);
                    }
                }));
            }
            cats.add(dyn);

            PaletteView.Category bars = new PaletteView.Category("Barlines & repeats");
            bars.add(new PaletteView.Cell("‖", false, "Double", "Double barline", barline(st, Measure.BAR_DOUBLE)));
            bars.add(new PaletteView.Cell("│┃", false, "Final", "Final barline", barline(st, Measure.BAR_FINAL)));
            bars.add(new PaletteView.Cell("┆", false, "Dashed", "Dashed barline", barline(st, Measure.BAR_DASHED)));
            bars.add(new PaletteView.Cell("┃│:", false, "Start repeat", "Start repeat", new Runnable() {
                @Override
                public void run() {
                    st.toggleStartRepeat();
                }
            }));
            bars.add(new PaletteView.Cell(":│┃", false, "End repeat", "End repeat", barline(st, Measure.BAR_END_REPEAT)));
            bars.add(new PaletteView.Cell("│", false, "Normal", "Normal barline", barline(st, Measure.BAR_NORMAL)));
            cats.add(bars);

            PaletteView.Category text = new PaletteView.Category("Text");
            text.add(new PaletteView.Cell(Sym.str(Sym.metNoteQuarterUp) + "=", true, "Tempo", a.getString(R.string.tempo_marking), new Runnable() {
                @Override
                public void run() {
                    a.tempoDialog();
                }
            }).scale(0.8f));
            text.add(new PaletteView.Cell("A", false, "Rehearsal", a.getString(R.string.rehearsal_mark), new Runnable() {
                @Override
                public void run() {
                    a.textDialog(a.getString(R.string.rehearsal_mark), 1);
                }
            }));
            text.add(new PaletteView.Cell("Abc", false, "Staff text", a.getString(R.string.staff_text), new Runnable() {
                @Override
                public void run() {
                    a.textDialog(a.getString(R.string.staff_text), 2);
                }
            }));
            text.add(new PaletteView.Cell("C⁷", false, "Chord", a.getString(R.string.chord_symbol), new Runnable() {
                @Override
                public void run() {
                    a.textDialog(a.getString(R.string.chord_symbol), 3);
                }
            }));
            text.add(new PaletteView.Cell("la", false, "Lyrics", a.getString(R.string.lyrics), new Runnable() {
                @Override
                public void run() {
                    a.lyricsDialog();
                }
            }));
            cats.add(text);

            PaletteView.Category meas = new PaletteView.Category("Measures");
            meas.add(new PaletteView.Cell("+1", false, "Append", "Append one measure", new Runnable() {
                @Override
                public void run() {
                    st.appendMeasures(1);
                }
            }));
            meas.add(new PaletteView.Cell("+4", false, "Append", "Append four measures", new Runnable() {
                @Override
                public void run() {
                    st.appendMeasures(4);
                }
            }));
            meas.add(new PaletteView.Cell("⇤+", false, "Insert", a.getString(R.string.insert_measure), new Runnable() {
                @Override
                public void run() {
                    st.insertMeasureBefore();
                }
            }));
            meas.add(new PaletteView.Cell("−", false, "Delete", a.getString(R.string.delete_measures), new Runnable() {
                @Override
                public void run() {
                    st.deleteSelectedMeasures();
                }
            }));
            cats.add(meas);
            return cats;
        }

        private static Runnable barline(final EditorState st, final int type) {
            return new Runnable() {
                @Override
                public void run() {
                    st.setBarline(type);
                }
            };
        }

        private static String minorName(int k) {
            String[] names = {"a♭", "e♭", "b♭", "f", "c", "g", "d", "a", "e", "b", "f♯", "c♯", "g♯", "d♯", "a♯"};
            return names[k + 7];
        }
    }

    boolean requireSelection() {
        if (st.hasSelection() || (st.inputMode && st.cursor != null)) return true;
        toast(getString(R.string.nothing_selected));
        return false;
    }

    // ------------------------------------------------------------------ dialogs

    private LinearLayout dialogBody() {
        LinearLayout l = Ui.column(this);
        l.setPadding(t.dp(24), t.dp(8), t.dp(24), 0);
        return l;
    }

    private void propertiesDialog() {
        if (st == null) return;
        LinearLayout body = dialogBody();
        final EditText title = labeled(body, getString(R.string.title), st.score.title);
        final EditText sub = labeled(body, getString(R.string.subtitle), st.score.subtitle);
        final EditText comp = labeled(body, getString(R.string.composer), st.score.composer);
        final EditText lyr = labeled(body, getString(R.string.lyricist), st.score.lyricist);
        final EditText copy = labeled(body, getString(R.string.copyright), st.score.copyright);
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        new AlertDialog.Builder(this).setTitle(R.string.score_properties).setView(sv)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        st.checkpoint();
                        st.score.title = title.getText().toString().trim().isEmpty() ? "Untitled score" : title.getText().toString().trim();
                        st.score.subtitle = sub.getText().toString().trim();
                        st.score.composer = comp.getText().toString().trim();
                        st.score.lyricist = lyr.getText().toString().trim();
                        st.score.copyright = copy.getText().toString().trim();
                        st.externalChange();
                        refresh();
                    }
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private EditText labeled(LinearLayout body, String label, String value) {
        TextView l = Ui.text(this, label, 12, t.primary, true);
        l.setPadding(0, t.dp(8), 0, 0);
        body.addView(l);
        EditText e = Ui.input(this, t, label, value);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        body.addView(e, Ui.lin(-1, -2));
        return e;
    }

    void textDialog(String title, final int kind) {
        if (!requireSelection()) return;
        LinearLayout body = dialogBody();
        String cur = null;
        ChordRest cr = st.selectedChordRest();
        if (kind == 1) cur = st.score.measures.get(st.selectionMeasure()).rehearsal;
        else if (kind == 2 && cr != null) cur = cr.staffText;
        else if (kind == 3 && cr != null) cur = cr.chordSymbol;
        final EditText e = Ui.input(this, t, title, cur == null ? "" : cur);
        if (kind == 3) e.setHint("e.g. Cmaj7, F#m7b5, Bb/D");
        body.addView(e, Ui.lin(-1, -2));
        final AlertDialog d = new AlertDialog.Builder(this).setTitle(title).setView(body)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dd, int w) {
                        String v = e.getText().toString();
                        if (kind == 1) st.setRehearsal(v);
                        else if (kind == 2) st.setStaffText(v);
                        else st.setChordSymbol(v);
                        refresh();
                    }
                }).setNegativeButton(R.string.cancel, null).create();
        showWithKeyboard(d, e);
    }

    void lyricsDialog() {
        ChordRest cr = st == null ? null : st.selectedChordRest();
        if (!(cr instanceof Chord)) {
            toast(getString(R.string.nothing_selected));
            return;
        }
        LinearLayout body = dialogBody();
        final EditText e = Ui.input(this, t, getString(R.string.lyrics), cr.lyric == null ? "" : cr.lyric);
        e.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        TextView hint = Ui.text(this, "End a syllable with \"-\" to hyphenate. Next moves to the following note.", 12, t.onSurfaceVariant, false);
        body.addView(e, Ui.lin(-1, -2));
        body.addView(hint);
        final AlertDialog d = new AlertDialog.Builder(this).setTitle(getString(R.string.lyrics) + " · m. " + st.score.measureNumber(st.selPos.measure))
                .setView(body)
                .setPositiveButton(R.string.done, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dd, int w) {
                        st.setLyric(e.getText().toString());
                        refresh();
                    }
                })
                .setNeutralButton(R.string.next_syllable, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dd, int w) {
                        nextLyric(e.getText().toString());
                    }
                })
                .setNegativeButton(R.string.cancel, null).create();
        e.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
                d.dismiss();
                nextLyric(e.getText().toString());
                return true;
            }
        });
        showWithKeyboard(d, e);
    }

    private void nextLyric(String text) {
        st.setLyric(text);
        boolean more = st.selectNextChord();
        refresh();
        followCursor();
        if (more) lyricsDialog();
    }

    private void showWithKeyboard(AlertDialog d, EditText e) {
        d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        e.requestFocus();
        e.setSelection(e.getText().length());
    }

    void tempoDialog() {
        if (st == null) return;
        final int m = st.selectionMeasure();
        Measure mm = st.score.measures.get(m);
        LinearLayout body = dialogBody();
        final EditText txt = labeled(body, "Text (optional)", mm.tempoText == null ? "" : mm.tempoText);
        txt.setHint("Allegro, Andante…");
        final EditText bpm = labeled(body, Sym.str(Sym.metNoteQuarterUp) + " BPM", String.valueOf(mm.tempo > 0 ? mm.tempo : st.score.tempoAt(m)));
        bpm.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this).setTitle(getString(R.string.tempo_marking) + " · m. " + st.score.measureNumber(m)).setView(body)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int v;
                        try {
                            v = Integer.parseInt(bpm.getText().toString().trim());
                        } catch (NumberFormatException e) {
                            v = 0;
                        }
                        st.setTempo(m, v, txt.getText().toString());
                        refresh();
                    }
                })
                .setNeutralButton(R.string.remove, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        st.setTempo(m, 0, null);
                        refresh();
                    }
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    void customTimeSigDialog() {
        LinearLayout body = Ui.row(this);
        body.setGravity(Gravity.CENTER);
        final NumberPicker n = new NumberPicker(this);
        n.setMinValue(1);
        n.setMaxValue(32);
        n.setValue(st.score.measures.get(st.selectionMeasure()).timeN);
        final NumberPicker d = new NumberPicker(this);
        final String[] dens = {"1", "2", "4", "8", "16", "32"};
        d.setDisplayedValues(dens);
        d.setMinValue(0);
        d.setMaxValue(dens.length - 1);
        d.setValue(2);
        body.addView(n);
        body.addView(Ui.text(this, " / ", 24, t.onSurface, false));
        body.addView(d);
        new AlertDialog.Builder(this).setTitle(R.string.time_signature).setView(body)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dd, int w) {
                        st.setTimeSig(n.getValue(), Integer.parseInt(dens[d.getValue()]), false);
                        refresh();
                    }
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void addMeasuresDialog() {
        LinearLayout body = dialogBody();
        final NumberPicker np = new NumberPicker(this);
        np.setMinValue(1);
        np.setMaxValue(64);
        np.setValue(4);
        body.addView(np);
        new AlertDialog.Builder(this).setTitle(R.string.add_measures).setView(body)
                .setPositiveButton(R.string.add, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        st.appendMeasures(np.getValue());
                        refresh();
                    }
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void instrumentsDialog() {
        final LinearLayout body = dialogBody();
        final AlertDialog[] holder = new AlertDialog[1];
        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() {
            @Override
            public void run() {
                body.removeAllViews();
                final Score s = st.score;
                for (int i = 0; i < s.parts.size(); i++) {
                    final int idx = i;
                    final Part p = s.parts.get(i);
                    LinearLayout row = Ui.row(EditorActivity.this);
                    TextView name = Ui.text(EditorActivity.this, p.name, 16, t.onSurface, false);
                    row.addView(name, Ui.lin(0, -2, 1f));
                    final Runnable self = this;
                    row.addView(Ui.icon(EditorActivity.this, t, R.drawable.ic_keyboard_arrow_up, getString(R.string.move_up), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (idx == 0) return;
                            st.checkpoint();
                            s.movePart(idx, idx - 1);
                            st.externalChange();
                            refresh();
                            self.run();
                        }
                    }));
                    row.addView(Ui.icon(EditorActivity.this, t, R.drawable.ic_keyboard_arrow_down, getString(R.string.move_down), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (idx >= s.parts.size() - 1) return;
                            st.checkpoint();
                            s.movePart(idx, idx + 1);
                            st.externalChange();
                            refresh();
                            self.run();
                        }
                    }));
                    row.addView(Ui.icon(EditorActivity.this, t, R.drawable.ic_delete, getString(R.string.remove), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (s.parts.size() <= 1) return;
                            st.checkpoint();
                            s.removePart(p);
                            st.clearSelection();
                            if (st.cursor != null) st.cursor = null;
                            st.stopInput();
                            st.externalChange();
                            refresh();
                            self.run();
                        }
                    }));
                    body.addView(row);
                }
                final Runnable self = this;
                TextView add = Ui.button(EditorActivity.this, t, getString(R.string.add_instrument), 1, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickInstrument(new InstrumentPicked() {
                            @Override
                            public void picked(Instruments.Def def) {
                                st.checkpoint();
                                st.score.addPart(def.create());
                                st.externalChange();
                                refresh();
                                self.run();
                            }
                        });
                    }
                });
                LinearLayout.LayoutParams lp = Ui.lin(-2, -2);
                lp.topMargin = t.dp(12);
                body.addView(add, lp);
            }
        };
        rebuild[0].run();
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        holder[0] = new AlertDialog.Builder(this).setTitle(R.string.instruments).setView(sv).setPositiveButton(R.string.done, null).show();
    }

    interface InstrumentPicked {
        void picked(Instruments.Def def);
    }

    void pickInstrument(final InstrumentPicked cb) {
        final List<Instruments.Def> defs = new ArrayList<Instruments.Def>();
        List<String> labels = new ArrayList<String>();
        for (String g : Instruments.groups()) {
            for (Instruments.Def d : Instruments.ALL) {
                if (!d.group.equals(g)) continue;
                defs.add(d);
                labels.add(d.name + "  ·  " + g);
            }
        }
        new AlertDialog.Builder(this).setTitle(R.string.add_instrument)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        cb.picked(defs.get(which));
                    }
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void mixerDialog() {
        LinearLayout body = dialogBody();
        TextView speedLabel = Ui.text(this, getString(R.string.tempo_scale) + ": " + Math.round(player.getTempoScale() * 100) + "%", 14, t.onSurface, true);
        body.addView(speedLabel);
        final TextView sl = speedLabel;
        SeekBar speed = new SeekBar(this);
        speed.setMax(150);
        speed.setProgress((int) Math.round(player.getTempoScale() * 100) - 50);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                player.setTempoScale((p + 50) / 100.0);
                sl.setText(getString(R.string.tempo_scale) + ": " + (p + 50) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        body.addView(speed);
        final CheckBox preview = new CheckBox(this);
        preview.setText("Play notes while editing");
        preview.setChecked(synth.enabled);
        preview.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                synth.enabled = c;
            }
        });
        body.addView(preview);
        for (final Part p : st.score.parts) {
            TextView name = Ui.text(this, p.name, 14, t.onSurface, true);
            name.setPadding(0, t.dp(12), 0, 0);
            body.addView(name);
            LinearLayout row = Ui.row(this);
            SeekBar vol = new SeekBar(this);
            vol.setMax(127);
            vol.setProgress(p.volume);
            vol.setContentDescription(p.name + " " + getString(R.string.volume));
            vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int pr, boolean u) {
                    if (u) p.volume = pr;
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                    st.checkpoint();
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    st.externalChange();
                }
            });
            row.addView(vol, Ui.lin(0, -2, 1f));
            CheckBox mute = new CheckBox(this);
            mute.setText(R.string.mute);
            mute.setChecked(p.muted);
            mute.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                    st.checkpoint();
                    p.muted = c;
                    st.externalChange();
                }
            });
            row.addView(mute);
            body.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        new AlertDialog.Builder(this).setTitle(R.string.mixer).setView(sv).setPositiveButton(R.string.done, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                refresh();
            }
        }).show();
    }

    private void shortcutsDialog() {
        String s = "N — note input on/off\n"
                + "A–G — enter note (Shift: add to chord)\n"
                + "1–7 — 64th … whole (as in MuseScore), . — dot\n"
                + "0 — rest,  + — tie,  T — triplet\n"
                + "↑ ↓ — semitone,  Ctrl+↑ ↓ — octave\n"
                + "← → — previous / next\n"
                + "Del / Backspace — delete\n"
                + "Ctrl+Alt+1–4 — voice\n"
                + "Ctrl+Z / Ctrl+Y — undo / redo\n"
                + "Ctrl+C / Ctrl+V — copy / paste,  Ctrl+A — select all\n"
                + "Space — play / pause,  Ctrl+S — save,  Esc — leave input / deselect\n"
                + "Ctrl+= / Ctrl+- — zoom";
        new AlertDialog.Builder(this).setTitle(R.string.shortcuts).setMessage(s).setPositiveButton(R.string.ok, null).show();
    }

    // ------------------------------------------------------------------ hardware keyboard (MuseScore shortcuts)

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (st == null) return super.onKeyDown(keyCode, e);
        boolean ctrl = e.isCtrlPressed(), shift = e.isShiftPressed(), alt = e.isAltPressed();
        boolean handled = true;
        if (ctrl && alt && keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_4) {
            st.setVoice(keyCode - KeyEvent.KEYCODE_1);
        } else if (ctrl) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_Z:
                    if (shift) st.redo();
                    else st.undo();
                    break;
                case KeyEvent.KEYCODE_Y:
                    st.redo();
                    break;
                case KeyEvent.KEYCODE_C:
                    st.copy();
                    break;
                case KeyEvent.KEYCODE_V:
                    st.paste();
                    break;
                case KeyEvent.KEYCODE_A:
                    st.selectAll();
                    break;
                case KeyEvent.KEYCODE_S:
                    save(true);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    st.transpose(12);
                    previewSelected();
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    st.transpose(-12);
                    previewSelected();
                    break;
                case KeyEvent.KEYCODE_EQUALS:
                case KeyEvent.KEYCODE_PLUS:
                    view.setZoom(view.getZoom() * 1.25f);
                    break;
                case KeyEvent.KEYCODE_MINUS:
                    view.setZoom(view.getZoom() / 1.25f);
                    break;
                default:
                    handled = false;
            }
        } else {
            if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_G) {
                // KEYCODE_A..G are consecutive: A B C D E F G -> steps 5 6 0 1 2 3 4
                int[] steps = {5, 6, 0, 1, 2, 3, 4};
                int step = steps[keyCode - KeyEvent.KEYCODE_A];
                if (!st.inputMode) setInputMode(true);
                int p = st.enterLetter(step, shift);
                if (p >= 0) synth.play(p);
                followCursor();
            } else if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_7) {
                st.setDuration(Duration.N64 - (keyCode - KeyEvent.KEYCODE_1));
            } else {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_N:
                        setInputMode(!st.inputMode);
                        break;
                    case KeyEvent.KEYCODE_PERIOD:
                        st.toggleDot();
                        break;
                    case KeyEvent.KEYCODE_0:
                        if (st.inputMode) st.enterRest();
                        else st.deleteSelection();
                        followCursor();
                        break;
                    case KeyEvent.KEYCODE_PLUS:
                    case KeyEvent.KEYCODE_EQUALS:
                        if (st.inputMode) st.enterTie();
                        else st.toggleTie();
                        followCursor();
                        break;
                    case KeyEvent.KEYCODE_T:
                        st.triplet = !st.triplet;
                        break;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        st.transpose(1);
                        previewSelected();
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        st.transpose(-1);
                        previewSelected();
                        break;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        move(-1);
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        move(1);
                        break;
                    case KeyEvent.KEYCODE_DEL:
                    case KeyEvent.KEYCODE_FORWARD_DEL:
                        st.deleteSelection();
                        break;
                    case KeyEvent.KEYCODE_SPACE:
                        togglePlay();
                        break;
                    case KeyEvent.KEYCODE_ESCAPE:
                        if (st.inputMode) setInputMode(false);
                        else st.clearSelection();
                        break;
                    default:
                        handled = false;
                }
            }
        }
        if (handled) {
            refresh();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    // ------------------------------------------------------------------ back navigation (predictive back aware)

    private boolean wantsBack() {
        return st != null && (paletteOpen() || st.inputMode || st.hasSelection());
    }

    private void handleBack() {
        if (paletteOpen()) closePalette();
        else if (st != null && st.inputMode) setInputMode(false);
        else if (st != null && st.hasSelection()) {
            st.clearSelection();
            refresh();
        }
    }

    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return;
        boolean want = wantsBack();
        if (want == backRegistered) return;
        OnBackInvokedDispatcher d = getOnBackInvokedDispatcher();
        if (backCallback == null) {
            backCallback = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    handleBack();
                    updateBackCallback();
                }
            };
        }
        if (want) d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) backCallback);
        else d.unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
        backRegistered = want;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (wantsBack()) handleBack();
        else super.onBackPressed();
    }
}
