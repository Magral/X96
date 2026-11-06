// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;

import java.io.*;
import java.util.*;
import static ru.x96.local.CoreTests.*;

public final class SnapshotTests {
    static final String TOKEN="0123456789abcdef0123456789abcdef";
    /** Stub transport executes known reads separately and emits the wire format. */
    static String respond(String command,TvShell source) throws Exception {
        String prefix="printf 'X96_READ_";if(!command.startsWith(prefix))throw new AssertionError("not a snapshot");
        String nonce=command.substring(prefix.length(),prefix.length()+32);
        if(!command.equals(TvSnapshot.command(nonce)))throw new AssertionError("unknown batched command");
        StringBuilder b=new StringBuilder();
        for(TvSnapshot.Field field:TvSnapshot.Field.values()){
            b.append(TvSnapshot.begin(nonce,field)).append('\n');
            try{b.append(source.shell(field.command));b.append('\n').append(TvSnapshot.end(nonce,field)).append(":0\n");}
            catch(IOException e){b.append("read failed\n").append(TvSnapshot.end(nonce,field)).append(":1\n");}
        }
        return b.toString();
    }
    static String fixture(String nonce){StringBuilder b=new StringBuilder();for(TvSnapshot.Field f:TvSnapshot.Field.values())b.append(TvSnapshot.begin(nonce,f)).append("\n").append(f==TvSnapshot.Field.DISABLED?"":f.name()+"\nsecond line\n").append('\n').append(TvSnapshot.end(nonce,f)).append(":0\n");return b.toString();}
    public static void main(String[] args) throws Exception {
        String raw=fixture(TOKEN);Map<TvSnapshot.Field,String> parsed=TvSnapshot.parse(raw,TOKEN);
        check(parsed.size()==9,"all required sections present");check(parsed.get(TvSnapshot.Field.DISABLED).equals(""),"empty disabled list is valid");
        check(parsed.get(TvSnapshot.Field.MODEL).equals("MODEL\nsecond line"),"multiline output preserved");
        check(TvSnapshot.parse(raw.replace("\n","\r\n"),TOKEN).equals(parsed),"CRLF accepted");
        check(TvSnapshot.parse(raw.trim(),TOKEN).equals(parsed),"ADB outer trim accepted");
        for(TvSnapshot.Field f:TvSnapshot.Field.values()){
            rejects(()->TvSnapshot.parse(raw.replace(TvSnapshot.end(TOKEN,f)+":0",TvSnapshot.end(TOKEN,f)+":1"),TOKEN));
            rejects(()->TvSnapshot.parse(raw.replace(TvSnapshot.begin(TOKEN,f),"MISSING"),TOKEN));
        }
        rejects(()->TvSnapshot.parse(raw.substring(0,raw.length()-12),TOKEN));
        rejects(()->TvSnapshot.parse(raw+"unexpected output",TOKEN));
        rejects(()->TvSnapshot.parse("banner\n"+raw,TOKEN));
        rejects(()->TvSnapshot.parse(raw,TvSnapshot.nonce()));
        rejects(()->TvSnapshot.parse(raw.replace(":0\n",":00\n"),TOKEN));
        rejects(()->TvSnapshot.parse(raw.replace(":0\n",":0; reboot\n"),TOKEN));
        rejects(()->TvSnapshot.command("bad'; reboot"));
        rejects(()->TvSnapshot.parse(null,TOKEN));
        check(!TvSnapshot.nonce().equals(TvSnapshot.nonce()),"fresh nonce per snapshot");
        // Execute the exact generated script in a real POSIX shell with stub Android commands.
        String functions="getprop() { printf '%s\\n' \"$1\"; }; settings() { printf 'android-id\\n'; }; id() { printf '0\\n'; }; pm() { printf 'package:com.example.app\\n'; }; cmd() { printf 'com.vendor/.Home\\n'; }; ";
        Process p=new ProcessBuilder("sh","-c",functions+TvSnapshot.command(TOKEN)).redirectErrorStream(true).start();
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int n;while((n=p.getInputStream().read(buffer))!=-1)out.write(buffer,0,n);
        check(p.waitFor()==0,"generated shell script executes");Map<TvSnapshot.Field,String> real=TvSnapshot.parse(new String(out.toByteArray(),"UTF-8"),TOKEN);
        check(real.get(TvSnapshot.Field.UID).equals("0")&&real.get(TvSnapshot.Field.PACKAGES).equals("package:com.example.app"),"real shell framing parsed");
        Process failure=new ProcessBuilder("sh","-c",functions+"pm() { printf 'denied\\n'; return 7; }; "+TvSnapshot.command(TOKEN)).redirectErrorStream(true).start();
        ByteArrayOutputStream failed=new ByteArrayOutputStream();while((n=failure.getInputStream().read(buffer))!=-1)failed.write(buffer,0,n);failure.waitFor();
        rejects(()->TvSnapshot.parse(new String(failed.toByteArray(),"UTF-8"),TOKEN));
        repository();
        malformedLists();
        System.out.println("SnapshotTests: "+checks+" checks passed");
    }
    static void malformedLists() throws Exception {
        for(String bad:new String[]{"package:com.android.systemui\nerror: denied","package:com.android.systemui\npackage:bad;reboot","package:com.nonexistent.system"}){
            RepositoryTests.MemoryStore store=new RepositoryTests.MemoryStore();RepositoryTests.FakeTv tv=new RepositoryTests.FakeTv(store);
            TvShell single=c->c.equals("pm list packages -s --user 0")?bad:tv.shell(c);
            TvShell transport=c->c.startsWith("printf 'X96_READ_")?respond(c,single):single.shell(c);
            TvRepository repo=new TvRepository(transport,store);rejects(()->repo.disable("com.example.game"));
            check(tv.mutations==0&&store.off.isEmpty(),"malformed or inconsistent system list never permits mutation");
        }
    }
    static void repository() throws Exception {
        RepositoryTests.MemoryStore journal=new RepositoryTests.MemoryStore();RepositoryTests.FakeTv tv=new RepositoryTests.FakeTv(journal);
        int[] calls={0};boolean[] bad={false},optionsFail={false};
        TvShell transport=cmd->{calls[0]++;if(cmd.startsWith("printf 'X96_READ_")){String answer=respond(cmd,tv);return bad[0]?answer.substring(0,answer.length()/2):answer;}if(optionsFail[0])throw new IOException("optional settings unavailable");return tv.shell(cmd);};
        TvRepository repo=new TvRepository(transport,journal);TvRepository.State s=repo.refresh();
        check(calls[0]==2,"refresh uses two transport calls instead of ten");
        check(s.homesKnown&&s.system.contains("com.android.systemui")&&s.packages.contains("com.example.game"),"batched state retains safety metadata");
        bad[0]=true;rejects(()->repo.disable("com.example.game"));check(tv.mutations==0&&journal.off.isEmpty(),"partial snapshot cannot mutate or write recovery journal");bad[0]=false;
        optionsFail[0]=true;s=repo.refresh();check(!s.options.get(Option.QUIET).supported&&s.homesKnown,"optional settings failure does not discard core state");optionsFail[0]=false;
        tv.id="another TV";rejects(()->repo.disable("com.example.game"));check(tv.mutations==0,"identity still checked before every mutation");
    }
}
