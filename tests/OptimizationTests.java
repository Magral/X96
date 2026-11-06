// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;
import java.io.IOException;
import java.util.*;
import static ru.x96.local.CoreTests.*;

public final class OptimizationTests {
    static final String PKG="com.example.game",COMP=PKG+"/.Main";
    static final class Shell implements TvShell {
        final RepositoryTests.MemoryStore store=new RepositoryTests.MemoryStore();
        final RepositoryTests.FakeTv base=new RepositoryTests.FakeTv(store);
        final List<String> writes=new ArrayList<>();String api="30",uid="0",answer="Success",free="40000000",activity=COMP;boolean longCall,failCompile,su;
        public String shellLong(String c) throws Exception {longCall=true;return shell(c);}
        public String shell(String c) throws Exception {
            if(c.equals("id -u"))return uid;
            if(c.equals("su -c 'id -u'"))return su?"0":"2000";
            if(c.equals("getprop ro.build.version.sdk"))return api;
            if(c.equals("df -k /data | tail -n 1"))return "/dev/block/dm-1 60000000 10000000 "+free+" 17% /data";
            if(c.startsWith("cmd package compile ")||c.startsWith("su -c 'cmd package compile ")){writes.add(c);if(failCompile)throw new IOException("connection lost after send");return answer;}
            if(c.startsWith("cmd package resolve-activity --brief")&&c.contains(" -p "))return activity;
            if(c.startsWith("am force-stop ")){writes.add(c);return "";}
            if(c.startsWith("am start -W --user 0 ")){writes.add(c);return "Status: ok\nActivity: "+COMP+"\nTotalTime: 432\nWaitTime: 460\nComplete\n";}
            return base.shell(c);
        }
        TvRepository repo(){return new TvRepository(this,store);}
    }
    public static void main(String[] args) throws Exception {
        Shell s=new Shell();TvRepository r=s.repo();
        r.optimizeApp(PKG,AppOptimization.Mode.PROFILE);
        check(s.writes.equals(Arrays.asList("cmd package compile -m speed-profile -f "+PKG)),"profile exact reviewed command for selected package only");
        check(s.longCall,"compile uses long timeout");
        r.optimizeApp(PKG,AppOptimization.Mode.FULL);check(s.writes.get(1).equals("cmd package compile -m speed -f "+PKG),"full compile");
        s.free="0";r.optimizeApp(PKG,AppOptimization.Mode.REMOVE);check(s.writes.get(2).equals("cmd package compile -m verify -f "+PKG),"remove works at low space without deleting profiles");
        int n=s.writes.size();rejects(()->r.optimizeApp(PKG,AppOptimization.Mode.FULL));check(s.writes.size()==n,"low space prevents compile");
        s.free="unknown";rejects(()->r.optimizeApp(PKG,AppOptimization.Mode.PROFILE));check(s.writes.size()==n,"unknown space prevents compile");s.free="40000000";
        for(String p:Arrays.asList("com.android.systemui","com.vendor.home",Policy.PROJECTIVY,"com.google.android.videos","com.previously.off","com.missing.app","com.example.game;reboot")){rejects(()->r.optimizeApp(p,AppOptimization.Mode.FULL));rejects(()->r.measureLaunch(p));}
        check(s.writes.size()==n,"system, home, missing, disabled, injection packages cannot mutate");
        for(String sdk:Arrays.asList("25","34","bad")){s.api=sdk;rejects(()->r.optimizeApp(PKG,AppOptimization.Mode.FULL));}check(s.writes.size()==n,"unsupported versions cannot compile");s.api="30";
        s.base.homeUnknown=true;rejects(()->r.optimizeApp(PKG,AppOptimization.Mode.FULL));rejects(()->r.measureLaunch(PKG));check(s.writes.size()==n,"unknown home fails closed");s.base.homeUnknown=false;
        s.answer="Failure: Success is unavailable";rejects(()->r.optimizeApp(PKG,AppOptimization.Mode.PROFILE));check(s.writes.size()==n+1,"no false success substring and no retry");
        s.answer="Success";s.failCompile=true;rejects(()->r.optimizeApp(PKG,AppOptimization.Mode.PROFILE));check(s.writes.size()==n+2,"lost response never retries compilation");s.failCompile=false;
        s.base.id="different-tv";rejects(()->r.optimizeApp(PKG,AppOptimization.Mode.PROFILE));check(s.writes.size()==n+2,"device identity guard");s.base.id="tv-one";
        s.writes.clear();long ms=r.measureLaunch(PKG);check(ms==432,"Android measured time parsed");check(s.writes.equals(Arrays.asList("am force-stop --user 0 "+PKG,"am start -W --user 0 -a android.intent.action.MAIN -n "+COMP)),"one explicit force stop before timed launch");
        s.activity="com.other.app/.Main";s.writes.clear();rejects(()->r.measureLaunch(PKG));check(s.writes.isEmpty(),"bad resolver must not stop app");
        Shell unrooted=new Shell();unrooted.uid="2000";rejects(()->unrooted.repo().optimizeApp(PKG,AppOptimization.Mode.FULL));check(unrooted.writes.isEmpty(),"no implicit su request");
        unrooted.su=true;ProjectivyTools root=new ProjectivyTools(unrooted,unrooted.store,"device","2000");root.probeRoot();root.optimizeApp(PKG,AppOptimization.Mode.FULL,30,1048576);
        check(unrooted.writes.get(0).equals("su -c 'cmd package compile -m speed -f "+PKG+"'"),"explicitly approved su quotes entire fixed command");
        rejects(()->AppOptimization.command(PKG,null,30,1048576));rejects(()->AppOptimization.command("bad;id",AppOptimization.Mode.FULL,30,1048576));
        String timing="Status: ok\nActivity: "+COMP+"\nTotalTime: 432\n";
        check(AppOptimization.launchMillis(timing,COMP)==432,"valid timing");
        for(String invalid:Arrays.asList(timing.replace("ok","timeout"),timing.replace("432","0"),timing.replace("432","-1"),timing.replace("432","9999999999999999"),timing+"TotalTime: 500\n",timing+"Warning: Activity not started\n",timing.replace(COMP,"com.other/.Main"),"Status: ok\nActivity: "+COMP))rejects(()->AppOptimization.launchMillis(invalid,COMP));
        check(AppOptimization.launchMillis(timing.replace(COMP,PKG+"/"+PKG+".Main"),COMP)==432,"full and short component forms equivalent");
        check(AppOptimization.launchMillis(timing+"LaunchState: COLD\n",COMP)==432,"cold launch accepted");
        rejects(()->AppOptimization.launchMillis(timing+"LaunchState: WARM\n",COMP));
        rejects(()->AppOptimization.launchMillis(timing+"Activity: "+COMP+"\n",COMP));
        System.out.println("OptimizationTests: "+checks+" checks passed");
    }
}
