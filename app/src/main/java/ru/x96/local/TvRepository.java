// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** All TV commands live here. No user shell, root elevation, downloads or automatic cleanup. */
public final class TvRepository {
    private final TvShell adb;
    private final RecoveryStore journal;
    private String identity="";
    private static final String QUERY_HOME="cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME";
    private static final String RESOLVE_HOME="cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME";
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
    private static Set<String> packages(String output) {
        Set<String> result=new TreeSet<>();for(String line:output.split("\n"))if(line.startsWith("package:")){String p=line.substring(8).trim();if(Policy.validPackage(p))result.add(p);}return result;
    }
    public static List<String> components(String output) {
        Set<String> result=new TreeSet<>();for(String line:output.split("\n")){String c=line.trim();if(Policy.validComponent(c))result.add(c);}return new ArrayList<>(result);
    }
    public State refresh() throws Exception {
        String ident=run("getprop ro.serialno; getprop ro.boot.serialno; settings get secure android_id; getprop ro.product.device");
        if(ident.replace("null","").replace("unknown","").trim().isEmpty())throw new IOException("Не удалось определить приставку");
        String next=hex(MessageDigest.getInstance("SHA-256").digest(ident.getBytes(StandardCharsets.UTF_8)));
        if(!identity.isEmpty()&&!identity.equals(next))throw new IOException("По этому адресу другая приставка. Подключись заново.");
        identity=next;
        State s=new State();s.model=run("getprop ro.product.model");s.android=run("getprop ro.build.version.release");s.uid=run("id -u");
        s.packages=packages(run("pm list packages --user 0"));if(s.packages.isEmpty())throw new IOException("Приставка не вернула список приложений");
        s.disabled=packages(run("pm list packages -d --user 0"));s.system=packages(run("pm list packages -s --user 0"));
        if(s.system.isEmpty())throw new IOException("Не удалось определить системные приложения");
        s.homeComponents=components(run(QUERY_HOME));s.homes=new HashSet<>();
        for(String c:s.homeComponents)s.homes.add(Policy.packageOf(c));
        List<String> current=components(run(RESOLVE_HOME));s.home=current.size()==1?current.get(0):"";
        s.homesKnown=!s.home.isEmpty()&&s.homeComponents.contains(s.home)&&!s.homes.isEmpty();if(!s.home.isEmpty())s.homes.add(Policy.packageOf(s.home));
        s.owned=new HashSet<>(journal.disabled(identity));s.homeSaved=journal.home(identity)!=null;
        for(Option o:Option.values()) {String[] values;try{values=o.parse(run(o.readCommand()));}catch(Exception e){values=null;}s.options.put(o,new Option.Status(values!=null,o.matches(values),journal.option(identity,o.name())!=null));}
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
        switch(key){case 3:case 4:case 19:case 20:case 21:case 22:case 23:case 24:case 25:case 85:case 164:break;default:throw new IOException("Кнопка недоступна");}
        run("input keyevent "+key);
    }
    public void openSettings(int kind) throws Exception {
        String action;switch(kind){case 0:action="android.settings.SETTINGS";break;case 1:action="android.settings.WIFI_SETTINGS";break;case 2:action="android.settings.INTERNAL_STORAGE_SETTINGS";break;default:throw new IOException("Раздел недоступен");}
        run("am start -W -a "+action);
    }
    public State trimCache() throws Exception {refresh();run("pm trim-caches 1G");return refresh();}
    public static boolean visible(State s,String p,boolean mine){
        if(s.owned.contains(p))return true;
        if(mine||s.disabled.contains(p))return false;
        return Policy.disableReason(p,s.homes,s.homesKnown,s.system.contains(p))==null;
    }
    private static String hex(byte[] b) {StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
}
