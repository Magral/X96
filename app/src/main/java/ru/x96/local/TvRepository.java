// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** All TV commands live here. Fixed commands only; root requests require an explicit user action. */
public final class TvRepository {
    private final TvShell adb;
    private final RecoveryStore journal;
    private String identity="";
    public String cacheResult="";
    private ProjectivyTools piTools;
    public static final class State {
        public String model,android,uid,home;
        public Set<String> packages,disabled,homes,owned,system;
        public List<String> homeComponents;
        public boolean homesKnown;
        public Map<Option,Option.Status> options=new EnumMap<>(Option.class);
        public boolean homeSaved;
    }
    public TvRepository(TvShell client,RecoveryStore recovery) {adb=client;journal=recovery;}
    private String run(String c) throws Exception {return adb.shell(c);}
    private static Set<String> packages(String output) throws IOException {
        Set<String> result=new TreeSet<>();for(String line:output.split("\n")){
            line=line.trim();if(line.isEmpty())continue;
            if(!line.startsWith("package:")||!Policy.validPackage(line.substring(8)))throw new IOException("Список приложений получен с ошибкой. Обнови данные перед изменениями.");
            result.add(line.substring(8));
        }return result;
    }
    public static List<String> components(String output) {
        Set<String> result=new TreeSet<>();for(String line:output.split("\n")){String c=line.trim();if(Policy.validComponent(c))result.add(c);}return new ArrayList<>(result);
    }
    public State refresh() throws Exception {
        Map<TvSnapshot.Field,String> snapshot=TvSnapshot.read(adb);
        String ident=snapshot.get(TvSnapshot.Field.ID);
        if(ident.replace("null","").replace("unknown","").trim().isEmpty())throw new IOException("Не удалось определить приставку");
        String next=hex(MessageDigest.getInstance("SHA-256").digest(ident.getBytes(StandardCharsets.UTF_8)));
        if(!identity.isEmpty()&&!identity.equals(next))throw new IOException("По этому адресу другая приставка. Подключись заново.");
        identity=next;
        State s=new State();s.model=snapshot.get(TvSnapshot.Field.MODEL);s.android=snapshot.get(TvSnapshot.Field.ANDROID);s.uid=snapshot.get(TvSnapshot.Field.UID);
        s.packages=packages(snapshot.get(TvSnapshot.Field.PACKAGES));if(s.packages.isEmpty())throw new IOException("Приставка не вернула список приложений");
        s.disabled=packages(snapshot.get(TvSnapshot.Field.DISABLED));s.system=packages(snapshot.get(TvSnapshot.Field.SYSTEM));
        if(s.system.isEmpty())throw new IOException("Не удалось определить системные приложения");
        if(!s.packages.containsAll(s.system)||!s.packages.containsAll(s.disabled))throw new IOException("Списки приложений изменились во время чтения. Обнови данные ещё раз.");
        s.homeComponents=components(snapshot.get(TvSnapshot.Field.HOMES));s.homes=new HashSet<>();
        for(String c:s.homeComponents)s.homes.add(Policy.packageOf(c));
        List<String> current=components(snapshot.get(TvSnapshot.Field.HOME));s.home=current.size()==1?current.get(0):"";
        s.homesKnown=!s.home.isEmpty()&&s.homeComponents.contains(s.home)&&!s.homes.isEmpty();if(!s.home.isEmpty())s.homes.add(Policy.packageOf(s.home));
        s.owned=new HashSet<>(journal.disabled(identity));s.homeSaved=journal.home(identity)!=null;
        Map<Option,String[]> settings;try{settings=Option.parseAll(run(Option.readAllCommand()));}catch(Exception e){settings=Collections.emptyMap();}
        for(Option o:Option.values()) {String[] values=settings.get(o);s.options.put(o,new Option.Status(values!=null,o.matches(values),journal.option(identity,o.name())!=null));}
        return s;
    }
    private void save(Set<String> packages) throws IOException {if(!journal.disabled(identity,new HashSet<>(packages)))throw new IOException("Не удалось сохранить журнал. Изменения отменены.");}
    public State disable(String pkg) throws Exception {
        State before=refresh();String reason=Policy.disableReason(pkg,before.homes,before.homesKnown,before.system.contains(pkg));
        if(reason!=null)throw new IOException(reason);if(!before.packages.contains(pkg))throw new IOException("Приложение не найдено");
        if(before.disabled.contains(pkg))throw new IOException("Приложение уже отключено");
        Set<String> own=new HashSet<>(before.owned);own.add(pkg);save(own); // write-ahead: restore survives disconnect or crash
        run("pm disable-user --user 0 "+pkg);
        State after=refresh();if(!after.disabled.contains(pkg))throw new IOException("Отключение не подтверждено. Запись восстановления сохранена.");return after;
    }
    public State restore(String pkg) throws Exception {
        if(!Policy.validPackage(pkg))throw new IOException("Недопустимое имя пакета");
        State before=refresh();if(!before.owned.contains(pkg))throw new IOException("Можно восстановить только изменения этого приложения");
        if(before.disabled.contains(pkg))run("pm enable --user 0 "+pkg);
        State after=refresh();if(after.disabled.contains(pkg))throw new IOException("Восстановление не подтверждено");
        Set<String> own=new HashSet<>(after.owned);own.remove(pkg);save(own);after.owned=own;return after;
    }
    public State projectivy() throws Exception {
        State before=refresh();String selected=null;
        for(String c:before.homeComponents)if(Policy.packageOf(c).equals(Policy.PROJECTIVY)){if(selected!=null)throw new IOException("Несколько экранов Projectivy — выбери его в настройках ТВ");selected=c;}
        if(selected==null)throw new IOException("Projectivy не найден среди домашних экранов. Установи и включи его на приставке.");
        if(!before.homesKnown)throw new IOException("Не удалось определить прежний домашний экран. Сначала выбери его в настройках ТВ.");
        String original=journal.home(identity);
        if(original==null && !Policy.packageOf(before.home).equals(Policy.PROJECTIVY)) {
            if(!journal.home(identity,before.home))throw new IOException("Не удалось сохранить прежний домашний экран");
        }
        run("cmd package set-home-activity --user 0 "+selected);
        State after=refresh();if(!Policy.packageOf(after.home).equals(Policy.PROJECTIVY))throw new IOException("Прошивка не подтвердила выбор Projectivy");return after;
    }
    public State restoreHome() throws Exception {
        State before=refresh();String c=journal.home(identity);
        if(!Policy.validComponent(c)||!before.homeComponents.contains(c))throw new IOException("Сохранённый домашний экран недоступен");
        run("cmd package set-home-activity --user 0 "+c);State after=refresh();
        if(!after.home.equals(c))throw new IOException("Прежний домашний экран не подтверждён");
        if(!journal.forgetHome(identity))throw new IOException("Экран восстановлен, запись журнала сохранена");return after;
    }
    public void homeKey() throws Exception {run("input keyevent 3");}
    private ProjectivyTools tools(State s){if(piTools==null)piTools=new ProjectivyTools(adb,journal,identity,s.uid);return piTools;}
    public ProjectivyTools.Report diagnoseProjectivy(boolean probeRoot) throws Exception {State s=refresh();ProjectivyTools t=tools(s);if(probeRoot)t.probeRoot();return t.inspect(s);}
    public State repairProjectivy(boolean withoutIntercept) throws Exception {
        State s=projectivy();ProjectivyTools t=tools(s);t.launchHome(s);
        // Re-check native HOME before changing the accessibility fallback.
        s=refresh();if(!Policy.packageOf(s.home).equals(Policy.PROJECTIVY))throw new IOException("Системный HOME не подтверждён. Перехват оставлен включённым.");
        if(withoutIntercept)t.disableIntercept();return refresh();
    }
    public State restoreProjectivyIntercept() throws Exception {State s=refresh();tools(s).restoreIntercept();return refresh();}
    public State prioritizeProjectivy(boolean enabled) throws Exception {State s=refresh();tools(s).priority(enabled);return refresh();}
    public State compileProjectivy(boolean reset) throws Exception {
        State s=refresh();ProjectivyTools.component(s);ProjectivyTools t=tools(s);ProjectivyTools.Report r=t.inspect(s);t.compile(reset,r.sdk);return refresh();
    }
    public State optimizeApp(String pkg,AppOptimization.Mode mode) throws Exception {
        State s=refresh();AppOptimization.eligible(s,pkg);ProjectivyTools t=tools(s);
        if(!t.rootReady())throw new IOException("Сначала проверь root на вкладке «Приставка»");
        String api=run("getprop ro.build.version.sdk").trim();
        if(!api.matches("[0-9]{1,3}"))throw new IOException("Не удалось определить версию Android");
        long free=-1;
        if(mode!=AppOptimization.Mode.REMOVE)free=DeviceHealth.parse(run("df -k /data | tail -n 1")).dataAvailable;
        t.optimizeApp(pkg,mode,Integer.parseInt(api),free);return refresh();
    }
    public long measureLaunch(String pkg) throws Exception {
        State s=refresh();AppOptimization.eligible(s,pkg);
        String component=appComponent(pkg);
        run("am force-stop --user 0 "+pkg);
        return AppOptimization.launchMillis(run("am start -W --user 0 -a android.intent.action.MAIN -n "+component),component);
    }
    public void openProjectivyStore() throws Exception {
        State s=refresh();if(!s.packages.contains("com.android.vending"))throw new IOException("На приставке нет Google Play. Открой официальные обновления через браузер.");
        run("am start -W -a android.intent.action.VIEW -p com.android.vending -d 'market://details?id="+Policy.PROJECTIVY+"'");
    }
    public State installApk(java.io.File file,String pkg,long versionCode,int minSdk,ApkInstall.Progress progress) throws Exception {
        State s=refresh();if(!Policy.validPackage(pkg)||versionCode<0)throw new IOException("Некорректный пакет APK");
        if(s.system.contains(pkg)&&!pkg.equals(Policy.PROJECTIVY))throw new IOException("Обновление системных компонентов через этот установщик заблокировано");
        String api=run("getprop ro.build.version.sdk").trim();if(!api.matches("[0-9]{1,3}")||minSdk>Integer.parseInt(api))throw new IOException("Для APK нужна более новая версия Android");
        ApkInstall.size(file.length());try(java.io.InputStream in=new java.io.FileInputStream(file)){adb.install(in,file.length(),progress);}
        State after=refresh();if(!after.packages.contains(pkg))throw new IOException("Установщик ответил успешно, но пакет не найден. Обнови список перед повтором.");
        java.util.regex.Matcher v=java.util.regex.Pattern.compile("versionCode=([0-9]+)").matcher(run("dumpsys package "+pkg+" | grep -m 1 'versionCode='"));
        if(!v.find()||!v.group(1).equals(Long.toString(versionCode)))throw new IOException("Установленная версия не совпала с выбранным APK. Проверь приложение на приставке.");return after;
    }
    public State closeApp(String pkg) throws Exception {
        State s=refresh();String reason=Policy.disableReason(pkg,s.homes,s.homesKnown,s.system.contains(pkg));
        if(!s.packages.contains(pkg)||reason!=null||s.disabled.contains(pkg))throw new IOException("Приложение нельзя закрыть этой командой");
        run("am force-stop --user 0 "+pkg);return refresh();
    }
    public void openApp(String pkg) throws Exception {
        State s=refresh();if(!Policy.validPackage(pkg)||!s.packages.contains(pkg)||s.disabled.contains(pkg))throw new IOException("Приложение недоступно");
        String component=appComponent(pkg);run("am start -W -a android.intent.action.MAIN -n "+component);
    }
    private String appComponent(String pkg) throws Exception {
        List<String> c=components(run("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -p "+pkg));
        if(c.size()!=1)c=components(run("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "+pkg));
        if(c.size()!=1||!Policy.packageOf(c.get(0)).equals(pkg))throw new IOException("Не найден экран запуска приложения");
        return c.get(0);
    }
    public State option(Option o,boolean enabled) throws Exception {
        refresh();String snapshot=journal.option(identity,o.name());
        if(enabled) {
            String[] current=o.parse(run(o.readCommand()));if(current==null)throw new IOException("Эта настройка не поддерживается прошивкой");
            if(o.matches(current)&&snapshot==null)return refresh();
            if(snapshot==null&&!journal.option(identity,o.name(),String.join("\n",current)))throw new IOException("Не удалось сохранить прежние значения. Настройка не изменена.");
            for(int i=0;i<o.keys.length;i++)run(o.writeCommand(i,o.target));
            if(!o.matches(o.parse(run(o.readCommand()))))throw new IOException("Прошивка не подтвердила настройку. Можно вернуть прежние значения.");
        } else {
            if(snapshot==null)throw new IOException("Нет сохранённого изменения этой настройки");
            String[] original=o.parse(snapshot);if(original==null)throw new IOException("Запись восстановления повреждена; команда не отправлена");
            for(int i=0;i<o.keys.length;i++)run(o.writeCommand(i,original[i]));
            if(!Arrays.equals(original,o.parse(run(o.readCommand()))))throw new IOException("Возврат не подтверждён. Запись восстановления сохранена.");
            if(!journal.forgetOption(identity,o.name()))throw new IOException("Настройка возвращена; запись восстановления сохранена");
        }
        return refresh();
    }
    public State restoreOptions() throws Exception {
        refresh();for(Option o:Option.values())if(journal.option(identity,o.name())!=null)option(o,false);return refresh();
    }
    public void remote(int key) throws Exception {
        switch(key){case 3:case 4:case 19:case 20:case 21:case 22:case 23:case 24:case 25:case 85:case 164:case 86:case 87:case 88:case 89:case 90:case 66:case 67:break;default:throw new IOException("Кнопка недоступна");}
        run("input keyevent "+key);
    }
    public void openSettings(int kind) throws Exception {
        String action;switch(kind){case 0:action="android.settings.SETTINGS";break;case 1:action="android.settings.WIFI_SETTINGS";break;case 2:action="android.settings.INTERNAL_STORAGE_SETTINGS";break;case 3:action="android.settings.DISPLAY_SETTINGS";break;case 4:action="android.settings.SOUND_SETTINGS";break;case 5:action="android.settings.ACCESSIBILITY_SETTINGS";break;case 6:action="android.settings.APPLICATION_SETTINGS";break;case 7:action="android.settings.DATE_SETTINGS";break;default:throw new IOException("Раздел недоступен");}
        run("am start -W -a "+action);
    }
    public State trimCache() throws Exception {
        cacheResult="";State before=refresh();long free=DeviceHealth.parse(run("df -k /data | tail -n 1")).dataAvailable;
        if(free<0)throw new IOException("Не удалось проверить свободное место. Очистка не запущена.");
        if(free>=1048576){cacheResult="Очистка не требуется: свободно "+DeviceHealth.size(free)+". Порог — 1 ГиБ.";return before;}
        run("pm trim-caches 1G");State after=refresh();long now=-1;
        try{now=DeviceHealth.parse(run("df -k /data | tail -n 1")).dataAvailable;}catch(Exception ignored){}
        cacheResult="Свободно до: "+DeviceHealth.size(free)+". После: "+DeviceHealth.size(now)+". "+(now<0?"Android завершил команду, но повторный замер недоступен.":now<=free?"Свободного места не стало больше. Android мог не найти подходящий кеш.":"Изменение свободного места включает также работу других приложений.");
        return after;
    }
    public static boolean visible(State s,String p,boolean mine){
        if(s.owned.contains(p))return true;
        if(mine||s.disabled.contains(p))return false;
        return Policy.disableReason(p,s.homes,s.homesKnown,s.system.contains(p))==null;
    }
    private static String hex(byte[] b) {StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
}
