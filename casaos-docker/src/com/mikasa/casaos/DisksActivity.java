package com.mikasa.casaos;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Discos conectados a CasaOS (sistema, internos y USB) con su espacio usado. */
public class DisksActivity extends Activity {

    private CasaApi api;
    private final List<CasaApi.Disk> disks = new ArrayList<CasaApi.Disk>();
    private Adapter adapter;
    private TextView status;
    private ProgressBar spinner;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Discos");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        api = new CasaApi(new Prefs(this));

        int p = Ui.dp(this, 16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        header.setPadding(p, p / 2, p, 0);
        spinner = new ProgressBar(this);
        header.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(getResources().getColor(R.color.text_muted));
        status.setPadding(0, p / 2, 0, p / 2);
        header.addView(status);
        root.addView(header);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setPadding(p / 2, 0, p / 2, p);
        list.setClipToPadding(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> a, View v, int pos, long id) {
                CasaApi.Disk d = disks.get(pos);
                String start = "/".equals(d.mount) ? "/DATA" : d.mount;
                Intent i = new Intent(DisksActivity.this, FilesActivity.class);
                i.putExtra(FilesActivity.EXTRA_PATH, start);
                i.putExtra(FilesActivity.EXTRA_TITLE, d.name);
                startActivity(i);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        refresh();
    }

    @Override public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, R.string.refresh).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        if (item.getItemId() == 1) { refresh(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void refresh() {
        spinner.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        status.setText("Cargando discos…");
        new Thread(new Runnable() {
            @Override public void run() {
                List<CasaApi.Disk> res = null;
                String err = null;
                try { res = api.listDisks(); } catch (Exception e) { err = Ui.describe(e); }
                final List<CasaApi.Disk> fRes = res;
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        spinner.setVisibility(View.GONE);
                        if (fErr != null) {
                            status.setText("No se pudieron leer los discos:\n" + fErr);
                            return;
                        }
                        disks.clear();
                        disks.addAll(fRes);
                        adapter.notifyDataSetChanged();
                        status.setText(disks.size() + " ubicaciones · toca una para ver sus carpetas");
                    }
                });
            }
        }).start();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return disks.size(); }
        @Override public Object getItem(int i) { return disks.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = build();
            CasaApi.Disk d = disks.get(pos);
            TextView title = (TextView) v.findViewWithTag("title");
            TextView sub = (TextView) v.findViewWithTag("sub");
            TextView usage = (TextView) v.findViewWithTag("usage");
            ProgressBar bar = (ProgressBar) v.findViewWithTag("bar");

            title.setText(d.name);
            String s = d.kind + " · " + d.mount;
            if (d.fsType != null && d.fsType.length() > 0) s += " · " + d.fsType;
            sub.setText(s);
            if (d.size > 0 && d.avail >= 0) {
                long used = Math.max(0, d.size - d.avail);
                int pct = (int) Math.round(used * 100.0 / d.size);
                bar.setVisibility(View.VISIBLE);
                bar.setProgress(pct);
                usage.setVisibility(View.VISIBLE);
                usage.setText(Ui.size(used) + " usados de " + Ui.size(d.size)
                        + " · " + Ui.size(d.avail) + " libres (" + pct + "%)");
            } else {
                bar.setVisibility(View.GONE);
                usage.setVisibility(View.GONE);
            }
            return v;
        }

        private View build() {
            Activity c = DisksActivity.this;
            int p = Ui.dp(c, 14);
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackgroundResource(R.drawable.card_bg);
            card.setElevation(Ui.dp(c, 2));
            card.setPadding(p, p, p, p);

            ImageView icon = new ImageView(c);
            icon.setImageResource(R.drawable.ic_disk);
            card.addView(icon, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(p, 0, 0, 0);
            TextView title = new TextView(c);
            title.setTag("title");
            title.setTextSize(17);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(getResources().getColor(R.color.text));
            texts.addView(title);
            TextView sub = new TextView(c);
            sub.setTag("sub");
            sub.setTextSize(13);
            sub.setTextColor(getResources().getColor(R.color.text_muted));
            texts.addView(sub);
            ProgressBar bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
            bar.setTag("bar");
            bar.setMax(100);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 8));
            blp.topMargin = Ui.dp(c, 8);
            texts.addView(bar, blp);
            TextView usage = new TextView(c);
            usage.setTag("usage");
            usage.setTextSize(12);
            usage.setTextColor(getResources().getColor(R.color.text_muted));
            texts.addView(usage);
            card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

            LinearLayout wrap = new LinearLayout(c);
            int m = Ui.dp(c, 6);
            wrap.setPadding(m, m, m, m);
            wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));
            return wrap;
        }
    }
}
