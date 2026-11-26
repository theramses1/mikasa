package com.mikasa.casaos;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Explorador de carpetas de un disco de CasaOS: navegar, crear, renombrar, mover, copiar, borrar, subir y descargar. */
public class FilesActivity extends Activity {

    static final String EXTRA_PATH = "path";
    static final String EXTRA_TITLE = "title";
    private static final int REQ_UPLOAD = 10;
    private static final int REQ_PERM = 11;

    /** Portapapeles compartido entre discos: ruta cortada/copiada. */
    private static String clipPath;
    private static boolean clipMove;

    private CasaApi api;
    private String startPath, path;
    private boolean showHidden;
    private final List<CasaApi.FileItem> items = new ArrayList<CasaApi.FileItem>();
    private Adapter adapter;
    private TextView pathView, status;
    private ProgressBar spinner;
    private LinearLayout clipBar;
    private TextView clipText;
    private CasaApi.FileItem pendingDownload;
    private int loadGen;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        api = new CasaApi(new Prefs(this));
        startPath = getIntent().getStringExtra(EXTRA_PATH);
        if (startPath == null) startPath = "/DATA";
        path = b != null ? b.getString("path", startPath) : startPath;
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        setTitle(title != null ? title : "Archivos");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        int p = Ui.dp(this, 12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        pathView = new TextView(this);
        pathView.setPadding(Ui.dp(this, 16), p, Ui.dp(this, 16), p);
        pathView.setTextColor(getResources().getColor(R.color.primary_dark));
        pathView.setTypeface(Typeface.MONOSPACE);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.START);
        pathView.setBackgroundColor(0xFFE3ECFB);
        root.addView(pathView);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER);
        header.setPadding(p, 0, p, 0);
        spinner = new ProgressBar(this);
        header.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        status = new TextView(this);
        status.setTextColor(getResources().getColor(R.color.text_muted));
        status.setPadding(p, p / 2, p, p / 2);
        status.setGravity(Gravity.CENTER);
        header.addView(status);
        root.addView(header);

        ListView list = new ListView(this);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> a, View v, int pos, long id) {
                if (hasUpRow() && pos == 0) { goUp(); return; }
                CasaApi.FileItem f = item(pos);
                if (f.dir) navigate(f.path);
                else showActions(f);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> a, View v, int pos, long id) {
                if (hasUpRow() && pos == 0) return false;
                showActions(item(pos));
                return true;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Barra de "pegar" cuando hay algo cortado/copiado
        clipBar = new LinearLayout(this);
        clipBar.setGravity(Gravity.CENTER_VERTICAL);
        clipBar.setBackgroundColor(0xFF1F2430);
        clipBar.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 4), 0);
        clipText = new TextView(this);
        clipText.setTextColor(0xFFFFFFFF);
        clipText.setSingleLine(true);
        clipText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        clipBar.addView(clipText, new LinearLayout.LayoutParams(0, -2, 1f));
        Button cancel = barButton("Cancelar");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clipPath = null; updateClipBar(); }
        });
        Button paste = barButton("Pegar aquí");
        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { paste(); }
        });
        clipBar.addView(cancel);
        clipBar.addView(paste);
        root.addView(clipBar, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        load();
    }

    @Override protected void onResume() {
        super.onResume();
        updateClipBar();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("path", path);
    }

    private Button barButton(String text) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setTextColor(0xFF8AB4F8);
        return b;
    }

    // ------------------------------------------------------------ navegación

    private boolean hasUpRow() { return !"/".equals(path); }

    private CasaApi.FileItem item(int pos) { return items.get(hasUpRow() ? pos - 1 : pos); }

    private void navigate(String p) {
        path = p;
        load();
    }

    private void goUp() { navigate(CasaApi.parent(path)); }

    @Override public void onBackPressed() {
        // Atrás sube de carpeta hasta llegar a la raíz del disco.
        if (!path.equals(startPath) && path.startsWith(startPath)) goUp();
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ menú

    @Override public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, "Nueva carpeta").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        m.add(0, 2, 1, "Subir archivo");
        m.add(0, 3, 2, R.string.refresh);
        m.add(0, 4, 3, "Mostrar ocultos").setCheckable(true);
        return true;
    }

    @Override public boolean onPrepareOptionsMenu(Menu m) {
        m.findItem(4).setChecked(showHidden);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem it) {
        switch (it.getItemId()) {
            case android.R.id.home: finish(); return true;
            case 1: askName("Nueva carpeta", "", "Crear", new NameCallback() {
                        @Override public void onName(final String name) {
                            run("Creando carpeta…", new Task() {
                                @Override public void exec() throws Exception {
                                    api.mkdir(CasaApi.join(path, name));
                                }
                            });
                        }
                    });
                    return true;
            case 2: pickUpload(); return true;
            case 3: load(); return true;
            case 4: showHidden = !showHidden; load(); return true;
        }
        return super.onOptionsItemSelected(it);
    }

    // ----------------------------------------------------------------- datos

    private void load() {
        final int gen = ++loadGen;
        final String target = path;
        pathView.setText(path);
        spinner.setVisibility(View.VISIBLE);
        status.setText("Cargando…");
        new Thread(new Runnable() {
            @Override public void run() {
                List<CasaApi.FileItem> res = null;
                String err = null;
                try { res = api.listDir(target); } catch (Exception e) { err = Ui.describe(e); }
                final List<CasaApi.FileItem> fRes = res;
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (gen != loadGen || isFinishing()) return;
                        spinner.setVisibility(View.GONE);
                        items.clear();
                        if (fErr != null) {
                            status.setText("Error: " + fErr);
                            adapter.notifyDataSetChanged();
                            return;
                        }
                        for (CasaApi.FileItem f : fRes) {
                            if (!showHidden && f.name.startsWith(".")) continue;
                            items.add(f);
                        }
                        Collections.sort(items, new Comparator<CasaApi.FileItem>() {
                            @Override public int compare(CasaApi.FileItem a, CasaApi.FileItem b) {
                                if (a.dir != b.dir) return a.dir ? -1 : 1;
                                return a.name.compareToIgnoreCase(b.name);
                            }
                        });
                        int dirs = 0;
                        for (CasaApi.FileItem f : items) if (f.dir) dirs++;
                        status.setText(items.isEmpty() ? "Carpeta vacía"
                                : dirs + " carpetas · " + (items.size() - dirs) + " archivos");
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        }).start();
    }

    // -------------------------------------------------------------- acciones

    private void showActions(final CasaApi.FileItem f) {
        final List<String> labels = new ArrayList<String>();
        if (f.dir) labels.add("Abrir carpeta");
        else { labels.add("Abrir"); labels.add("Descargar al móvil"); }
        labels.add("Renombrar");
        labels.add("Copiar");
        labels.add("Mover");
        labels.add("Eliminar");
        labels.add("Propiedades");
        new AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String a = labels.get(which);
                        if (a.equals("Abrir carpeta")) navigate(f.path);
                        else if (a.equals("Abrir")) openFile(f);
                        else if (a.startsWith("Descargar")) download(f);
                        else if (a.equals("Renombrar")) rename(f);
                        else if (a.equals("Copiar")) setClip(f, false);
                        else if (a.equals("Mover")) setClip(f, true);
                        else if (a.equals("Eliminar")) confirmDelete(f);
                        else if (a.equals("Propiedades")) properties(f);
                    }
                })
                .show();
    }

    private void rename(final CasaApi.FileItem f) {
        askName("Renombrar", f.name, "Renombrar", new NameCallback() {
            @Override public void onName(final String name) {
                if (name.equals(f.name)) return;
                run("Renombrando…", new Task() {
                    @Override public void exec() throws Exception {
                        api.rename(f.path, CasaApi.join(CasaApi.parent(f.path), name));
                    }
                });
            }
        });
    }

    private void confirmDelete(final CasaApi.FileItem f) {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar")
                .setMessage("¿Eliminar " + (f.dir ? "la carpeta" : "el archivo") + " «" + f.name + "»"
                        + (f.dir ? " y todo su contenido" : "") + "?\nEsta acción no se puede deshacer.")
                .setPositiveButton("Eliminar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        run("Eliminando…", new Task() {
                            @Override public void exec() throws Exception {
                                List<String> l = new ArrayList<String>();
                                l.add(f.path);
                                api.delete(l);
                            }
                        });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void properties(CasaApi.FileItem f) {
        String msg = "Ruta: " + f.path + "\nTipo: " + (f.dir ? "Carpeta" : "Archivo");
        if (!f.dir) msg += "\nTamaño: " + Ui.size(f.size);
        if (f.date != null && f.date.length() >= 16) msg += "\nModificado: " + f.date.substring(0, 16).replace('T', ' ');
        new AlertDialog.Builder(this).setTitle(f.name).setMessage(msg)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private void setClip(CasaApi.FileItem f, boolean move) {
        clipPath = f.path;
        clipMove = move;
        updateClipBar();
        Toast.makeText(this, "Ve a la carpeta de destino (en cualquier disco) y pulsa «Pegar aquí»",
                Toast.LENGTH_LONG).show();
    }

    private void updateClipBar() {
        if (clipBar == null) return;
        if (clipPath == null) { clipBar.setVisibility(View.GONE); return; }
        clipBar.setVisibility(View.VISIBLE);
        clipText.setText((clipMove ? "Mover: " : "Copiar: ") + clipPath.substring(clipPath.lastIndexOf('/') + 1));
    }

    private void paste() {
        final String src = clipPath;
        final boolean move = clipMove;
        if (src == null) return;
        if (CasaApi.parent(src).equals(path)) {
            Toast.makeText(this, "El origen y el destino son la misma carpeta", Toast.LENGTH_SHORT).show();
            return;
        }
        if (path.equals(src) || path.startsWith(src + "/")) {
            Toast.makeText(this, "No se puede pegar una carpeta dentro de sí misma", Toast.LENGTH_SHORT).show();
            return;
        }
        clipPath = null;
        updateClipBar();
        run(move ? "Moviendo…" : "Copiando…", new Task() {
            @Override public void exec() throws Exception {
                List<String> l = new ArrayList<String>();
                l.add(src);
                api.operate(move ? "move" : "copy", l, path);
                Thread.sleep(1500); // la tarea corre en el servidor; dar tiempo antes de recargar
            }
        });
        Toast.makeText(this, "La operación continúa en CasaOS; actualiza si aún no aparece",
                Toast.LENGTH_LONG).show();
    }

    private void openFile(final CasaApi.FileItem f) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String url = api.downloadUrl(f.path);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            String mime = android.webkit.MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(ext(f.name));
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            if (mime != null) i.setDataAndType(Uri.parse(url), mime);
                            else i.setData(Uri.parse(url));
                            try { startActivity(i); }
                            catch (Exception e) {
                                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                                catch (Exception e2) { toast("No hay ninguna app para abrir este archivo"); }
                            }
                        }
                    });
                } catch (Exception e) { toast("Error: " + Ui.describe(e)); }
            }
        }).start();
    }

    private void download(final CasaApi.FileItem f) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = f;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String url = api.downloadUrl(f.path);
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                    r.addRequestHeader("Authorization", api.token());
                    r.setTitle(f.name);
                    r.setDescription("Desde CasaOS");
                    r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, f.name);
                    ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                    toast("Descargando «" + f.name + "» en Descargas");
                } catch (Exception e) { toast("Error: " + Ui.describe(e)); }
            }
        }).start();
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_PERM && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED && pendingDownload != null) {
            download(pendingDownload);
        }
        pendingDownload = null;
    }

    // ---------------------------------------------------------------- subida

    private void pickUpload() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(Intent.createChooser(i, "Elige un archivo"), REQ_UPLOAD); }
        catch (Exception e) { toast("No hay selector de archivos disponible"); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_UPLOAD || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        String name = "archivo";
        long size = -1;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && c.getString(ni) != null) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }

        for (CasaApi.FileItem f : items) {
            if (f.name.equals(name)) {
                toast("Ya existe «" + name + "» en esta carpeta. Renómbralo o bórralo primero.");
                return;
            }
        }
        final String fName = name;
        final long fSize = size;
        final String dir = path;
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("Subiendo " + name);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setIndeterminate(fSize <= 0);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    api.upload(dir, fName, in, fSize, new CasaApi.Progress() {
                        long last;
                        @Override public void onProgress(final long done, final long total) {
                            long now = System.currentTimeMillis();
                            if (total <= 0 || now - last < 200) return;
                            last = now;
                            runOnUiThread(new Runnable() {
                                @Override public void run() { pd.setProgress((int) (done * 100 / total)); }
                            });
                        }
                    });
                } catch (Exception e) {
                    err = Ui.describe(e);
                } finally {
                    try { if (in != null) in.close(); } catch (Exception ignored) { }
                }
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        toast(fErr == null ? "«" + fName + "» subido" : "Error al subir: " + fErr);
                        load();
                    }
                });
            }
        }).start();
    }

    // --------------------------------------------------------------- helpers

    private interface Task { void exec() throws Exception; }
    private interface NameCallback { void onName(String name); }

    /** Ejecuta una operación en segundo plano con diálogo de progreso y recarga la carpeta. */
    private void run(String label, final Task t) {
        final ProgressDialog pd = ProgressDialog.show(this, null, label, true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try { t.exec(); } catch (Exception e) { err = Ui.describe(e); }
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        if (fErr != null) toast("Error: " + fErr);
                        load();
                    }
                });
            }
        }).start();
    }

    private void askName(String title, String initial, String ok, final NameCallback cb) {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(initial);
        int dot = initial.lastIndexOf('.');
        e.setSelection(0, dot > 0 ? dot : initial.length());
        LinearLayout box = new LinearLayout(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String n = e.getText().toString().trim();
                        if (n.length() == 0 || n.contains("/") || n.equals(".") || n.equals("..")) {
                            e.setError("Nombre no válido");
                            return;
                        }
                        d.dismiss();
                        cb.onName(n);
                    }
                });
            }
        });
        d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() { Toast.makeText(FilesActivity.this, msg, Toast.LENGTH_LONG).show(); }
        });
    }

    // --------------------------------------------------------------- adapter

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size() + (hasUpRow() ? 1 : 0); }
        @Override public Object getItem(int i) { return i; }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = build();
            ImageView icon = (ImageView) v.findViewWithTag("icon");
            TextView name = (TextView) v.findViewWithTag("name");
            TextView meta = (TextView) v.findViewWithTag("meta");
            if (hasUpRow() && pos == 0) {
                icon.setImageResource(R.drawable.ic_up);
                name.setText("..");
                meta.setText("Subir a " + CasaApi.parent(path));
                return v;
            }
            CasaApi.FileItem f = item(pos);
            icon.setImageResource(f.dir ? R.drawable.ic_folder : R.drawable.ic_file);
            name.setText(f.name);
            String m = f.dir ? "Carpeta" : Ui.size(f.size);
            if (f.date != null && f.date.length() >= 10) m += " · " + f.date.substring(0, 10);
            meta.setText(m);
            return v;
        }

        private View build() {
            Activity c = FilesActivity.this;
            int p = Ui.dp(c, 12);
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c, 16), p, Ui.dp(c, 16), p);
            ImageView icon = new ImageView(c);
            icon.setTag("icon");
            row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(Ui.dp(c, 14), 0, 0, 0);
            TextView name = new TextView(c);
            name.setTag("name");
            name.setTextSize(16);
            name.setTextColor(getResources().getColor(R.color.text));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            texts.addView(name);
            TextView meta = new TextView(c);
            meta.setTag("meta");
            meta.setTextSize(12);
            meta.setTextColor(getResources().getColor(R.color.text_muted));
            texts.addView(meta);
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            return row;
        }
    }
}
