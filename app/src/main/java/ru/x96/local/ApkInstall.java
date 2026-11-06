// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;
import java.io.*;
import java.util.function.BooleanSupplier;

/** Pure helpers shared by the phone file picker, ADB stream and tests. */
final class ApkInstall {
    static final long MAX_BYTES=256L*1024*1024;
    static final String PROJECTIVY_CERT="f6697bf4082ee97511e4de07863193884a015b7ab5860430321bda1042b0aadd";
    interface Progress {void update(long sent,long total);}
    static void size(long value) throws IOException {if(value<1||value>MAX_BYTES)throw new IOException("Нужен один APK размером до 256 МБ");}
    static long copy(InputStream in,OutputStream out,BooleanSupplier cancelled) throws IOException {
        byte[] buffer=new byte[65536];long count=0;int n;
        while((n=in.read(buffer))!=-1){if(cancelled.getAsBoolean())throw new IOException("Выбор файла отменён");count+=n;if(count>MAX_BYTES)throw new IOException("APK больше 256 МБ");out.write(buffer,0,n);}
        size(count);return count;
    }
    static IOException failure(String raw){
        String reason;
        if(raw.contains("UPDATE_INCOMPATIBLE")||raw.contains("INCONSISTENT_CERTIFICATES"))reason="Подпись APK отличается от установленного приложения. Android отклонил обновление; старую версию удалять автоматически не будем.";
        else if(raw.contains("VERSION_DOWNGRADE"))reason="Этот APK старее установленной версии. Понижение версии не разрешено.";
        else if(raw.contains("NO_MATCHING_ABIS"))reason="APK не подходит процессору приставки. Нужна совместимая ARM-сборка.";
        else if(raw.contains("OLDER_SDK"))reason="Для этого APK нужна более новая версия Android на приставке.";
        else if(raw.contains("INSUFFICIENT_STORAGE"))reason="На приставке недостаточно места для установки.";
        else if(raw.contains("MISSING_SPLIT"))reason="Нужен полный APK: выбранный файл требует дополнительных split-файлов.";
        else if(raw.contains("USER_RESTRICTED")||raw.contains("ABORTED"))reason="Установка запрещена или отменена на приставке. Проверь её экран и ограничения установки.";
        else if(raw.contains("INVALID_APK")||raw.contains("PARSE_FAILED"))reason="Android не принял APK: файл повреждён, неполон или несовместим.";
        else reason="Android не подтвердил установку. "+raw.replaceAll("[\\p{Cntrl}]"," ").trim();
        return new IOException(reason.length()>600?reason.substring(0,600):reason);
    }
    static void success(String response) throws IOException {
        if(response.contains("Failure")||!response.trim().equals("Success"))throw failure(response);
    }
    private ApkInstall(){}
}
