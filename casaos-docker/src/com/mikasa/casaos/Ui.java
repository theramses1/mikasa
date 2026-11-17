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

    static String describe(Exception e) {
        if (e instanceof UnknownHostException) return "no se encuentra el servidor";
        if (e instanceof ConnectException) return "conexión rechazada (¿IP/puerto correctos?)";
        if (e instanceof SocketTimeoutException) return "tiempo de espera agotado";
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
