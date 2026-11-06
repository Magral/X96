// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;

import java.util.Locale;
import java.util.regex.*;

/** Read-only, bounded parsing. Unknown measurements stay unknown, never zero. */
final class DeviceHealth {
    final long ramTotal,ramAvailable,dataTotal,dataAvailable;
    DeviceHealth(long t,long a,long d,long f){ramTotal=t;ramAvailable=a;dataTotal=d;dataAvailable=f;}
    private static long number(String s){try{long n=Long.parseLong(s);return n>=0&&n<=(1L<<40)?n:-1;}catch(Exception e){return -1;}}
    private static long field(String raw,String key){
        Matcher m=Pattern.compile("(?m)^"+key+":\\s*([0-9]+) kB\\s*$").matcher(raw);
        if(!m.find())return -1;long n=number(m.group(1));return m.find()?-1:n;
    }
    static DeviceHealth parse(String raw){
        if(raw==null||raw.length()>8192)return new DeviceHealth(-1,-1,-1,-1);
        long total=field(raw,"MemTotal"),available=field(raw,"MemAvailable"),disk=-1,free=-1;
        if(total<=0||available<0||available>total){total=-1;available=-1;}
        for(String line:raw.split("\\r?\\n")){
            String[] a=line.trim().split("\\s+");
            if(a.length!=6||!a[5].equals("/data")||!a[4].matches("[0-9]{1,3}%"))continue;
            long t=number(a[1]),used=number(a[2]),f=number(a[3]);
            if(t>0&&used>=0&&used<=t&&f>=0&&f<=t&&used+f<=t&&Integer.parseInt(a[4].replace("%",""))<=100){disk=t;free=f;}
        }
        return new DeviceHealth(total,available,disk,free);
    }
    static String size(long kib){return kib<0?"нет данных":kib>=1048576?String.format(Locale.ROOT,"%.2f ГиБ",kib/1048576.0):String.format(Locale.ROOT,"%.0f МиБ",kib/1024.0);}
    String text(){return "Оперативная память: "+(ramAvailable<0?"нет данных":"доступно "+size(ramAvailable)+" из "+size(ramTotal))+
        "\nНакопитель: "+(dataAvailable<0?"нет данных":"свободно "+size(dataAvailable)+" из "+size(dataTotal))+
        (dataAvailable>=0&&dataAvailable<1048576?"\nСвободно меньше 1 ГиБ: можно воспользоваться очисткой кеша в «Улучшениях».":"")+ 
        "\nДоступная RAM — оценка Android с учётом освобождаемого кеша. Один замер не определяет причину торможения.";}
    String difference(DeviceHealth before){
        if(before==null||ramAvailable<0||before.ramAvailable<0||ramTotal!=before.ramTotal)return "Изменение доступной RAM: нет сопоставимых данных.";
        long delta=ramAvailable-before.ramAvailable;
        return "Доступная RAM: "+size(before.ramAvailable)+" → "+size(ramAvailable)+" ("+(delta>=0?"+":"−")+size(Math.abs(delta))+"). Это изменение всей системы, а не только лаунчера.";
    }
}
