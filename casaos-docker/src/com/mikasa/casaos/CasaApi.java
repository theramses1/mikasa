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

    // ------------------------------------------------------------- discos

    static final class Disk {
        String name;      // modelo / etiqueta
        String mount;     // punto de montaje
        String kind;      // Sistema, USB, Disco
        String fsType;
        long size;        // bytes (0 = desconocido)
        long avail;       // bytes (-1 = desconocido)
    }

    /** Discos y particiones montadas (internos + USB). */
    List<Disk> listDisks() throws Exception {
        java.util.LinkedHashMap<String, Disk> out = new java.util.LinkedHashMap<String, Disk>();
        Exception firstErr = null;

        // Discos internos: GET /v1/storage?system=show  ->  [{disk_name, type, children:[{mount_point,size,avail,label,type}]}]
        try {
            JSONArray arr = dataArray(authed("GET", "/v1/storage?system=show", null));
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject d = arr.optJSONObject(i);
                if (d == null) continue;
                String diskName = d.optString("disk_name", "");
                String tran = d.optString("type", "");
                JSONArray ch = d.optJSONArray("children");
                for (int j = 0; ch != null && j < ch.length(); j++) {
                    JSONObject c = ch.optJSONObject(j);
                    if (c == null) continue;
                    String mp = c.optString("mount_point", "");
                    if (mp.length() == 0 || out.containsKey(mp)) continue;
                    Disk k = new Disk();
                    k.mount = mp;
                    String label = c.optString("label", "");
                    k.name = label.length() > 0 ? label : (diskName.length() > 0 ? diskName : mp);
                    k.kind = "System".equalsIgnoreCase(diskName) || "/".equals(mp) ? "Sistema"
                            : "usb".equalsIgnoreCase(tran) ? "USB" : "Disco";
                    k.fsType = c.optString("type", "");
                    k.size = num(c.opt("size"), 0);
                    k.avail = num(c.opt("avail"), -1);
                    out.put(mp, k);
                }
            }
        } catch (Exception e) { firstErr = e; }

        // USB: GET /v1/disks/usb -> [{name, model, children:[{name,size,avail,mount_point}]}]
        try {
            JSONArray arr = dataArray(authed("GET", "/v1/disks/usb", null));
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject d = arr.optJSONObject(i);
                if (d == null) continue;
                String model = d.optString("model", d.optString("name", "USB"));
                JSONArray ch = d.optJSONArray("children");
                for (int j = 0; ch != null && j < ch.length(); j++) {
                    JSONObject c = ch.optJSONObject(j);
                    if (c == null) continue;
                    String mp = c.optString("mount_point", "");
                    if (mp.length() == 0) continue;
                    Disk k = out.containsKey(mp) ? out.get(mp) : new Disk();
                    k.mount = mp;
                    if (k.name == null) k.name = model.length() > 0 ? model : c.optString("name", mp);
                    k.kind = "USB";
                    if (k.size <= 0) k.size = num(c.opt("size"), 0);
                    if (k.avail < 0) k.avail = num(c.opt("avail"), -1);
                    out.put(mp, k);
                }
            }
        } catch (Exception e) { if (firstErr == null) firstErr = e; }

        if (out.isEmpty() && firstErr != null) throw firstErr;
        if (!out.containsKey("/DATA")) {
            // Carpeta de datos estándar de CasaOS: siempre accesible.
            Disk k = new Disk();
            k.mount = "/DATA";
            k.name = "DATA";
            k.kind = "CasaOS";
            k.avail = -1;
            out.put("/DATA", k);
        }
        return new ArrayList<Disk>(out.values());
    }

    // ----------------------------------------------------------- archivos

    static final class FileItem {
        String name, path;
        boolean dir;
        long size;
        String date;
    }

    List<FileItem> listDir(String path) throws Exception {
        String res = authed("GET", "/v1/folder?index=1&size=100000&path=" + Uri.encode(path), null);
        Object data = new JSONObject(res).opt("data");
        JSONArray arr = null;
        if (data instanceof JSONObject) arr = ((JSONObject) data).optJSONArray("content");
        else if (data instanceof JSONArray) arr = (JSONArray) data;
        List<FileItem> out = new ArrayList<FileItem>();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            FileItem f = new FileItem();
            f.name = o.optString("name", "");
            f.path = o.optString("path", join(path, f.name));
            f.dir = o.optBoolean("is_dir", false);
            f.size = o.optLong("size", 0);
            f.date = o.optString("modified", o.optString("date", ""));
            out.add(f);
        }
        return out;
    }

    void mkdir(String path) throws Exception {
        JSONObject b = new JSONObject();
        b.put("path", path);
        authed("POST", "/v1/folder", b.toString());
    }

    void rename(String oldPath, String newPath) throws Exception {
        JSONObject b = new JSONObject();
        b.put("old_path", oldPath);
        b.put("new_path", newPath);
        authed("PUT", "/v1/folder/name", b.toString());
    }

    void delete(List<String> paths) throws Exception {
        JSONArray a = new JSONArray();
        for (String p : paths) a.put(p);
        authed("DELETE", "/v1/batch", a.toString());
    }

    /** Copia o mueve en segundo plano en el servidor. type = "copy" | "move". */
    void operate(String type, List<String> from, String to) throws Exception {
        JSONObject b = new JSONObject();
        b.put("type", type);
        b.put("to", to);
        b.put("style", "");
        JSONArray items = new JSONArray();
        for (String f : from) {
            JSONObject it = new JSONObject();
            it.put("from", f);
            it.put("finished", false);
            it.put("size", 0);
            it.put("processed_size", 0);
            items.put(it);
        }
        b.put("item", items);
        authed("POST", "/v1/batch/task", b.toString());
    }

    interface Progress { void onProgress(long done, long total); }

    /** Sube un archivo (en un único bloque) a la carpeta indicada. */
    void upload(String dir, String fileName, InputStream data, long length, Progress cb) throws Exception {
        // Petición ligera previa para renovar el token si caducó (el flujo no se puede reintentar).
        authed("GET", "/v1/folder?index=1&size=1&path=" + Uri.encode(dir), null);
        uploadOnce(dir, fileName, data, length, cb);
    }

    private void uploadOnce(String dir, String fileName, InputStream data, long length, Progress cb)
            throws Exception {
        String boundary = "----CasaOSDocker" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(prefs.baseUrl() + "/v1/file/upload").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(120000);
            c.setChunkedStreamingMode(64 * 1024);
            c.setRequestProperty("Authorization", prefs.token());
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            OutputStream os = c.getOutputStream();
            String[][] fields = {
                    {"path", dir}, {"relativePath", fileName}, {"filename", fileName},
                    {"totalChunks", "1"}, {"chunkNumber", "1"},
                    {"chunkSize", String.valueOf(Math.max(length, 1))},
                    {"currentChunkSize", String.valueOf(Math.max(length, 0))},
                    {"totalSize", String.valueOf(Math.max(length, 0))},
            };
            StringBuilder sb = new StringBuilder();
            for (String[] f : fields) {
                sb.append("--").append(boundary).append("\r\n")
                  .append("Content-Disposition: form-data; name=\"").append(f[0]).append("\"\r\n\r\n")
                  .append(f[1]).append("\r\n");
            }
            sb.append("--").append(boundary).append("\r\n")
              .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
              .append(fileName.replace("\"", "_")).append("\"\r\n")
              .append("Content-Type: application/octet-stream\r\n\r\n");
            os.write(sb.toString().getBytes("UTF-8"));
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = data.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (cb != null) cb.onProgress(done, length);
            }
            os.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            if (code >= 400) {
                InputStream es = c.getErrorStream();
                throw new ApiException(code, errorMessage(code, es == null ? "" : readAll(es)));
            }
        } finally {
            c.disconnect();
        }
    }

    /** URL de descarga directa (incluye el token, para navegador/DownloadManager). */
    String downloadUrl(String path) throws Exception {
        if (prefs.token().length() == 0) login();
        return prefs.baseUrl() + "/v1/file?path=" + Uri.encode(path) + "&token=" + Uri.encode(prefs.token());
    }

    String token() { return prefs.token(); }

    static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    static String parent(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    private static JSONArray dataArray(String res) throws Exception {
        Object d = new JSONObject(res).opt("data");
        if (d instanceof JSONArray) return (JSONArray) d;
        if (d instanceof JSONObject) {
            JSONObject o = (JSONObject) d;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                Object v = o.opt(it.next());
                if (v instanceof JSONArray) return (JSONArray) v;
            }
        }
        return null;
    }

    private static long num(Object v, long def) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong(((String) v).trim()); } catch (Exception ignored) { }
            try { return (long) Double.parseDouble(((String) v).trim()); } catch (Exception ignored) { }
        }
        return def;
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
            c.setReadTimeout(30000);
            c.setRequestProperty("Accept", "application/json");
            if (auth) c.setRequestProperty("Authorization", prefs.token());
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                OutputStream os;
                try {
                    os = c.getOutputStream();
                } catch (java.net.ProtocolException pe) {
                    // Algunas versiones de Android no permiten cuerpo en DELETE.
                    c.disconnect();
                    return rawRequest(url, method, body, auth);
                }
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (code >= 400) throw new ApiException(code, errorMessage(code, text));
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String errorMessage(int code, String text) {
        String msg = "HTTP " + code;
        try {
            JSONObject o = new JSONObject(text);
            String m = o.optString("message", "");
            Object d = o.opt("data");
            if (d instanceof String && ((String) d).length() > 0 && !((String) d).equals(m)) m += " (" + d + ")";
            if (m.length() > 0) msg += ": " + m;
        } catch (Exception ignored) { }
        return msg;
    }

    /** Petición HTTP/1.1 escrita a mano (solo como respaldo para DELETE con cuerpo). */
    private String rawRequest(URL url, String method, String body, boolean auth) throws Exception {
        boolean https = "https".equalsIgnoreCase(url.getProtocol());
        int port = url.getPort() > 0 ? url.getPort() : (https ? 443 : 80);
        java.net.Socket s = https
                ? javax.net.ssl.SSLSocketFactory.getDefault().createSocket(url.getHost(), port)
                : new java.net.Socket(url.getHost(), port);
        try {
            s.setSoTimeout(30000);
            byte[] payload = body.getBytes("UTF-8");
            String file = url.getFile().length() == 0 ? "/" : url.getFile();
            StringBuilder h = new StringBuilder();
            h.append(method).append(' ').append(file).append(" HTTP/1.1\r\n")
             .append("Host: ").append(url.getHost()).append(url.getPort() > 0 ? ":" + url.getPort() : "").append("\r\n")
             .append("Accept: application/json\r\n")
             .append("Content-Type: application/json\r\n")
             .append("Content-Length: ").append(payload.length).append("\r\n")
             .append("Connection: close\r\n");
            if (auth) h.append("Authorization: ").append(prefs.token()).append("\r\n");
            h.append("\r\n");
            OutputStream os = s.getOutputStream();
            os.write(h.toString().getBytes("UTF-8"));
            os.write(payload);
            os.flush();
            String resp = readAll(s.getInputStream());
            int sp = resp.indexOf(' ');
            int code = Integer.parseInt(resp.substring(sp + 1, sp + 4));
            int bodyAt = resp.indexOf("\r\n\r\n");
            String text = bodyAt < 0 ? "" : resp.substring(bodyAt + 4);
            if (resp.toLowerCase().contains("transfer-encoding: chunked")) {
                int nl = text.indexOf("\r\n");
                int end = text.lastIndexOf("\r\n0\r\n");
                if (nl >= 0 && end > nl) text = text.substring(nl + 2, end);
            }
            if (code >= 400) throw new ApiException(code, errorMessage(code, text));
            return text;
        } finally {
            s.close();
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
