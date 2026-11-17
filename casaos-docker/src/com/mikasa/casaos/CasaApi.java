package com.mikasa.casaos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Cliente mínimo de la API HTTP de CasaOS (v0.4.x):
 *  - POST /v1/users/login                         -> token
 *  - GET  /v2/app_management/web/appgrid          -> apps / contenedores Docker
 *  - PUT  /v2/app_management/compose/{id}/status  -> "running" | "stopped"
 * Todas las llamadas son bloqueantes: ejecutar fuera del hilo principal.
 */
final class CasaApi {

    static final class ApiException extends Exception {
        final int code;
        ApiException(int code, String msg) { super(msg); this.code = code; }
    }

    static final class App {
        String id;        // nombre del proyecto compose / contenedor
        String title;
        String status;    // running, exited, stopped, ...
        String type;      // v2app, v1app, container
        String icon;
        String url;       // URL de la interfaz web de la app (o null)
        String image;

        boolean isRunning() { return "running".equalsIgnoreCase(status); }
        boolean controllable() { return "v2app".equals(type); }
    }

    private final Prefs prefs;

    CasaApi(Prefs prefs) { this.prefs = prefs; }

    // ---------------------------------------------------------------- auth

    void login() throws Exception {
        JSONObject body = new JSONObject();
        body.put("username", prefs.user());
        body.put("password", prefs.pass());
        String res = request("POST", "/v1/users/login", body.toString(), false);
        JSONObject data = new JSONObject(res).optJSONObject("data");
        String token = null;
        if (data != null) {
            JSONObject t = data.optJSONObject("token");
            if (t != null) token = t.optString("access_token", null);
        }
        if (token == null || token.length() == 0) {
            throw new ApiException(401, "Usuario o contraseña incorrectos");
        }
        prefs.setToken(token);
    }

    /** Hace la petición autenticada; si el token caducó, vuelve a iniciar sesión una vez. */
    private String authed(String method, String path, String body) throws Exception {
        if (prefs.token().length() == 0) login();
        try {
            return request(method, path, body, true);
        } catch (ApiException e) {
            if (e.code == 401 || e.code == 403) {
                login();
                return request(method, path, body, true);
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------- apps

    List<App> listApps() throws Exception {
        String res = authed("GET", "/v2/app_management/web/appgrid", null);
        JSONArray arr = new JSONObject(res).optJSONArray("data");
        List<App> out = new ArrayList<App>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            App a = new App();
            a.type = o.optString("app_type", "");
            a.id = o.optString("name", o.optString("id", ""));
            a.status = o.optString("status", "unknown");
            a.icon = o.optString("icon", "");
            a.image = o.optString("image", "");
            a.title = pickTitle(o.opt("title"), a.id);
            a.url = buildAppUrl(o);
            out.add(a);
        }
        return out;
    }

    void setStatus(App app, String status) throws Exception {
        String path = "/v2/app_management/compose/" + Uri.encode(app.id) + "/status";
        authed("PUT", path, JSONObject.quote(status));
    }

    void restart(App app) throws Exception {
        setStatus(app, "stopped");
        setStatus(app, "running");
    }

    Bitmap loadIcon(String url) {
        if (url == null || url.length() == 0) return null;
        try {
            if (url.startsWith("/")) url = prefs.baseUrl() + url;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            InputStream in = c.getInputStream();
            try { return BitmapFactory.decodeStream(in); } finally { in.close(); c.disconnect(); }
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------- helpers

    private static String pickTitle(Object t, String fallback) {
        if (t instanceof JSONObject) {
            JSONObject o = (JSONObject) t;
            String[] keys = {"es_es", "es_ES", "en_us", "en_US", "custom"};
            for (String k : keys) {
                String v = o.optString(k, "");
                if (v.length() > 0) return v;
            }
            java.util.Iterator<String> it = o.keys();
            if (it.hasNext()) {
                String v = o.optString(it.next(), "");
                if (v.length() > 0) return v;
            }
        } else if (t instanceof String && ((String) t).length() > 0) {
            return (String) t;
        }
        return fallback;
    }

    private String buildAppUrl(JSONObject o) {
        String port = o.optString("port", "");
        if (port.length() == 0 || "0".equals(port)) return null;
        String scheme = o.optString("scheme", "http");
        if (scheme.length() == 0) scheme = "http";
        String host = o.optString("hostname", "");
        if (host.length() == 0) {
            Uri base = Uri.parse(prefs.baseUrl());
            host = base.getHost() == null ? "" : base.getHost();
        }
        String index = o.optString("index", "/");
        if (!index.startsWith("/")) index = "/" + index;
        return scheme + "://" + host + ":" + port + index;
    }

    private String request(String method, String path, String body, boolean auth) throws Exception {
        URL url = new URL(prefs.baseUrl() + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Accept", "application/json");
            if (auth) c.setRequestProperty("Authorization", prefs.token());
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (code >= 400) {
                String msg = "HTTP " + code;
                try {
                    String m = new JSONObject(text).optString("message", "");
                    if (m.length() > 0) msg += ": " + m;
                } catch (Exception ignored) { }
                throw new ApiException(code, msg);
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }
}
