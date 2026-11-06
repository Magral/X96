// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Fixed, opt-in Projectivy/root operations. Never modifies partitions or private app settings. */
final class ProjectivyTools {
    static final String PKG=Policy.PROJECTIVY,ACCESS="pi.accessibility",OOM="pi.oom";
    private final TvShell shell;private final RecoveryStore store;private final String device;
    private int rootMode; // 0 unavailable/not checked, 1 existing root adbd, 2 su -c
    ProjectivyTools(TvShell s,RecoveryStore j,String d,String uid){shell=s;store=j;device=d;rootMode="0".equals(uid)?1:0;}
    static String quote(String s){return "'"+s.replace("'","'\"'\"'")+"'";}
    boolean rootReady(){return rootMode!=0;}
    void probeRoot() throws Exception {
        if("0".equals(shell.shell("id -u").trim())){rootMode=1;return;}
        rootMode=0;if("0".equals(shell.shell("su -c 'id -u'").trim())){rootMode=2;return;}
        throw new IOException("Root не подтверждён. Разреши запрос su на телевизоре, если он появился.");
    }
    private String root(String fixed,boolean slow) throws Exception {
        if(rootMode==0)throw new IOException("Сначала проверь root-доступ");
        String command=rootMode==2?"su -c "+quote(fixed):fixed;
        return slow?shell.shellLong(command):shell.shell(command);
    }
    static List<String> services(String raw) throws IOException {
        List<String> out=new ArrayList<>();if(raw==null)throw new IOException("Не удалось прочитать службы специальных возможностей");
        String text=raw.trim();if(text.isEmpty()||text.equals("null"))return out;
        for(String part:text.split(":",-1)){if(!Policy.validComponent(part))throw new IOException("Неожиданный список служб: изменения не отправлены");if(!out.contains(part))out.add(part);}
        return out;
    }
    private List<String> readServices() throws Exception{return services(shell.shell("settings --user 0 get secure enabled_accessibility_services"));}
    private void writeServices(List<String> values) throws Exception {for(String v:values)if(!Policy.validComponent(v))throw new IOException("Недопустимая служба");shell.shell("settings --user 0 put secure enabled_accessibility_services "+quote(String.join(":",values)));}
    boolean hasInterceptRecord(){return store.option(device,ACCESS)!=null;}
    void disableIntercept() throws Exception {
        List<String> current=readServices(),pi=new ArrayList<>(),keep=new ArrayList<>();
        for(String c:current)if(Policy.packageOf(c).equals(PKG))pi.add(c);else keep.add(c);
        if(pi.isEmpty())return;
        if(!hasInterceptRecord()&&!store.option(device,ACCESS,String.join(":",pi)))throw new IOException("Журнал не сохранён; перехват не изменён");
        writeServices(keep);
        for(String c:readServices())if(Policy.packageOf(c).equals(PKG))throw new IOException("Отключение перехвата не подтверждено; запись возврата сохранена");
    }
    void restoreIntercept() throws Exception {
        String snapshot=store.option(device,ACCESS);if(snapshot==null)throw new IOException("Нет сохранённого изменения перехвата");
        List<String> own=services(snapshot);if(own.isEmpty())throw new IOException("Пустая запись возврата");
        for(String c:own)if(!Policy.packageOf(c).equals(PKG))throw new IOException("Запись возврата не относится к Projectivy");
        List<String> merged=readServices();for(String c:own)if(!merged.contains(c))merged.add(c);
        writeServices(merged);if(!readServices().containsAll(own))throw new IOException("Возврат перехвата не подтверждён");
        if(!store.forgetOption(device,ACCESS))throw new IOException("Служба возвращена; запись журнала сохранена");
    }
    static String component(TvRepository.State s) throws IOException {
        String result=null;for(String c:s.homeComponents)if(Policy.packageOf(c).equals(PKG)){if(result!=null)throw new IOException("Несколько HOME-компонентов Projectivy");result=c;}
        if(result==null)throw new IOException("Установленный Projectivy не найден среди домашних экранов");return result;
    }
    void launchHome(TvRepository.State s) throws Exception {shell.shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n "+component(s));}
    void compile(boolean reset,int sdk) throws Exception {
        if(sdk<26||sdk>33)throw new IOException("Эта оптимизация поддерживается здесь только для Android 8–13");
        String result=root("cmd package compile "+(reset?"--reset ":"-m speed -f ")+PKG,true);
        AppOptimization.success(result);
    }
    void optimizeApp(String pkg,AppOptimization.Mode mode,int sdk,long freeKiB) throws Exception {
        String fixed=AppOptimization.command(pkg,mode,sdk,freeKiB);
        AppOptimization.success(root(fixed,true));
    }
    static final class Process {
        final String pid,start,boot;final int score;
        Process(String p,String s,String b,int n){pid=p;start=s;boot=b;score=n;}
        String snapshot(){return pid+"|"+start+"|"+boot+"|"+score;}
        boolean same(Process p){return p!=null&&pid.equals(p.pid)&&start.equals(p.start)&&boot.equals(p.boot);}
    }
    static Process snapshot(String raw) throws IOException {
        String[] a=raw.split("\\|",-1);if(a.length!=4||!a[0].matches("[1-9][0-9]{0,7}")||!a[1].matches("[0-9]{1,20}")||!a[2].matches("[a-fA-F0-9-]{36}")||!a[3].matches("-?[0-9]{1,4}"))throw new IOException("Некорректная запись приоритета");
        int n=Integer.parseInt(a[3]);if(n< -1000||n>1000)throw new IOException("Недопустимый приоритет");return new Process(a[0],a[1],a[2],n);
    }
    private Process process() throws Exception {
        String pid=shell.shell("pidof "+PKG+" || true").trim();if(pid.isEmpty())return null;
        if(!pid.matches("[1-9][0-9]{0,7}"))throw new IOException("Не удалось однозначно определить процесс Projectivy");
        String data=root("cat /proc/"+pid+"/stat; tr '\\000' '\\n' < /proc/"+pid+"/cmdline | head -n 1; cat /proc/"+pid+"/oom_score_adj; cat /proc/sys/kernel/random/boot_id",false);
        String[] lines=data.split("\\r?\\n");if(lines.length!=4||!lines[1].equals(PKG))throw new IOException("Процесс изменился; запись приоритета отменена");
        int end=lines[0].lastIndexOf(')');if(end<0||!lines[0].startsWith(pid+" "))throw new IOException("Некорректные сведения о процессе");
        String[] fields=lines[0].substring(end+1).trim().split("\\s+");if(fields.length<20)throw new IOException("Нет времени запуска процесса");
        return snapshot(pid+"|"+fields[19]+"|"+lines[3].trim()+"|"+lines[2].trim());
    }
    private void writeScore(Process p,int value) throws Exception {
        if(value< -1000||value>1000)throw new IOException("Недопустимый приоритет");
        String path="/proc/"+p.pid;
        String guard="[ \"$(awk '{print $22}' "+path+"/stat)\" = "+quote(p.start)+" ] && [ \"$(cat /proc/sys/kernel/random/boot_id)\" = "+quote(p.boot)+" ] && [ \"$(tr '\\000' '\\n' < "+path+"/cmdline | head -n 1)\" = "+quote(PKG)+" ]";
        // Open the proc file first: a reused numeric PID cannot redirect this descriptor.
        root("exec 3> "+path+"/oom_score_adj && "+guard+" && printf '%s' "+value+" >&3",false);
        Process after=process();if(!p.same(after)||after.score!=value)throw new IOException("Приоритет не подтверждён; запись возврата сохранена");
    }
    boolean hasPriorityRecord(){return store.option(device,OOM)!=null;}
    void priority(boolean enabled) throws Exception {
        Process current=process();String stored=store.option(device,OOM);
        if(enabled){
            if(current==null)throw new IOException("Сначала открой Projectivy на телевизоре");
            if(stored!=null&&!snapshot(stored).same(current)){if(!store.forgetOption(device,OOM))throw new IOException("Не удалось обновить журнал старого процесса");stored=null;}
            if(current.score<=-200&&stored==null)return;
            if(stored==null&&!store.option(device,OOM,current.snapshot()))throw new IOException("Журнал не сохранён; приоритет не изменён");
            writeScore(current,-200);
        }else{
            if(stored==null)throw new IOException("Нет изменения приоритета для возврата");Process original=snapshot(stored);
            if(original.same(current))writeScore(original,original.score); // never write to a reused PID
            if(!store.forgetOption(device,OOM))throw new IOException("Приоритет возвращён; запись сохранена");
        }
    }
    static final class Report {
        final String measuredAt=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.MEDIUM).format(new java.util.Date());
        DeviceHealth health=DeviceHealth.parse("");
        String identity="",comparison="";
        String version="не определена",firmware="",root="не проверен",home="",advice="",pid="не запущен",memory="",build="",board="",wifi="не определён",temperature="недоступна";
        boolean intercept,homeTask,rootReady,interceptSaved,prioritySaved,oldVersion;
        int sdk;
        String text(){return "X96 Локально 0.10.0\nЗамер: "+measuredAt+"\nПлата: "+board+"\nСборка: "+build+"\nПрошивка: "+firmware+"\nAndroid API: "+sdk+"\nRoot: "+root+"\nМодули Wi-Fi (если видны): "+wifi+"\nТемпература: "+temperature+"\nProjectivy: "+version+"\nСистемный HOME: "+home+"\nHOME-задача Projectivy обнаружена: "+homeTask+"\nПерехват Projectivy: "+intercept+"\nПроцесс: "+pid+"\n"+health.text()+"\n"+memory+"\n\n"+advice+(comparison.isEmpty()?"":"\n\nПроверка первого нажатия:\n"+comparison);}
    }
    static String processIdentity(String pid,String raw){
        if(!pid.matches("[1-9][0-9]{0,7}"))return "";
        String[] lines=raw.trim().split("\\r?\\n");if(lines.length!=2||!lines[0].matches("[a-fA-F0-9-]{36}")||!lines[1].startsWith(pid+" ("))return "";
        int end=lines[1].lastIndexOf(')');if(end<0)return "";
        String[] f=lines[1].substring(end+1).trim().split("\\s+");
        return f.length>=20&&f[19].matches("[0-9]{1,20}")?lines[0]+"|"+pid+"|"+f[19]:"";
    }
    static String compare(Report before,Report after){
        if(before==null)return "Сначала сохрани замер до нажатия.";
        String outcome;
        if(before.identity.isEmpty()||after.identity.isEmpty())outcome="Не удалось надёжно сравнить процессы: прошивка не предоставила все сведения. Совпадения PID недостаточно.";
        else if(!before.identity.split("\\|")[0].equals(after.identity.split("\\|")[0]))outcome="Между замерами приставка перезагрузилась. Повтори проверку в пределах одной загрузки.";
        else if(before.identity.equals(after.identity))outcome="Процесс Projectivy остался тем же. Повторный логотип возможен при пересоздании экрана или смене HOME-задачи; это не подтверждение выгрузки процесса.";
        else outcome="Процесс Projectivy сменился. Эти замеры не показывают причину: это может быть завершение, сбой или обновление приложения.";
        if(!before.version.equals(after.version)||!before.home.equals(after.home)||before.intercept!=after.intercept)outcome+="\nМежду замерами изменились версия или настройки запуска. Для проверки пульта повтори замеры без изменения настроек.";
        return outcome+"\n"+after.health.difference(before.health)+"\nТемпература до: "+before.temperature+"; после: "+after.temperature+". Датчик не обязательно относится к процессору.";
    }
    private String optional(String c){try{return shell.shell(c);}catch(Exception e){return "";}}
    static boolean oldVersion(String v){Matcher m=Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})(?:\\D|$)").matcher(v);if(!m.find())return false;int major=Integer.parseInt(m.group(1)),minor=Integer.parseInt(m.group(2));return major<4||major==4&&minor<73;}
    private static String safe(String s){return s.replaceAll("[\\p{Cntrl}]"," ").trim().substring(0,Math.min(220,s.replaceAll("[\\p{Cntrl}]"," ").trim().length()));}
    Report inspect(TvRepository.State s) {
        Report r=new Report();r.home=s.home;r.rootReady=rootReady();r.root=rootMode==1?"ADB уже работает с root":rootMode==2?"su подтверждён":"не проверен / недоступен";
        String props=optional("getprop ro.product.board; getprop ro.hardware; getprop ro.build.display.id; getprop ro.build.fingerprint; getprop ro.build.version.sdk");String[] a=props.split("\\r?\\n",-1);
        if(a.length>=5){r.board=safe(a[0])+" / "+safe(a[1]);r.build=safe(a[2]);r.firmware=safe(a[3]);try{r.sdk=Integer.parseInt(a[4].trim());}catch(Exception ignored){}}
        Matcher v=Pattern.compile("versionName=([^\\s]+)").matcher(optional("dumpsys package "+PKG+" | grep -m 1 'versionName='"));if(v.find())r.version=safe(v.group(1));r.oldVersion=oldVersion(r.version);
        try{for(String c:readServices())if(Policy.packageOf(c).equals(PKG))r.intercept=true;}catch(Exception ignored){}
        r.homeTask=optional("dumpsys activity activities | grep -F 'type=home' | grep -F '"+PKG+"' || true").contains(PKG);
        String pid=optional("pidof "+PKG+" || true").trim();if(pid.matches("[1-9][0-9]{0,7}"))r.pid=pid;
        if(pid.matches("[1-9][0-9]{0,7}"))r.identity=processIdentity(pid,optional("cat /proc/sys/kernel/random/boot_id; cat /proc/"+pid+"/stat"));
        String memory=optional("grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; df -k /data | tail -n 1");r.health=DeviceHealth.parse(memory);
        String wifi=optional("ls /sys/module | grep -E '^(dhd|bcmdhd|brcmfmac|8189|8723|8822|8852|ssv|uwe|aic|rtk)' || true");if(!wifi.trim().isEmpty())r.wifi=safe(wifi);
        String[] thermal=optional("cat /sys/class/thermal/thermal_zone0/type 2>/dev/null; cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null").split("\\r?\\n");
        if(thermal.length==2&&thermal[1].trim().matches("-?[0-9]{1,6}")){double n=Double.parseDouble(thermal[1].trim());if(Math.abs(n)>200)n/=1000;if(n>=-40&&n<=150)r.temperature=safe(thermal[0])+": "+String.format(Locale.ROOT,"%.1f °C",n);}
        r.interceptSaved=hasInterceptRecord();r.prioritySaved=hasPriorityRecord();
        if(r.rootReady)try{Process p=process();if(p!=null)r.memory+="\nПриоритет текущего процесса (oom_score_adj): "+p.score;}catch(Exception ignored){}
        r.advice=(r.oldVersion?"Сначала обнови Projectivy до 4.73 или новее: разработчик исправил похожий HOME-сбой в 4.73.\n":"")+
            (!Policy.packageOf(s.home).equals(PKG)?"Projectivy не выбран системным домашним экраном.\n":"")+
            (r.intercept?"Включена служба перехвата Projectivy. После проверки системного HOME можно попробовать режим без перехвата. Ярлыки пульта Projectivy при этом перестанут работать.\n":"")+
            "Повторное появление логотипа само по себе не доказывает выгрузку из памяти. Сравни PID до и после первого нажатия; отсутствие HOME-задачи в отчёте может зависеть от формата вывода прошивки.";
        return r;
    }
}
