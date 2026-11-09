package com.hubmobile.app.harness;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/**
 * 本地桥共享密钥鉴权。
 *
 * 同机任意 App 都能连 127.0.0.1，所以除了 LocalFence 的 Host/Origin 围栏，
 * 敏感路由还要校验 App 在启动 node 时注入的 APP_LOCAL_TOKEN。
 * 网页设置页直连的 /overlay、/status、/changelog、/open 走免密路由（只读或低风险）。
 */
public final class LocalBridgeAuth {

    public static final String PREFS = "dsh_prefs";
    public static final String KEY = "local_token";

    private LocalBridgeAuth() {}

    public static String token(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String t = p.getString(KEY, "");
        if (t == null || t.length() < 16) {
            t = java.util.UUID.randomUUID().toString().replace("-", "")
                    + java.util.UUID.randomUUID().toString().replace("-", "");
            p.edit().putString(KEY, t).apply();
        }
        return t;
    }

    public static boolean authorized(String header, String body, Context c) {
        String expect = token(c);
        if (expect.isEmpty()) return false;
        // 1) X-DSH-Token 头
        if (header != null) {
            String low = header.toLowerCase(Locale.US);
            int i = low.indexOf("x-dsh-token:");
            if (i >= 0) {
                int eol = header.indexOf('\r', i);
                if (eol < 0) eol = header.length();
                if (expect.equals(header.substring(i + 12, eol).trim())) return true;
            }
            // 2) query ?token=
            String first = header.split("\r\n", 2)[0];
            int q = first.indexOf("token=");
            if (q >= 0) {
                String v = first.substring(q + 6).split("[&\\s]", 2)[0];
                if (expect.equals(v)) return true;
            }
        }
        // 3) body 里的 "token":"..."
        if (body != null && body.indexOf(expect) >= 0) return true;
        return false;
    }

    public static void reject(OutputStream out) throws IOException {
        byte[] b = "{\"ok\":false,\"error\":\"unauthorized\"}".getBytes("UTF-8");
        out.write(("HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\nContent-Length: "
                + b.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }
}
