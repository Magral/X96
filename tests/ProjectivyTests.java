// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;
import java.io.IOException;
import java.util.*;
import static ru.x96.local.CoreTests.*;

public final class ProjectivyTests {
    static final String PI=Policy.PROJECTIVY+"/.services.ProjectivyAccessibilityService",OTHER="com.reader/.Service",NEW="com.other/.Service",BOOT="12345678-1234-1234-1234-123456789abc";
    static final class FakeShell implements TvShell {
        String uid="0",enabled=OTHER+":"+PI,pid="234",start="777",boot=BOOT;int score=600,writes=0,compiles=0;boolean suAllowed=false,failWrite=false,slow=false;
        final List<String> commands=new ArrayList<>();
        public String shellLong(String c) throws Exception {slow=true;return shell(c);}
        public String shell(String c) throws Exception {
            commands.add(c);
            if(c.equals("id -u"))return uid;
            if(c.equals("su -c 'id -u'"))return suAllowed?"0":"2000";
            if(c.startsWith("su -c 'cmd package compile ")){if(!suAllowed)throw new IOException("su denied");c=c.substring(7,c.length()-1);}
            if(c.equals("settings --user 0 get secure enabled_accessibility_services"))return enabled;
            String put="settings --user 0 put secure enabled_accessibility_services ";
            if(c.startsWith(put)){String value=c.substring(put.length());check(value.startsWith("'")&&value.endsWith("'"),"service values shell quoted");enabled=value.substring(1,value.length()-1);writes++;return "";}
            if(c.equals("pidof "+Policy.PROJECTIVY+" || true"))return pid;
            if(c.startsWith("cat /proc/"+pid+"/stat; ")){
                List<String> fields=new ArrayList<>();fields.add("S");for(int i=1;i<19;i++)fields.add("0");fields.add(start);fields.add("0");
                return pid+" (projengmenu) "+String.join(" ",fields)+"\n"+Policy.PROJECTIVY+"\n"+score+"\n"+boot;
            }
            if(c.startsWith("exec 3> /proc/")){
                check(c.contains(" = '"+start+"' ]")&&c.contains(" = '"+boot+"' ]"),"guard includes process start and boot identity");
                check(c.contains("cmdline")&&c.contains(" = '"+Policy.PROJECTIVY+"' ]"),"guard checks full process name");
                check(c.startsWith("exec 3> /proc/"+pid+"/oom_score_adj &&")&&c.endsWith(" >&3"),"write uses already opened proc descriptor");
                int n=Integer.parseInt(c.substring(c.indexOf("printf '%s' ")+12,c.lastIndexOf(" >&3")));score=n;writes++;if(failWrite)throw new IOException("lost after write");return "";
            }
            if(c.startsWith("cmd package compile ")){check(c.endsWith(" "+Policy.PROJECTIVY),"only Projectivy compiled");compiles++;return "Success";}
            if(c.startsWith("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n "))return "Status: ok";
            throw new IOException("unsupported diagnostic command");
        }
    }
    static ProjectivyTools tools(FakeShell s,RepositoryTests.MemoryStore m){return new ProjectivyTools(s,m,"device-one",s.uid);}
    public static void main(String[] args) throws Exception {
        check(ProjectivyTools.oldVersion("4.72")&&!ProjectivyTools.oldVersion("4.73")&&!ProjectivyTools.oldVersion("4.100")&&!ProjectivyTools.oldVersion("5.0"),"4.73 fix version comparison");
        rejects(()->ProjectivyTools.services("com.bad/x;reboot"));rejects(()->ProjectivyTools.snapshot("123|777|"+BOOT+"|-200;id"));
        check(Option.SLEEP_30.valid("-1")&&Option.SLEEP_30.valid("2147483647")&&!Option.SLEEP_30.valid("2147483648"),"sleep timeout bounds");
        FakeShell s=new FakeShell();RepositoryTests.MemoryStore j=new RepositoryTests.MemoryStore();ProjectivyTools t=tools(s,j);
        j.allowWrite=false;rejects(()->t.disableIntercept());check(s.writes==0,"intercept not changed without journal");j.allowWrite=true;
        t.disableIntercept();check(s.enabled.equals(OTHER)&&t.hasInterceptRecord(),"only Projectivy interceptor removed");
        s.enabled=OTHER+":"+NEW;t.restoreIntercept();check(ProjectivyTools.services(s.enabled).containsAll(Arrays.asList(PI,OTHER,NEW)),"restore merges new unrelated services");check(!t.hasInterceptRecord(),"intercept snapshot removed after verification");
        j.option("device-one",ProjectivyTools.ACCESS,"com.malicious/.Service");int before=s.writes;rejects(()->t.restoreIntercept());check(s.writes==before,"foreign saved service rejected");j.forgetOption("device-one",ProjectivyTools.ACCESS);
        t.priority(true);check(s.score==-200&&t.hasPriorityRecord(),"root priority applied and saved");
        t.priority(false);check(s.score==600&&!t.hasPriorityRecord(),"original priority restored");
        t.priority(true);s.start="888";s.score=500;before=s.writes;t.priority(false);check(s.writes==before&&s.score==500&&!t.hasPriorityRecord(),"reused PID never written on restore");
        t.priority(true);s.boot="aaaaaaaa-1234-1234-1234-123456789abc";s.score=400;before=s.writes;t.priority(false);check(s.writes==before&&s.score==400,"previous boot process never written");
        s.score=-800;before=s.writes;t.priority(true);check(s.writes==before&&!t.hasPriorityRecord(),"do not lower an already stronger priority");
        s.score=600;s.failWrite=true;rejects(()->t.priority(true));check(t.hasPriorityRecord(),"priority snapshot survives lost connection after write");s.failWrite=false;
        new ProjectivyTools(s,j,"device-one","0").priority(false);check(s.score==600,"new session recovers process priority");
        j.allowWrite=false;before=s.writes;rejects(()->t.priority(true));check(s.writes==before,"priority write blocked if snapshot cannot be saved");j.allowWrite=true;
        s.uid="2000";ProjectivyTools unprivileged=tools(s,j);rejects(()->unprivileged.compile(false,30));check(s.compiles==0,"root actions refused without confirmed root");
        rejects(()->unprivileged.probeRoot());check(!unprivileged.rootReady(),"su refusal is respected");s.suAllowed=true;unprivileged.probeRoot();check(unprivileged.rootReady(),"su authorization verified by uid");unprivileged.compile(false,30);check(s.slow&&s.compiles==1,"su compilation uses bounded long transport");
        rejects(()->unprivileged.compile(false,35));check(s.compiles==1,"unsupported Android ART path not attempted");
        TvRepository.State state=new TvRepository.State();state.homeComponents=Arrays.asList("com.vendor/.Home",Policy.PROJECTIVY+"/.Home");state.home=Policy.PROJECTIVY+"/.Home";
        t.launchHome(state);check(s.commands.get(s.commands.size()-1).contains("-c android.intent.category.HOME"),"repair explicitly starts a HOME task");
        state.homeComponents=Arrays.asList("com.vendor/.Home");rejects(()->t.launchHome(state));
        System.out.println("PASS: "+checks+" Projectivy/root checks (service merge, snapshot before mutation, PID reuse, boot identity, root refusal, ART compatibility)");
    }
}
