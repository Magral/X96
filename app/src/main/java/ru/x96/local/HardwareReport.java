// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;
import java.util.*;
final class HardwareReport {
    static String command(){
        return "for n in 0 1 2 3 4 5 6 7; do p=/sys/devices/system/cpu/cpu$n/cpufreq; [ -d \"$p\" ] || continue; printf 'C|%s' \"$n\"; for f in scaling_governor scaling_cur_freq scaling_min_freq scaling_max_freq cpuinfo_max_freq; do printf '|%s' \"$(head -n 1 \"$p/$f\" 2>/dev/null)\"; done; printf '\\n'; done; for n in 0 1 2 3 4 5 6 7; do p=/sys/class/thermal/thermal_zone$n; [ -d \"$p\" ] || continue; printf 'T|%s|%s|%s\\n' \"$n\" \"$(head -n 1 \"$p/type\" 2>/dev/null)\" \"$(head -n 1 \"$p/temp\" 2>/dev/null)\"; done; true";
    }
    static String mhz(String raw){try{if(!raw.matches("[0-9]{1,8}"))return "?";long n=Long.parseLong(raw);return n>=10000&&n<=10000000?String.format(Locale.ROOT,"%.0f",n/1000.0):"?";}catch(Exception e){return "?";}}
    static String parse(String raw){
        if(raw==null||raw.length()>8192)return "Сведения о процессоре и датчиках недоступны.";
        StringBuilder out=new StringBuilder();Set<String> seen=new HashSet<>();
        for(String line:raw.split("\\r?\\n")){
            String[] a=line.split("\\|",-1);if(a.length<2||!a[1].matches("[0-7]")||!seen.add(a[0]+a[1]))continue;
            if(a[0].equals("C")&&a.length==7){
                String governor=a[2].matches("[A-Za-z0-9_-]{1,40}")?a[2]:"нет данных";
                out.append("CPU ").append(a[1]).append(": режим ").append(governor).append("; частота драйвера ").append(mhz(a[3])).append(" МГц; пределы ").append(mhz(a[4])).append("–").append(mhz(a[5])).append(" МГц; максимум CPU ").append(mhz(a[6])).append(" МГц\n");
            }else if(a[0].equals("T")&&a.length==4&&a[2].matches("[A-Za-z0-9_. -]{1,60}")&&a[3].matches("-?[0-9]{1,6}")){
                double c=Double.parseDouble(a[3])/1000.0;if(c< -40||c>150)continue;
                out.append("Датчик ").append(a[1]).append(" (").append(a[2]).append("): ").append(String.format(Locale.ROOT,"%.1f °C",c)).append('\n');
            }
        }
        if(out.length()==0)return "Сведения о процессоре и датчиках недоступны.";
        return out+"Частота драйвера может отличаться от фактической. Низкая частота в покое нормальна. Эти данные сами по себе не доказывают перегрев или троттлинг. Датчики могут относиться к разным узлам; одинаковые частоты ядер могут описывать общий кластер. Показаны до 8 ядер и 8 датчиков. Параметры не изменяются.";
    }
}
