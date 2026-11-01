package io.github.vlesk.stave.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import io.github.vlesk.stave.R;
import io.github.vlesk.stave.engrave.Sym;
import io.github.vlesk.stave.io.Mscz;
import io.github.vlesk.stave.io.ScoreStore;
import io.github.vlesk.stave.model.Score;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Score library: thumbnail cards of all scores, import and "New score". */
public final class HomeActivity extends Activity {
    private static final int REQ_IMPORT = 1;

    private Theme t;
    private ScoreStore store;
    private final List<ScoreStore.Entry> entries = new ArrayList<ScoreStore.Entry>();
    private Adapter adapter;
    private GridView grid;
    private View empty;
    private TextView noResults, sortRecent, sortTitle;
    private android.widget.EditText query;
    private View clearQuery;
    private boolean sortByTitle;
    private Ui.Snackbar snack;
    private final List<ScoreStore.Entry> all = new ArrayList<ScoreStore.Entry>();
    /** Ids hidden while their deletion can still be undone. */
    private final java.util.Set<String> pendingDelete = new java.util.HashSet<String>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        t = new Theme(this);
        store = new ScoreStore(getFilesDir());
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.surface);
        LinearLayout col = Ui.column(this);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = Ui.column(this);
        top.setPadding(t.dp(4), 0, t.dp(4), 0);
        LinearLayout bar = Ui.row(this);
        bar.setMinimumHeight(t.dp(64));
        bar.addView(Ui.spacer(this));
        bar.addView(Ui.icon(this, t, R.drawable.ic_file_open, getString(R.string.import_score), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImport();
            }
        }));
        bar.addView(Ui.icon(this, t, R.drawable.ic_info, getString(R.string.about), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                about();
            }
        }));
        top.addView(bar);
        TextView big = Ui.text(this, getString(R.string.home_title), 32, t.onSurface, false);
        big.setPadding(t.dp(16), 0, t.dp(16), t.dp(12));
        top.addView(big);
        // Material 3 search bar: a full-width pill that filters the library as you type
        LinearLayout search = Ui.row(this);
        search.setBackground(Ui.round(t.surfaceContainerHigh, t.dp(28)));
        search.setPadding(t.dp(16), 0, t.dp(8), 0);
        android.widget.ImageView si = new android.widget.ImageView(this);
        si.setImageResource(R.drawable.ic_search);
        si.setImageTintList(android.content.res.ColorStateList.valueOf(t.onSurfaceVariant));
        si.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        search.addView(si, Ui.lin(t.dp(24), t.dp(24)));
        query = new android.widget.EditText(this);
        query.setHint(R.string.search_scores);
        query.setBackground(null);
        query.setSingleLine(true);
        query.setTextSize(16);
        query.setTextColor(t.onSurface);
        query.setHintTextColor(t.onSurfaceVariant);
        query.setPadding(t.dp(12), 0, t.dp(8), 0);
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        query.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                applyFilter();
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
            }
        });
        search.addView(query, Ui.lin(0, t.dp(56), 1f));
        clearQuery = Ui.icon(this, t, R.drawable.ic_close, getString(R.string.cancel), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                query.setText("");
            }
        });
        clearQuery.setVisibility(View.GONE);
        search.addView(clearQuery);
        LinearLayout.LayoutParams slp = Ui.lin(-1, t.dp(56));
        slp.setMargins(t.dp(12), 0, t.dp(12), t.dp(8));
        top.addView(search, slp);
        // sort chips (filter chips, single select)
        LinearLayout sorts = Ui.row(this);
        sorts.setPadding(t.dp(16), 0, t.dp(16), t.dp(8));
        sortRecent = sortChip(getString(R.string.sort_recent), true);
        sortTitle = sortChip(getString(R.string.sort_title), false);
        sorts.addView(sortRecent);
        sorts.addView(sortTitle);
        top.addView(sorts);
        col.addView(top);

        FrameLayout content = new FrameLayout(this);
        grid = new GridView(this);
        grid.setVerticalSpacing(t.dp(12));
        grid.setHorizontalSpacing(t.dp(12));
        grid.setPadding(t.dp(16), 0, t.dp(16), t.dp(96));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        int cols = Math.max(2, getResources().getConfiguration().screenWidthDp / 200);
        grid.setNumColumns(cols);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        content.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        empty = buildEmpty();
        content.addView(empty, Ui.frame(-1, -2, Gravity.CENTER));
        noResults = Ui.text(this, "", 16, t.onSurfaceVariant, false);
        noResults.setGravity(Gravity.CENTER);
        noResults.setVisibility(View.GONE);
        content.addView(noResults, Ui.frame(-1, -2, Gravity.TOP));
        col.addView(content, Ui.lin(-1, 0, 1f));

        // extended FAB
        LinearLayout fab = Ui.row(this);
        fab.setPadding(t.dp(16), 0, t.dp(20), 0);
        fab.setBackground(Ui.rippleRound(t, t.primaryContainer, 16));
        fab.setElevation(t.dp(6));
        android.widget.ImageView ic = new android.widget.ImageView(this);
        ic.setImageResource(R.drawable.ic_add);
        ic.setImageTintList(android.content.res.ColorStateList.valueOf(t.onPrimaryContainer));
        fab.addView(ic);
        TextView fl = Ui.text(this, getString(R.string.new_score), 16, t.onPrimaryContainer, true);
        fl.setPadding(t.dp(12), 0, 0, 0);
        fab.addView(fl);
        fab.setClickable(true);
        fab.setContentDescription(getString(R.string.new_score));
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, NewScoreActivity.class));
            }
        });
        final FrameLayout fabHost = new FrameLayout(this);
        FrameLayout.LayoutParams flp = Ui.frame(-2, t.dp(56), Gravity.BOTTOM | Gravity.END);
        flp.setMargins(0, 0, t.dp(16), t.dp(16));
        fabHost.addView(fab, flp);
        root.addView(fabHost, new FrameLayout.LayoutParams(-1, -1));
        fabHost.setClickable(false);
        snack = new Ui.Snackbar(fabHost, t);
        snack.setBottomMargin(t.dp(72));

        setContentView(root);
        Ui.edgeToEdge(this, t, top, fabHost, col);
    }

    private View buildEmpty() {
        LinearLayout l = Ui.column(this);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(t.dp(32), 0, t.dp(32), t.dp(80));
        TextView glyph = new TextView(this);
        glyph.setTypeface(Theme.music(this));
        glyph.setText(Sym.str(Sym.gClef));
        glyph.setTextSize(72);
        glyph.setTextColor(t.primary);
        glyph.setGravity(Gravity.CENTER);
        glyph.setPadding(0, t.dp(40), 0, 0);
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        l.addView(glyph);
        TextView title = Ui.text(this, getString(R.string.empty_title), 22, t.onSurface, false);
        title.setGravity(Gravity.CENTER);
        l.addView(title);
        TextView body = Ui.text(this, getString(R.string.empty_body), 14, t.onSurfaceVariant, false);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, t.dp(8), 0, t.dp(16));
        l.addView(body);
        LinearLayout btns = Ui.row(this);
        btns.addView(Ui.button(this, t, getString(R.string.new_score), 0, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, NewScoreActivity.class));
            }
        }));
        View gap = new View(this);
        btns.addView(gap, Ui.lin(t.dp(12), 1));
        btns.addView(Ui.button(this, t, getString(R.string.import_score), 1, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImport();
            }
        }));
        l.addView(btns);
        return l;
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    private void reload() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<ScoreStore.Entry> list = store.list();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        all.clear();
                        all.addAll(list);
                        applyFilter();
                    }
                });
            }
        });
    }

    private TextView sortChip(String label, boolean on) {
        final TextView chip = Ui.text(this, label, 14, t.onSurfaceVariant, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(t.dp(16), 0, t.dp(16), 0);
        chip.setMinHeight(t.dp(32));
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sortByTitle = v == sortTitle;
                applyFilter();
            }
        });
        LinearLayout.LayoutParams lp = Ui.lin(-2, t.dp(32));
        lp.rightMargin = t.dp(8);
        chip.setLayoutParams(lp);
        styleChip(chip, on);
        return chip;
    }

    private void styleChip(TextView chip, boolean on) {
        chip.setBackground(on ? Ui.rippleRound(t, t.primaryContainer, 8) : Ui.ripple(t, Ui.roundStroke(0, t.outlineVariant, t.dp(1), t.dp(8)), true));
        chip.setTextColor(on ? t.onPrimaryContainer : t.onSurfaceVariant);
        chip.setSelected(on);
    }

    /** Applies search text, sort order and pending deletions to the visible list. */
    private void applyFilter() {
        String q = query.getText().toString().trim().toLowerCase();
        clearQuery.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
        styleChip(sortRecent, !sortByTitle);
        styleChip(sortTitle, sortByTitle);
        entries.clear();
        for (ScoreStore.Entry e : all) {
            if (pendingDelete.contains(e.id)) continue;
            if (!q.isEmpty()) {
                String hay = (e.title + " " + e.composer + " " + e.instruments).toLowerCase();
                if (!hay.contains(q)) continue;
            }
            entries.add(e);
        }
        if (sortByTitle) {
            java.util.Collections.sort(entries, new java.util.Comparator<ScoreStore.Entry>() {
                @Override
                public int compare(ScoreStore.Entry a, ScoreStore.Entry b) {
                    return a.title.compareToIgnoreCase(b.title);
                }
            });
        }
        adapter.notifyDataSetChanged();
        boolean libraryEmpty = all.size() == pendingDelete.size();
        empty.setVisibility(libraryEmpty ? View.VISIBLE : View.GONE);
        noResults.setText(getString(R.string.no_results, q));
        noResults.setPadding(0, t.dp(48), 0, 0);
        noResults.setVisibility(!libraryEmpty && entries.isEmpty() && !q.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Deletes with an Undo snackbar instead of a confirmation dialog (the deletion is reversible until it times out). */
    private void deleteWithUndo(final ScoreStore.Entry e) {
        pendingDelete.add(e.id);
        applyFilter();
        final Runnable commit = new Runnable() {
            @Override
            public void run() {
                if (pendingDelete.remove(e.id)) {
                    store.delete(e.id);
                    all.remove(e);
                    applyFilter();
                }
            }
        };
        handler.postDelayed(commit, 5500);
        snack.show(getString(R.string.score_deleted, e.title), getString(R.string.undo), new Runnable() {
            @Override
            public void run() {
                handler.removeCallbacks(commit);
                pendingDelete.remove(e.id);
                applyFilter();
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        snack.dismiss();
        // commit pending deletions when leaving the screen
        for (String id : new ArrayList<String>(pendingDelete)) store.delete(id);
        pendingDelete.clear();
    }

    private void open(String id) {
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_ID, id);
        startActivity(i);
    }

    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/x-musescore", "application/vnd.recordare.musicxml+xml",
                "application/vnd.recordare.musicxml", "application/xml", "text/xml", "application/octet-stream", "application/zip"});
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_IMPORT && res == RESULT_OK && data != null && data.getData() != null) {
            Intent i = new Intent(this, EditorActivity.class);
            i.setAction(Intent.ACTION_VIEW);
            i.setData(data.getData());
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        }
    }

    private void about() {
        String msg = "Stave is an unofficial Android port of MuseScore Studio's notation editor, rebuilt for touch.\n\n"
                + "• Opens and saves MuseScore .mscz/.mscx files and MusicXML\n"
                + "• Exports PDF, MusicXML and MIDI\n"
                + "• Music font: Leland, text font: Edwin (© MuseScore Ltd, SIL Open Font License)\n"
                + "• Icons: Material Symbols (Apache 2.0)\n\n"
                + "MuseScore is a trademark of Muse Group. This app is not affiliated with or endorsed by Muse Group.\n\n"
                + "Licensed under the GNU GPL v3.";
        new AlertDialog.Builder(this).setTitle(getString(R.string.app_name)).setMessage(msg).setPositiveButton(R.string.ok, null).show();
    }

    private void cardMenu(View anchor, final ScoreStore.Entry e) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, R.string.rename);
        m.add(0, 2, 0, R.string.duplicate);
        m.add(0, 3, 0, R.string.share);
        m.add(0, 4, 0, R.string.delete);
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        rename(e);
                        return true;
                    case 2:
                        io.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    store.duplicate(e.id, e.title + " (copy)");
                                } catch (Exception ignored) {
                                }
                                reload();
                            }
                        });
                        return true;
                    case 3:
                        shareEntry(e);
                        return true;
                    case 4:
                        deleteWithUndo(e);
                        return true;
                    default:
                        return false;
                }
            }
        });
        pm.show();
    }

    private void rename(final ScoreStore.Entry e) {
        LinearLayout body = Ui.column(this);
        body.setPadding(t.dp(24), t.dp(8), t.dp(24), 0);
        final EditText in = Ui.input(this, t, getString(R.string.title), e.title);
        body.addView(in, Ui.lin(-1, -2));
        new AlertDialog.Builder(this).setTitle(R.string.rename).setView(body)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final String name = in.getText().toString().trim();
                        if (name.isEmpty()) return;
                        io.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Score s = store.load(e.id);
                                    s.title = name;
                                    byte[] thumb = e.thumb.exists() ? Mscz.readAll(new FileInputStream(e.thumb)) : null;
                                    store.save(e.id, s, thumb);
                                } catch (Exception ignored) {
                                }
                                reload();
                            }
                        });
                    }
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void shareEntry(final ScoreStore.Entry e) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = ShareProvider.shareDir(HomeActivity.this);
                    String name = (e.title.isEmpty() ? "score" : e.title.replaceAll("[\\\\/:*?\"<>|]", "_")) + ".mscz";
                    final File f = new File(dir, name);
                    FileInputStream inS = new FileInputStream(e.file);
                    FileOutputStream out = new FileOutputStream(f);
                    out.write(Mscz.readAll(inS));
                    inS.close();
                    out.close();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Uri uri = ShareProvider.uriFor(f);
                            Intent send = new Intent(Intent.ACTION_SEND);
                            send.setType("application/x-musescore");
                            send.putExtra(Intent.EXTRA_STREAM, uri);
                            send.setClipData(ClipData.newRawUri(f.getName(), uri));
                            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(send, getString(R.string.share)));
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int i) {
            return entries.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            final ScoreStore.Entry e = entries.get(pos);
            LinearLayout card = Ui.column(HomeActivity.this);
            card.setBackground(Ui.rippleRound(t, t.surfaceContainer, 16));
            card.setClipToOutline(true);
            card.setPadding(0, 0, 0, t.dp(12));
            ImageView img = new ImageView(HomeActivity.this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundColor(0xFFFFFFFF);
            img.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (e.thumb.exists()) {
                Bitmap bm = BitmapFactory.decodeFile(e.thumb.getAbsolutePath());
                if (bm != null) img.setImageBitmap(bm);
            }
            final int radius = t.dp(16);
            img.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight() + radius, radius);
                }
            });
            img.setClipToOutline(true);
            int w = (parent.getWidth() - t.dp(32) - t.dp(12) * (Math.max(1, grid.getNumColumns()) - 1)) / Math.max(1, grid.getNumColumns());
            card.addView(img, Ui.lin(-1, Math.max(t.dp(90), (int) (w * 0.62f))));
            TextView title = Ui.text(HomeActivity.this, e.title, 16, t.onSurface, true);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(t.dp(12), t.dp(10), t.dp(12), 0);
            card.addView(title);
            String sub = e.composer != null && !e.composer.isEmpty() ? e.composer : e.instruments;
            TextView s1 = Ui.text(HomeActivity.this, sub == null ? "" : sub, 13, t.onSurfaceVariant, false);
            s1.setSingleLine(true);
            s1.setEllipsize(android.text.TextUtils.TruncateAt.END);
            s1.setPadding(t.dp(12), t.dp(2), t.dp(12), 0);
            card.addView(s1);
            TextView s2 = Ui.text(HomeActivity.this, getString(R.string.edited,
                    DateUtils.getRelativeTimeSpanString(e.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)), 12, t.onSurfaceVariant, false);
            s2.setPadding(t.dp(12), t.dp(2), t.dp(12), 0);
            card.addView(s2);
            card.setContentDescription(e.title + ". " + sub);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open(e.id);
                }
            });
            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    cardMenu(v, e);
                    return true;
                }
            });
            return card;
        }
    }
}
