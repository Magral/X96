// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;
import android.content.Context;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import java.io.*;
import java.security.MessageDigest;
import java.security.DigestOutputStream;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Only a user-selected content URI is read; no broad storage access or downloaded APKs. */
final class ApkSelection implements AutoCloseable {
    final File file;final String label,pkg,version,sha256;final long code;final int minSdk;
    private ApkSelection(File f,String label,String pkg,String version,long code,int min,String hash){file=f;this.label=label;this.pkg=pkg;this.version=version;this.code=code;minSdk=min;sha256=hash;}
    static ApkSelection read(Context context,Uri uri,BooleanSupplier cancelled) throws Exception {
        File dir=new File(context.getCacheDir(),"apk-install");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Не удалось подготовить временную папку");
        File f=File.createTempFile("picked-",".apk",dir);
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new DigestOutputStream(new FileOutputStream(f),digest)){
                if(in==null)throw new IOException("Не удалось открыть выбранный файл");ApkInstall.copy(in,out,cancelled);
            }
            if(cancelled.getAsBoolean())throw new IOException("Выбор APK отменён");
            PackageManager pm=context.getPackageManager();
            @SuppressWarnings("deprecation")
            PackageInfo p=pm.getPackageArchiveInfo(f.getAbsolutePath(),Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);
            if(p==null||!Policy.validPackage(p.packageName)||p.applicationInfo==null)throw new IOException("Выбранный файл не является самостоятельным APK. Архивы APKS, XAPK и APKM пока не поддерживаются.");
            ApplicationInfo app=p.applicationInfo;app.sourceDir=f.getAbsolutePath();app.publicSourceDir=f.getAbsolutePath();
            String label=clean(app.loadLabel(pm).toString()),version=clean(p.versionName==null?"без номера":p.versionName);
            @SuppressWarnings("deprecation") long code=Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;
            if(code<0)throw new IOException("Некорректная версия APK");
            List<Signature> certs=new ArrayList<>();
            if(Build.VERSION.SDK_INT>=28){if(p.signingInfo!=null)certs.addAll(Arrays.asList(p.signingInfo.getApkContentsSigners()));}
            else {@SuppressWarnings("deprecation") Signature[] signatures=p.signatures;if(signatures!=null)certs.addAll(Arrays.asList(signatures));}
            if(certs.isEmpty())throw new IOException("Не удалось прочитать сертификат APK");
            if(p.packageName.equals(Policy.PROJECTIVY)){
                if(certs.size()!=1||!hex(MessageDigest.getInstance("SHA-256").digest(certs.get(0).toByteArray())).equals(ApkInstall.PROJECTIVY_CERT))throw new IOException("Сертификат этого Projectivy отличается от официальной GitHub-сборки. Файл не будет отправлен. Используй официальный источник или Google Play на ТВ.");
            }
            return new ApkSelection(f,label.isEmpty()?p.packageName:label,p.packageName,version,code,app.minSdkVersion,hex(digest.digest()));
        }catch(Exception e){f.delete();throw e;}
    }
    static String clean(String value){String s=value.replaceAll("[\\p{Cntrl}]"," ").trim();return s.substring(0,Math.min(160,s.length()));}
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte v:bytes)out.append(String.format(Locale.ROOT,"%02x",v&255));return out.toString();}
    @Override public void close(){file.delete();}
    static void sweep(Context context){File dir=new File(context.getCacheDir(),"apk-install");File[] files=dir.listFiles();if(files!=null)for(File f:files)if(f.getName().startsWith("picked-")&&f.getName().endsWith(".apk")&&System.currentTimeMillis()-f.lastModified()>24L*60*60*1000)f.delete();}
}
