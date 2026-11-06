// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;

import java.io.IOException;
import java.util.regex.*;

/** Reviewed per-package commands; no global ART flags or batch compilation. */
final class AppOptimization {
    enum Mode {
        PROFILE("По использованию", "speed-profile"),
        FULL("Полная компиляция", "speed"),
        REMOVE("Убрать предварительную компиляцию", "verify");
        final String title,filter;
        Mode(String t,String f){title=t;filter=f;}
    }
    static void eligible(TvRepository.State s,String pkg) throws IOException {
        if(!Policy.validPackage(pkg)||!s.packages.contains(pkg)||s.disabled.contains(pkg))throw new IOException("Приложение не установлено или отключено");
        if(s.system.contains(pkg))throw new IOException("Системные приложения не входят в этот раздел оптимизации");
        String reason=Policy.disableReason(pkg,s.homes,s.homesKnown,false);
        if(reason!=null)throw new IOException(reason);
    }
    static String command(String pkg,Mode mode,int sdk,long freeKiB) throws IOException {
        if(!Policy.validPackage(pkg)||mode==null)throw new IOException("Некорректный выбор приложения или режима");
        if(sdk<26||sdk>33)throw new IOException("Эта функция поддерживает Android 8–13");
        if(mode!=Mode.REMOVE&&freeKiB<1048576)throw new IOException(freeKiB<0?"Не удалось проверить свободное место. Компиляция не запущена.":"Для компиляции здесь требуется не меньше 1 ГиБ свободного места. Это запас приложения, а не гарантия достаточного места.");
        return "cmd package compile -m "+mode.filter+" -f "+pkg;
    }
    static void success(String result) throws IOException {
        if(result==null||!result.trim().equals("Success"))throw new IOException("Android не подтвердил завершение компиляции. Не повторяй операцию автоматически.");
    }
    static long launchMillis(String raw,String component) throws IOException {
        if(raw==null||raw.length()>8192||!Policy.validComponent(component))throw new IOException("Не удалось прочитать замер запуска");
        Matcher activity=Pattern.compile("(?m)^Activity: ([^\\r\\n]+)$").matcher(raw);
        if(!Pattern.compile("(?m)^Status: ok\\s*$").matcher(raw).find()||!activity.find()||!canonical(component).equals(canonical(activity.group(1).trim()))||activity.find()||raw.contains("Warning:"))throw new IOException("Новый запуск не подтверждён; результат не сравниваем");
        Matcher launch=Pattern.compile("(?m)^LaunchState: ([^\\r\\n]+)$").matcher(raw);
        if(launch.find()&&!launch.group(1).trim().equals("COLD"))throw new IOException("Android сообщил о повторном использовании процесса или экрана. Такой замер не сравниваем с запуском после закрытия.");
        Matcher m=Pattern.compile("(?m)^TotalTime: ([0-9]{1,8})\\s*$").matcher(raw);
        if(!m.find())throw new IOException("Прошивка не предоставила время запуска");
        long value=Long.parseLong(m.group(1));if(value<=0||m.find())throw new IOException("Некорректное время запуска");return value;
    }
    private static String canonical(String component){
        if(!Policy.validComponent(component))return "";String[] parts=component.split("/",2);
        return parts[0]+"/"+(parts[1].startsWith(".")?parts[0]+parts[1]:parts[1]);
    }
}
