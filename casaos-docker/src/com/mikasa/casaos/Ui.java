package com.mikasa.casaos;

import android.content.Context;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

final class Ui {
    private Ui() { }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        String[] u = {"KB", "MB", "GB", "TB", "PB"};
        double v = b;
        int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1);
        return (v >= 100 ? String.format(java.util.Locale.US, "%.0f", v)
                : String.format(java.util.Locale.US, "%.1f", v)) + " " + u[i];
    }

    static String describe(Exception e) {
        if (e instanceof UnknownHostException) return "no se encuentra el servidor";
        if (e instanceof ConnectException) return "conexión rechazada (¿IP/puerto correctos?)";
        if (e instanceof SocketTimeoutException) return "tiempo de espera agotado";
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
