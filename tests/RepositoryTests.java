// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;
import java.io.IOException;
import java.util.*;
import static ru.x96.local.CoreTests.*;

public final class RepositoryTests {
    static final class MemoryStore implements RecoveryStore {
        final Map<String,Set<String>> off=new HashMap<>();final Map<String,String> homes=new HashMap<>(),snapshots=new HashMap<>();boolean allowWrite=true;
        public Set<String> disabled(String d){return new HashSet<>(off.getOrDefault(d,Collections.emptySet()));}
        public boolean disabled(String d,Set<String> p){if(!allowWrite)return false;off.put(d,new HashSet<>(p));return true;}
        public String home(String d){return homes.get(d);}
        public boolean home(String d,String c){if(!allowWrite)return false;homes.put(d,c);return true;}
        public boolean forgetHome(String d){if(!allowWrite)return false;homes.remove(d);return true;}
        public String option(String d,String id){return snapshots.get(d+id);}
        public boolean option(String d,String id,String s){if(!allowWrite)return false;snapshots.put(d+id,s);return true;}
        public boolean forgetOption(String d,String id){if(!allowWrite)return false;snapshots.remove(d+id);return true;}
        boolean recorded(String pkg){return off.values().stream().anyMatch(s->s.contains(pkg));}
    }
    static final class FakeTv implements TvShell {
        final MemoryStore journal;Set<String> off=new HashSet<>(Arrays.asList("com.previously.off"));
        String id="tv-one",home="com.vendor.home/.Main",model="X96 Max+ Ultra";boolean dropAfterDisable=false,homeUnknown=false;
        int mutations=0;
        final Map<String,String> settings=new HashMap<>();boolean failSetting=false;
        FakeTv(MemoryStore j){journal=j;for(Option o:Option.values())for(String k:o.keys)settings.put(k,"1");}
        public String shell(String c) throws Exception {
            if(c.startsWith("printf 'X96_READ_"))return SnapshotTests.respond(c,this);
            if(c.startsWith("printf 'X96_OPTION_")){StringJoiner out=new StringJoiner("\n");for(Option o:Option.values()){out.add("X96_OPTION_"+o.name());out.add(shell(o.readCommand()));}return out.toString();}
            if(c.startsWith("settings --user 0 get ")){StringJoiner out=new StringJoiner("\n");for(String part:c.split("; ")){String[] words=part.split(" ");out.add(settings.getOrDefault(words[5],"null"));}return out.toString();}
            if(c.startsWith("settings --user 0 put ")||c.startsWith("settings --user 0 delete ")){String[] w=c.split(" ");check(!journal.snapshots.isEmpty(),"settings journal BEFORE mutation");settings.put(w[5],w[3].equals("delete")?"null":w[6]);mutations++;if(failSetting)throw new IOException("setting interrupted");return "";}
            if(c.equals("getprop ro.serialno; getprop ro.boot.serialno; settings get secure android_id; getprop ro.product.device"))return id;
            if(c.equals("getprop ro.product.model"))return model;
            if(c.equals("getprop ro.build.version.release"))return "11";
            if(c.equals("id -u"))return "0";
            if(c.equals("pm list packages --user 0"))return "package:com.example.game\npackage:com.vendor.home\npackage:com.spocky.projengmenu\npackage:com.android.systemui\npackage:com.vendor.unknown\npackage:com.previously.off\npackage:com.google.android.videos";
            if(c.equals("pm list packages -s --user 0"))return "package:com.android.systemui\npackage:com.vendor.home\npackage:com.vendor.unknown\npackage:com.google.android.videos";
            if(c.equals("pm list packages -d --user 0")){StringBuilder b=new StringBuilder();for(String p:off)b.append("package:").append(p).append('\n');return b.toString();}
            if(c.startsWith("cmd package query-activities --brief"))return homeUnknown?"":"2 activities found:\ncom.vendor.home/.Main\ncom.spocky.projengmenu/.Main";
            if(c.startsWith("cmd package resolve-activity --brief"))return homeUnknown?"No activity found":home;
            if(c.startsWith("pm disable-user --user 0 ")){String p=c.substring("pm disable-user --user 0 ".length());check(journal.recorded(p),"journal written BEFORE command");off.add(p);mutations++;if(dropAfterDisable)throw new IOException("link lost");return "disabled-user";}
            if(c.startsWith("pm enable --user 0 ")){off.remove(c.substring("pm enable --user 0 ".length()));mutations++;return "enabled";}
            if(c.startsWith("cmd package set-home-activity --user 0 ")){check(!journal.homes.isEmpty(),"old Home recorded BEFORE change");home=c.substring("cmd package set-home-activity --user 0 ".length());mutations++;return "Success";}
            if(c.equals("input keyevent 3"))return "";
            throw new AssertionError("Unreviewed command: "+c);
        }
    }
    public static void main(String[] args) throws Exception {
        MemoryStore store=new MemoryStore();FakeTv tv=new FakeTv(store);TvRepository r=new TvRepository(tv,store);
        check(r.refresh().uid.equals("0"),"root detected without requesting su");
        rejects(()->r.restore("com.previously.off"));check(tv.off.contains("com.previously.off"),"pre-existing disabled app preserved");
        for(String p:Arrays.asList("com.android.systemui","com.vendor.home","com.vendor.unknown","com.spocky.projengmenu","com.example.game;reboot"))rejects(()->r.disable(p));
        check(tv.mutations==0,"protected packages never mutated");
        store.allowWrite=false;rejects(()->r.disable("com.example.game"));check(tv.mutations==0,"no mutation if journal write fails");store.allowWrite=true;
        tv.dropAfterDisable=true;rejects(()->r.disable("com.example.game"));check(store.recorded("com.example.game")&&tv.off.contains("com.example.game"),"disconnect retains recovery record");
        tv.dropAfterDisable=false;TvRepository recovered=new TvRepository(tv,store);
        check(recovered.refresh().owned.contains("com.example.game"),"new session recovers journal");
        check(!recovered.restore("com.example.game").disabled.contains("com.example.game"),"restore after disconnect");check(!store.recorded("com.example.game"),"remove record after successful restore");
        recovered.projectivy();check(tv.home.startsWith(Policy.PROJECTIVY+"/"),"Projectivy selected");check(tv.off.size()==1,"no factory launcher disabled");
        recovered.restoreHome();check(tv.home.equals("com.vendor.home/.Main"),"old Home restored");
        tv.homeUnknown=true;int count=tv.mutations;rejects(()->recovered.disable("com.example.game"));check(tv.mutations==count,"unknown home fails closed");tv.homeUnknown=false;
        tv.id="tv-two";rejects(()->recovered.disable("com.example.game"));check(tv.mutations==count,"different TV at same IP cannot mutate");
        options();
        System.out.println("PASS: "+checks+" repository checks (write-ahead recovery, storage failure, identity, protected apps, Home rollback)");
    }
    static void options() throws Exception {
        MemoryStore store=new MemoryStore();FakeTv tv=new FakeTv(store);TvRepository r=new TvRepository(tv,store);
        TvRepository.State state=r.refresh();check(!TvRepository.visible(state,"com.android.systemui",false),"protected services hidden");check(!TvRepository.visible(state,"com.vendor.unknown",false),"unknown system services hidden");
        check(TvRepository.visible(state,"com.example.game",false),"actionable user app visible");check(!TvRepository.visible(state,"com.previously.off",false),"no-action disabled apps hidden");
        store.allowWrite=false;rejects(()->r.option(Option.ANIMATIONS,true));check(tv.mutations==0,"no settings changes without stored original");store.allowWrite=true;
        tv.settings.put("transition_animation_scale","1.5");tv.settings.put("animator_duration_scale","null");
        r.option(Option.ANIMATIONS,true);check(tv.settings.get("window_animation_scale").equals("0.5"),"animation enabled");
        new TvRepository(tv,store).option(Option.ANIMATIONS,false);check(tv.settings.get("transition_animation_scale").equals("1.5")&&tv.settings.get("animator_duration_scale").equals("null"),"restore mixed and absent original values in new session");
        check(store.snapshots.isEmpty(),"snapshot removed after verified restore");
        tv.failSetting=true;rejects(()->r.option(Option.ANIMATIONS,true));check(!store.snapshots.isEmpty(),"partial failure keeps snapshot");tv.failSetting=false;r.option(Option.ANIMATIONS,false);
        tv.settings.put("screensaver_enabled","null");check(!r.refresh().options.get(Option.NO_SAVER).supported,"unsupported feature marked unavailable");int before=tv.mutations;rejects(()->r.option(Option.NO_SAVER,true));check(tv.mutations==before,"unsupported setting not changed");
        r.option(Option.LARGE_TEXT,true);r.option(Option.QUIET,true);r.restoreOptions();check(store.snapshots.isEmpty()&&tv.settings.get("font_scale").equals("1")&&tv.settings.get("sound_effects_enabled").equals("1"),"restore all feature values");
        rejects(()->r.remote(26));rejects(()->r.openSettings(9));rejects(()->Option.QUIET.writeCommand(0,"0; reboot"));
        tv.settings.put("font_scale","1.15");before=tv.mutations;r.option(Option.LARGE_TEXT,true);check(tv.mutations==before&&store.snapshots.isEmpty(),"already active setting is not claimed as our change");
    }
}
