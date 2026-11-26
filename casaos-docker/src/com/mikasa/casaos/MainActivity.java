package com.mikasa.casaos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Lista de apps/contenedores Docker de CasaOS con controles de inicio/parada. */
public class MainActivity extends Activity {

    private static final int REQ_SETUP = 1;

    private Prefs prefs;
    private CasaApi api;
    private final List<CasaApi.App> apps = new ArrayList<CasaApi.App>();
    private final Map<String, Bitmap> icons = new HashMap<String, Bitmap>();
    private final Map<String, Boolean> busy = new HashMap<String, Boolean>();
    private Adapter adapter;
    private TextView status;
    private ProgressBar spinner;
    private Button dashboardBtn;
    private int loadGen = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        api = new CasaApi(prefs);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Ui.dp(this, 16);
        header.setPadding(p, p / 2, p, 0);
        Button disksBtn = new Button(this);
        disksBtn.setText("Discos y archivos");
        disksBtn.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_disk, 0, 0, 0);
        disksBtn.setCompoundDrawablePadding(Ui.dp(this, 8));
        disksBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openDisks(); }
        });
        header.addView(disksBtn, new LinearLayout.LayoutParams(-1, -2));
        spinner = new ProgressBar(this);
        header.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(getResources().getColor(R.color.text_muted));
        status.setPadding(0, p / 2, 0, p / 2);
        header.addView(status);
        dashboardBtn = new Button(this);
        dashboardBtn.setText(R.string.open_dashboard);
        dashboardBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openDashboard(); }
        });
        header.addView(dashboardBtn);
        root.addView(header);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setPadding(p / 2, 0, p / 2, p);
        list.setClipToPadding(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);

        if (!prefs.isConfigured()) {
            startActivityForResult(new Intent(this, SetupActivity.class), REQ_SETUP);
        } else {
            refresh();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_SETUP) {
            if (prefs.isConfigured()) refresh();
            else finish();
        }
    }

    // ------------------------------------------------------------------ menu

    @Override public boolean onCreateOptionsMenu(Menu m) {
        MenuItem r = m.add(0, 1, 0, R.string.refresh);
        r.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        m.add(0, 4, 1, "Discos y archivos");
        m.add(0, 2, 2, R.string.open_dashboard);
        m.add(0, 3, 3, R.string.settings);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1: refresh(); return true;
            case 2: openDashboard(); return true;
            case 4: openDisks(); return true;
            case 3: startActivityForResult(new Intent(this, SetupActivity.class), REQ_SETUP); return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------ data

    private void refresh() {
        final int gen = ++loadGen;
        spinner.setVisibility(View.VISIBLE);
        status.setText(R.string.loading);
        status.setVisibility(View.VISIBLE);
        dashboardBtn.setVisibility(View.GONE);
        new Thread(new Runnable() {
            @Override public void run() {
                List<CasaApi.App> result = null;
                String err = null;
                try {
                    result = api.listApps();
                } catch (Exception e) {
                    err = Ui.describe(e);
                }
                final List<CasaApi.App> fRes = result;
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (gen != loadGen || isFinishing()) return;
                        spinner.setVisibility(View.GONE);
                        if (fErr != null) {
                            status.setText("No se pudo leer la lista de contenedores:\n" + fErr
                                    + "\n\nPuedes abrir el panel web de CasaOS directamente.");
                            dashboardBtn.setVisibility(View.VISIBLE);
                            return;
                        }
                        apps.clear();
                        apps.addAll(fRes);
                        adapter.notifyDataSetChanged();
                        int running = 0;
                        for (CasaApi.App a : apps) if (a.isRunning()) running++;
                        if (apps.isEmpty()) {
                            status.setText(R.string.empty);
                            dashboardBtn.setVisibility(View.VISIBLE);
                        } else {
                            status.setText(apps.size() + " apps Docker · " + running + " en ejecución");
                        }
                        loadIcons();
                    }
                });
            }
        }).start();
    }

    private void loadIcons() {
        final List<CasaApi.App> snapshot = new ArrayList<CasaApi.App>(apps);
        new Thread(new Runnable() {
            @Override public void run() {
                for (final CasaApi.App a : snapshot) {
                    if (icons.containsKey(a.icon)) continue;
                    final Bitmap bmp = api.loadIcon(a.icon);
                    if (bmp == null) continue;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            icons.put(a.icon, bmp);
                            adapter.notifyDataSetChanged();
                        }
                    });
                }
            }
        }).start();
    }

    private void changeStatus(final CasaApi.App app, final String action) {
        busy.put(app.id, true);
        adapter.notifyDataSetChanged();
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    if ("restart".equals(action)) api.restart(app);
                    else api.setStatus(app, action);
                } catch (Exception e) {
                    err = Ui.describe(e);
                }
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        busy.remove(app.id);
                        if (fErr != null) {
                            Toast.makeText(MainActivity.this, app.title + ": " + fErr,
                                    Toast.LENGTH_LONG).show();
                        }
                        refresh();
                    }
                });
            }
        }).start();
    }

    private void confirmStop(final CasaApi.App app) {
        new AlertDialog.Builder(this)
                .setTitle(app.title)
                .setMessage("¿Detener este contenedor?")
                .setPositiveButton(R.string.stop, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { changeStatus(app, "stopped"); }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openDisks() {
        startActivity(new Intent(this, DisksActivity.class));
    }

    private void openDashboard() {
        openWeb(prefs.baseUrl(), getString(R.string.open_dashboard));
    }

    private void openWeb(String url, String title) {
        Intent i = new Intent(this, WebActivity.class);
        i.putExtra(WebActivity.EXTRA_URL, url);
        i.putExtra(WebActivity.EXTRA_TITLE, title);
        startActivity(i);
    }

    // --------------------------------------------------------------- adapter

    private int statusColor(CasaApi.App a) {
        if (a.isRunning()) return getResources().getColor(R.color.running);
        String s = a.status.toLowerCase();
        if (s.contains("exit") || s.contains("stop") || s.contains("dead")) {
            return getResources().getColor(R.color.stopped);
        }
        return getResources().getColor(R.color.other);
    }

    private static String statusLabel(String s) {
        String l = s.toLowerCase();
        if (l.equals("running")) return "En ejecución";
        if (l.contains("exit") || l.contains("stop")) return "Detenido";
        if (l.contains("restart")) return "Reiniciando";
        if (l.contains("pause")) return "En pausa";
        if (l.contains("creat")) return "Creado";
        return s;
    }

    private static final class Holder {
        ImageView icon;
        TextView title, subtitle;
        View dot;
        Button toggle, restart, open;
        ProgressBar busy;
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int i) { return apps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View v, ViewGroup parent) {
            Holder h;
            if (v == null) {
                h = new Holder();
                v = buildRow(h);
                v.setTag(h);
            } else {
                h = (Holder) v.getTag();
            }
            final CasaApi.App a = apps.get(pos);

            h.title.setText(a.title);
            String sub = statusLabel(a.status);
            if (!TextUtils.isEmpty(a.image)) sub += " · " + a.image;
            h.subtitle.setText(sub);
            ((GradientDrawable) h.dot.getBackground()).setColor(statusColor(a));

            Bitmap bmp = icons.get(a.icon);
            if (bmp != null) h.icon.setImageBitmap(bmp);
            else h.icon.setImageResource(R.drawable.ic_launcher);

            boolean isBusy = busy.containsKey(a.id);
            h.busy.setVisibility(isBusy ? View.VISIBLE : View.GONE);

            boolean ctl = a.controllable() && !isBusy;
            h.toggle.setVisibility(a.controllable() ? View.VISIBLE : View.GONE);
            h.restart.setVisibility(a.controllable() && a.isRunning() ? View.VISIBLE : View.GONE);
            h.toggle.setEnabled(ctl);
            h.restart.setEnabled(ctl);
            h.toggle.setText(a.isRunning() ? R.string.stop : R.string.start);
            h.toggle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) {
                    if (a.isRunning()) confirmStop(a);
                    else changeStatus(a, "running");
                }
            });
            h.restart.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) { changeStatus(a, "restart"); }
            });

            h.open.setVisibility(a.url != null ? View.VISIBLE : View.GONE);
            h.open.setEnabled(a.isRunning());
            h.open.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) { openWeb(a.url, a.title); }
            });
            return v;
        }

        private View buildRow(Holder h) {
            int p = Ui.dp(MainActivity.this, 12);
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.card_bg);
            card.setElevation(Ui.dp(MainActivity.this, 2));
            card.setPadding(p, p, p, Ui.dp(MainActivity.this, 4));

            LinearLayout top = new LinearLayout(MainActivity.this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            h.icon = new ImageView(MainActivity.this);
            h.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int is = Ui.dp(MainActivity.this, 44);
            top.addView(h.icon, new LinearLayout.LayoutParams(is, is));

            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(p, 0, p, 0);
            h.title = new TextView(MainActivity.this);
            h.title.setTextSize(17);
            h.title.setTypeface(Typeface.DEFAULT_BOLD);
            h.title.setTextColor(getResources().getColor(R.color.text));
            h.title.setSingleLine(true);
            h.title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(h.title);

            LinearLayout statusRow = new LinearLayout(MainActivity.this);
            statusRow.setGravity(Gravity.CENTER_VERTICAL);
            h.dot = new View(MainActivity.this);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(Color.GRAY);
            h.dot.setBackground(dot);
            int ds = Ui.dp(MainActivity.this, 9);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ds, ds);
            dlp.rightMargin = Ui.dp(MainActivity.this, 6);
            statusRow.addView(h.dot, dlp);
            h.subtitle = new TextView(MainActivity.this);
            h.subtitle.setTextSize(13);
            h.subtitle.setTextColor(getResources().getColor(R.color.text_muted));
            h.subtitle.setSingleLine(true);
            h.subtitle.setEllipsize(TextUtils.TruncateAt.END);
            statusRow.addView(h.subtitle);
            texts.addView(statusRow);
            top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

            h.busy = new ProgressBar(MainActivity.this);
            int bs = Ui.dp(MainActivity.this, 24);
            top.addView(h.busy, new LinearLayout.LayoutParams(bs, bs));
            card.addView(top);

            LinearLayout actions = new LinearLayout(MainActivity.this);
            actions.setGravity(Gravity.END);
            h.restart = flatButton(R.string.restart);
            h.toggle = flatButton(R.string.start);
            h.open = flatButton(R.string.open);
            actions.addView(h.restart);
            actions.addView(h.toggle);
            actions.addView(h.open);
            card.addView(actions);

            LinearLayout wrap = new LinearLayout(MainActivity.this);
            int m = Ui.dp(MainActivity.this, 6);
            wrap.setPadding(m, m, m, m);
            wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));
            return wrap;
        }

        private Button flatButton(int text) {
            Button b = new Button(MainActivity.this, null, android.R.attr.borderlessButtonStyle);
            b.setText(text);
            b.setTextColor(getResources().getColorStateList(R.color.primary));
            return b;
        }
    }
}
