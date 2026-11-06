// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import static ru.x96.local.CoreTests.*;

/** Binary protocol tests without network, APK execution or a physical TV. */
public final class InstallTests {
    // TCP close drains already-written bytes; closing PipedInputStream instead drops its buffer.
    static final class DrainingSocket extends CoreTests.FakeSocket {
        DrainingSocket() throws IOException {super();}
        @Override public void close() throws IOException {phoneOutput.close();serverOutput.close();}
    }
    static byte[] payload(int size){byte[] b=new byte[size];for(int i=0;i<size;i++)b[i]=(byte)(i*31);return b;}
    static void stream(KeyPair key,String mode) throws Exception {
        DrainingSocket socket=new DrainingSocket();ExecutorService pool=Executors.newSingleThreadExecutor();
        byte[] content=payload(17531);long size=content.length;byte[] input=mode.equals("short")?Arrays.copyOf(content,4200):mode.equals("extra")?Arrays.copyOf(content,content.length+1):content;
        ByteArrayOutputStream received=new ByteArrayOutputStream();
        Future<?> server=pool.submit(()->{try{
            CoreTests.authenticate(socket,key);AdbProtocol.Packet open=AdbProtocol.read(socket.serverInput);
            check(new String(open.data,StandardCharsets.UTF_8).equals("exec:cmd package install -r --user 0 -S "+size+"\0"),"fixed install command: no root, downgrade, grant or signature-bypass flags");
            AdbProtocol.send(socket.serverOutput,AdbProtocol.OKAY,7,1,new byte[0]);
            while(received.size()<size){
                AdbProtocol.Packet p=AdbProtocol.read(socket.serverInput);if(p.command!=AdbProtocol.WRTE||p.arg0!=1||p.arg1!=7)throw new AssertionError("bad upload packet");received.write(p.data);
                if(mode.equals("early"))break;
                AdbProtocol.send(socket.serverOutput,AdbProtocol.OKAY,7,1,new byte[0]);
            }
            String text=mode.equals("rejected")?"Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]\n":"Success\n";
            AdbProtocol.send(socket.serverOutput,AdbProtocol.WRTE,7,1,text.getBytes(StandardCharsets.UTF_8));
            check(AdbProtocol.read(socket.serverInput).command==AdbProtocol.OKAY,"installer output acknowledged");
            if(!mode.equals("rejected")){AdbProtocol.send(socket.serverOutput,AdbProtocol.CLSE,7,1,new byte[0]);AdbProtocol.read(socket.serverInput);}
        }catch(IOException e){if(!mode.equals("short")&&!mode.equals("extra")&&!mode.equals("rejected"))throw new RuntimeException(e);}catch(Exception e){throw new RuntimeException(e);}});
        try{
            AdbClient client=new AdbClient("192.168.1.50",5555,key,()->socket);List<Long> progress=new ArrayList<>();
            CoreTests.Throwing install=()->client.install(new ByteArrayInputStream(input),size,(sent,total)->{if(total!=size)throw new AssertionError("wrong total");progress.add(sent);});
            if(mode.equals("success"))install.run();else rejects(install);
            server.get(5,TimeUnit.SECONDS);
            if(mode.equals("success")||mode.equals("rejected"))check(Arrays.equals(content,received.toByteArray()),"binary content including zero bytes preserved");
            if(mode.equals("success"))check(progress.get(progress.size()-1)==size,"progress finishes after acknowledged complete transfer");
            if(mode.equals("short")||mode.equals("extra")||mode.equals("early"))check(received.size()<size,"incomplete or changed file never completes upload");
        }finally{socket.close();pool.shutdownNow();}
    }
    static void cancel(KeyPair key) throws Exception {
        DrainingSocket socket=new DrainingSocket();AdbClient c=new AdbClient("192.168.1.50",5555,key,()->socket);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch first=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        Future<?> server=pool.submit(()->{try{CoreTests.authenticate(socket,key);AdbProtocol.read(socket.serverInput);AdbProtocol.send(socket.serverOutput,AdbProtocol.OKAY,7,1,new byte[0]);AdbProtocol.read(socket.serverInput);first.countDown();cancelled.await(5,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}});
        Future<?> client=pool.submit(()->{try{c.install(new ByteArrayInputStream(payload(20000)),20000,(a,b)->{});throw new AssertionError("cancelled upload reported success");}catch(Exception expected){}});
        try{check(first.await(5,TimeUnit.SECONDS),"binary upload starts");c.cancel();cancelled.countDown();client.get(3,TimeUnit.SECONDS);server.get(3,TimeUnit.SECONDS);rejects(()->c.install(new ByteArrayInputStream(new byte[1]),1,(a,b)->{}));}finally{cancelled.countDown();socket.close();pool.shutdownNow();}
    }
    static void boundsAndErrors() throws Exception {
        rejects(()->ApkInstall.size(0));rejects(()->ApkInstall.size(ApkInstall.MAX_BYTES+1));
        ByteArrayOutputStream copy=new ByteArrayOutputStream();byte[] source=payload(200000);check(ApkInstall.copy(new ByteArrayInputStream(source),copy,()->false)==source.length&&Arrays.equals(copy.toByteArray(),source),"bounded file copy is byte-exact");
        ByteArrayOutputStream empty=new ByteArrayOutputStream();rejects(()->ApkInstall.copy(new ByteArrayInputStream(source),empty,()->true));check(empty.size()==0,"cancelled selection writes no bytes");
        check(ApkInstall.failure("INSTALL_FAILED_UPDATE_INCOMPATIBLE").getMessage().contains("Подпись"),"signature mismatch explanation");
        check(ApkInstall.failure("INSTALL_FAILED_NO_MATCHING_ABIS").getMessage().contains("процессору"),"ABI mismatch explanation");
        check(ApkInstall.failure("INSTALL_FAILED_VERSION_DOWNGRADE").getMessage().contains("старее"),"downgrade explanation");
        rejects(()->ApkInstall.success("Success\nFailure [bad]"));rejects(()->ApkInstall.success(""));
    }
    static void repository() throws Exception {
        RepositoryTests.MemoryStore journal=new RepositoryTests.MemoryStore();RepositoryTests.FakeTv tv=new RepositoryTests.FakeTv(journal);int[] installs={0};
        TvShell link=new TvShell(){public String shell(String cmd) throws Exception{if(cmd.equals("getprop ro.build.version.sdk"))return "30";if(cmd.equals("dumpsys package com.example.game | grep -m 1 'versionCode='"))return "versionCode=42 minSdk=26";return tv.shell(cmd);}public void install(InputStream in,long size,ApkInstall.Progress p) throws Exception{installs[0]++;byte[] data=new byte[(int)size];new DataInputStream(in).readFully(data);check(Arrays.equals(data,payload(100)),"repository streams selected file");}};
        File f=File.createTempFile("x96-test-", ".apk");try{try(FileOutputStream out=new FileOutputStream(f)){out.write(payload(100));}
            TvRepository r=new TvRepository(link,journal);rejects(()->r.installApk(f,"com.android.systemui",42,26,(a,b)->{}));rejects(()->r.installApk(f,"com.example.game",42,31,(a,b)->{}));check(installs[0]==0,"system update and newer SDK refused before upload");
            check(r.installApk(f,"com.example.game",42,26,(a,b)->{}).packages.contains("com.example.game"),"installed package and version verified");
        }finally{f.delete();}
    }
    public static void main(String[] args) throws Exception {
        boundsAndErrors();repository();KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);KeyPair key=gen.generateKeyPair();
        for(String mode:new String[]{"success","short","extra","early","rejected"})stream(key,mode);cancel(key);
        System.out.println("PASS: "+checks+" installation checks (binary integrity, ACK progress, cancellation, truncated files, system/SDK guards, Android failures)");
    }
}
