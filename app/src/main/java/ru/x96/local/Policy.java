// SPDX-License-Identifier: GPL-3.0-only
// Protection rules adapted from Tellyscope c302787, Protected.kt; see NOTICE.
package ru.x96.local;

import java.util.*;
import java.util.regex.Pattern;

public final class Policy {
    public static final String PROJECTIVY = "com.spocky.projengmenu";
    private static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*");
    private static final String[] CRITICAL = {"launcher", "networkstack", "wifi", "connectivity", "inputmethod", "keyboard", ".ime", "inputdevices", "tvinput", "tv.input", "hdmi", "tuner", "bluetooth", "remote", "rcu", "setupwizard", "setupwraith", "bootwizard", "provision", "updat", "upgrad", "systemui", "permissioncontroller", "packageinstaller", "webview", "trichromelibrary", "providers.", "overlay", "ext.services", "ext.shared", "modulemetadata"};
    public static boolean validPackage(String p) { return p != null && p.length() <= 200 && PKG.matcher(p).matches(); }
    public static boolean validComponent(String c) {
        if (c == null || c.length() > 400) return false;
        String[] a = c.split("/", -1);
        return a.length == 2 && validPackage(a[0]) && a[1].matches("[A-Za-z0-9_.$]+") && !a[1].equals(".");
    }
    public static String packageOf(String component) { return validComponent(component) ? component.substring(0, component.indexOf('/')) : ""; }
    // Deliberately short local review list. Unknown OEM system packages stay enabled.
    private static final Set<String> REVIEWED_SYSTEM = new HashSet<>(Arrays.asList(
        "com.google.android.videos", "com.google.android.music", "com.google.android.play.games",
        "com.google.android.apps.tachyon", "com.google.android.feedback", "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom"));
    public static String disableReason(String p,Set<String> homes,boolean known,boolean system) {
        String reason=protect(p,homes,known);
        if(reason!=null)return reason;
        return system && !REVIEWED_SYSTEM.contains(p) ? "Системный пакет пока не проверен для X96" : null;
    }
    public static String protect(String p, Set<String> homes, boolean homesKnown) {
        if (!validPackage(p)) return "Недопустимое имя пакета";
        if (!homesKnown || homes.isEmpty()) return "Не удалось определить домашние экраны";
        if (homes.contains(p) || p.equals(PROJECTIVY)) return "Домашний экран";
        if (p.equals("android") || p.equals("com.android.shell") || p.equals("com.android.phone") || p.equals("com.android.settings") || p.equals("com.android.tv.settings") || p.equals("com.android.vending") || p.equals("com.google.android.gms") || p.equals("com.google.android.gsf")) return "Компонент системы";
        String lower = p.toLowerCase(Locale.ROOT);
        for (String s : CRITICAL) if (lower.contains(s)) return "Защищённый компонент системы";
        if (Pattern.compile("(^|\\.)(f|usb|remote|auth)?ota(\\.|$)").matcher(lower).find()) return "Обновление системы";
        return null;
    }
    /** Numeric RFC1918 only: no hostnames, public IPs, loopback, link-local or DNS. */
    public static byte[] privateAddress(String ip) {
        if (ip == null || !ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IllegalArgumentException("Укажи локальный IPv4, например 192.168.1.50");
        String[] parts = ip.split("\\."); byte[] b = new byte[4]; int[] n = new int[4];
        for (int i=0;i<4;i++) { n[i]=Integer.parseInt(parts[i]); if(n[i]>255 || (parts[i].length()>1 && parts[i].startsWith("0"))) throw new IllegalArgumentException("Некорректный IP"); b[i]=(byte)n[i]; }
        if (!(n[0]==10 || n[0]==172 && n[1]>=16 && n[1]<=31 || n[0]==192 && n[1]==168)) throw new IllegalArgumentException("Разрешены только частные адреса локальной сети");
        return b;
    }
    public static int port(String p) { try { int n=Integer.parseInt(p); if(n>=1024 && n<=65535)return n; } catch(NumberFormatException ignored) {} throw new IllegalArgumentException("Порт должен быть от 1024 до 65535"); }
    private Policy() {}
}
