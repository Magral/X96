// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;
interface TvShell {
    String shell(String fixedCommand) throws Exception;
    default String shellLong(String fixedCommand) throws Exception{return shell(fixedCommand);}
    default void install(java.io.InputStream source,long size,ApkInstall.Progress progress) throws Exception{throw new java.io.IOException("Передача APK не поддерживается этим соединением");}
}
