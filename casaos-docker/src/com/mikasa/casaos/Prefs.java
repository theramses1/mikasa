package com.mikasa.casaos;

import android.content.Context;
import android.content.SharedPreferences;

/** Configuración guardada: servidor, credenciales y token de sesión. */
final class Prefs {
    private static final String FILE = "casaos";
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String server() { return sp.getString("server", ""); }
    String user() { return sp.getString("user", ""); }
    String pass() { return sp.getString("pass", ""); }
    String token() { return sp.getString("token", ""); }

    boolean isConfigured() { return server().length() > 0; }

    void save(String server, String user, String pass) {
        sp.edit().putString("server", server).putString("user", user)
                .putString("pass", pass).remove("token").apply();
    }

    void setToken(String t) { sp.edit().putString("token", t).apply(); }

    /** Devuelve la URL base, p.ej. "http://192.168.1.50". */
    String baseUrl() { return normalize(server()); }

    static String normalize(String s) {
        s = s.trim();
        if (s.length() == 0) return s;
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
