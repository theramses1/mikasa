package com.mikasa.casaos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

/** Navegador integrado para el panel de CasaOS o la web de un contenedor. */
public class WebActivity extends Activity {

    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";

    private WebView web;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null) setTitle(title);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new FrameLayout.LayoutParams(-1, Ui.dp(this, 4)));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String u, Bitmap f) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override public void onReceivedSslError(WebView v, final SslErrorHandler h, SslError e) {
                // Certificados autofirmados son habituales en servidores caseros: preguntar.
                new AlertDialog.Builder(WebActivity.this)
                        .setTitle("Certificado no válido")
                        .setMessage("El certificado HTTPS de este servidor no es de confianza "
                                + "(probablemente autofirmado). ¿Continuar igualmente?")
                        .setPositiveButton("Continuar", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { h.proceed(); }
                        })
                        .setNegativeButton("Cancelar", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { h.cancel(); }
                        })
                        .setCancelable(false)
                        .show();
            }
        });

        if (b != null) web.restoreState(b);
        else if (url != null) web.loadUrl(url);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, "Recargar");
        m.add(0, 2, 0, "Abrir en el navegador");
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: finish(); return true;
            case 1: web.reload(); return true;
            case 2:
                String u = web.getUrl();
                if (u != null) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); }
                    catch (Exception ignored) { }
                }
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
