// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;
import java.util.*;
final class CpuLoad {
 static final String COMMAND="head -n 1 /proc/stat; sleep 1; head -n 1 /proc/stat";
 static long[] sample(String line){
  String[] a=line.trim().split("\\s+");if(a.length<5||a.length>11||!a[0].equals("cpu"))return null;
  long[] result=new long[8];
  try{for(int i=1;i<a.length;i++){if(!a[i].matches("[0-9]{1,15}"))return null;long n=Long.parseLong(a[i]);if(i<=8)result[i-1]=n;}}catch(Exception e){return null;}return result;
 }
 static String parse(String raw){
  String unknown="Загрузка CPU: замер недоступен.";
  if(raw==null||raw.length()>2048)return unknown;String[] lines=raw.trim().split("\\r?\\n");if(lines.length!=2)return unknown;
  long[] a=sample(lines[0]),b=sample(lines[1]);if(a==null||b==null)return unknown;long total=0,idle=0,wait=0;
  for(int i=0;i<8;i++){long delta=b[i]-a[i];if(delta<0)return unknown;total+=delta;if(i==3)idle=delta;if(i==4)wait=delta;}
  if(total==0)return unknown;
  return String.format(Locale.ROOT,"Загрузка CPU: %.1f%%; ожидание ввода-вывода (iowait): %.1f%% за интервал около секунды.",(total-idle-wait)*100.0/total,wait*100.0/total)+" Это среднее по всем ядрам; одно ядро может быть занято сильнее. iowait — приблизительный показатель ядра, он не определяет причину задержки или скорость накопителя.";
 }
}
