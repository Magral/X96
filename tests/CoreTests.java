// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;

/** Host tests; the simulated ADB server has no network and never touches a real TV. */
public final class CoreTests {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    interface Throwing {void run() throws Exception;}
    static void rejects(Throwing f) throws Exception {boolean failed=false;try{f.run();}catch(Exception e){failed=true;}check(failed,"must reject invalid input");}
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static void policy() throws Exception {
        for(String ip:Arrays.asList("192.168.1.2","10.0.0.5","172.16.0.1","172.31.255.2"))check(Policy.privateAddress(ip).length==4,ip);
        for(String ip:Arrays.asList("127.0.0.1","8.8.8.8","169.254.1.1","172.32.0.1","0.0.0.0","192.168.001.2","192.168.1.256","example.org","192.168.1.1.evil.test","::1","192.168.1.1;curl"))rejects(()->Policy.privateAddress(ip));
        rejects(()->Policy.port("22"));rejects(()->Policy.port("65536"));check(Policy.port("5555")==5555,"port");
        Set<String> homes=new HashSet<>(Arrays.asList("com.vendor.home"));
        for(String p:Arrays.asList("android","com.vendor.home","com.android.systemui","com.android.providers.media","com.vendor.bluetooth","com.google.android.gms","com.vendor.ota","com.spocky.projengmenu","com.vendor.inputmethod","com.vendor.wifi"))check(Policy.protect(p,homes,true)!=null,"protected "+p);
        check(Policy.protect("com.example.game",homes,false)!=null,"unknown home fails closed");
        check(Policy.disableReason("com.vendor.strange",homes,true,true)!=null,"unreviewed system protected");
        check(Policy.disableReason("com.google.android.videos",homes,true,true)==null,"reviewed optional package");
        check(Policy.disableReason("com.example.game",homes,true,false)==null,"user app");
        for(String p:Arrays.asList("com.app;reboot","com.app\nreboot","com.app$(id)","com.app'","../file","-a"))check(!Policy.validPackage(p),"shell injection");
        check(Policy.validComponent("com.vendor.home/.MainActivity"),"component");
        check(!Policy.validComponent("com.vendor.home/.MainActivity;reboot"),"component injection");
    }
    static void codec() throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream();AdbProtocol.send(b,AdbProtocol.WRTE,1,2,bytes("привет"));
        AdbProtocol.Packet p=AdbProtocol.read(new ByteArrayInputStream(b.toByteArray()));check(p.arg0==1&&p.arg1==2&&new String(p.data,StandardCharsets.UTF_8).equals("привет"),"packet round trip");
        byte[] bad=b.toByteArray();bad[20]^=1;rejects(()->AdbProtocol.read(new ByteArrayInputStream(bad)));
        byte[] checksum=b.toByteArray();checksum[24]^=1;rejects(()->AdbProtocol.read(new ByteArrayInputStream(checksum)));
        byte[] huge=b.toByteArray();ByteBuffer.wrap(huge).order(ByteOrder.LITTLE_ENDIAN).putInt(12,AdbProtocol.MAX_PAYLOAD+1);rejects(()->AdbProtocol.read(new ByteArrayInputStream(huge)));
        rejects(()->AdbProtocol.read(new ByteArrayInputStream(new byte[5])));
    }
    static final class FakeSocket extends Socket {
        final PipedInputStream phoneInput=new PipedInputStream(65536),serverInput=new PipedInputStream(65536);
        final PipedOutputStream serverOutput=new PipedOutputStream(phoneInput),phoneOutput=new PipedOutputStream(serverInput);
        SocketAddress destination;
        FakeSocket() throws IOException {}
        @Override public void connect(SocketAddress a,int t){destination=a;}
        @Override public void setSoTimeout(int t){}
        @Override public void setTcpNoDelay(boolean b){}
        @Override public InputStream getInputStream(){return phoneInput;}
        @Override public OutputStream getOutputStream(){return phoneOutput;}
        @Override public void close() throws IOException {phoneInput.close();phoneOutput.close();serverInput.close();serverOutput.close();}
    }
    static void authenticate(FakeSocket s,KeyPair pair) throws Exception {
        AdbProtocol.Packet hello=AdbProtocol.read(s.serverInput);check(hello.command==AdbProtocol.CNXN,"CNXN");
        byte[] token=new byte[20];new SecureRandom().nextBytes(token);
        AdbProtocol.send(s.serverOutput,AdbProtocol.AUTH,1,0,token);
        AdbProtocol.Packet auth=AdbProtocol.read(s.serverInput);check(auth.command==AdbProtocol.AUTH&&auth.arg0==2,"AUTH signature");
        Cipher cipher=Cipher.getInstance("RSA/ECB/PKCS1Padding");cipher.init(Cipher.DECRYPT_MODE,pair.getPublic());byte[] signature=cipher.doFinal(auth.data);
        check(signature.length==35&&Arrays.equals(Arrays.copyOfRange(signature,15,35),token),"RSA signature covers actual token");
        AdbProtocol.send(s.serverOutput,AdbProtocol.AUTH,1,0,token);
        AdbProtocol.Packet pub=AdbProtocol.read(s.serverInput);check(pub.arg0==3,"AUTH public key");
        String text=new String(pub.data,StandardCharsets.UTF_8);check(text.endsWith(" x96-local@android\0"),"public key terminator");
        ByteBuffer key=ByteBuffer.wrap(Base64.getDecoder().decode(text.split(" ")[0])).order(ByteOrder.LITTLE_ENDIAN);
        check(key.getInt()==64,"ADB modulus words");long n0=key.getInt()&0xffffffffL;
        byte[] mod=new byte[256];key.get(mod);byte[] rr=new byte[256];key.get(rr);check(key.getInt()==65537,"public exponent");
        BigInteger modulus=((RSAPublicKey)pair.getPublic()).getModulus();
        check(modulus.and(BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)).multiply(BigInteger.valueOf(n0)).add(BigInteger.ONE).mod(BigInteger.ONE.shiftLeft(32)).equals(BigInteger.ZERO),"n0inv");
        reverse(mod);reverse(rr);check(new BigInteger(1,mod).equals(modulus),"modulus encoding");check(new BigInteger(1,rr).equals(BigInteger.ONE.shiftLeft(4096).mod(modulus)),"Montgomery RR");
        AdbProtocol.send(s.serverOutput,AdbProtocol.CNXN,0x01000000,4096,bytes("device::\0"));
    }
    static void reverse(byte[] a){for(int i=0,j=a.length-1;i<j;i++,j--){byte b=a[i];a[i]=a[j];a[j]=b;}}
    static void transport(KeyPair pair,boolean failedExit) throws Exception {
        FakeSocket socket=new FakeSocket();ExecutorService e=Executors.newSingleThreadExecutor();
        Future<?> server=e.submit(()->{try{
            authenticate(socket,pair);AdbProtocol.Packet open=AdbProtocol.read(socket.serverInput);check(open.command==AdbProtocol.OPEN,"OPEN");
            String command=new String(open.data,StandardCharsets.UTF_8);check(command.startsWith("shell:getprop ro.product.model;"),"fixed requested command");
            java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("X96_EXIT_[0-9a-f]{32}:").matcher(command);check(matcher.find(),"random completion sentinel");String marker=matcher.group();
            AdbProtocol.send(socket.serverOutput,AdbProtocol.OKAY,7,1,new byte[0]);
            AdbProtocol.send(socket.serverOutput,AdbProtocol.WRTE,7,1,bytes("X96 Max+ Ultra\r\n\r\n"+marker+(failedExit?"1":"0")+"\r\n"));
            check(AdbProtocol.read(socket.serverInput).command==AdbProtocol.OKAY,"stream acknowledgement");
            AdbProtocol.send(socket.serverOutput,AdbProtocol.CLSE,7,1,new byte[0]);
        }catch(Exception ex){throw new RuntimeException(ex);}});
        try{
            AdbClient c=new AdbClient("192.168.1.50",5555,pair,()->socket);
            if(failedExit)rejects(()->c.shell("getprop ro.product.model"));else check(c.shell("getprop ro.product.model").equals("X96 Max+ Ultra"),"shell result without protocol sentinel");
            server.get(5,TimeUnit.SECONDS);check(((InetSocketAddress)socket.destination).getAddress().getHostAddress().equals("192.168.1.50"),"only target IP");
        }finally{e.shutdownNow();socket.close();}
    }
    static void cancellation(KeyPair pair) throws Exception {
        FakeSocket s=new FakeSocket();AdbClient c=new AdbClient("192.168.1.2",5555,pair,()->s);ExecutorService e=Executors.newSingleThreadExecutor();
        Future<?> f=e.submit(()->{try{c.shell("getprop ro.product.model");throw new AssertionError("cancel must fail");}catch(Exception expected){}});
        AdbProtocol.read(s.serverInput);c.cancel();f.get(3,TimeUnit.SECONDS);rejects(()->c.shell("getprop ro.product.model"));e.shutdownNow();
    }
    public static void main(String[] args) throws Exception {
        policy();codec();KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);KeyPair k=g.generateKeyPair();
        transport(k,false);transport(k,true);cancellation(k);System.out.println("PASS: "+checks+" checks (policy, packets, RSA auth, shell, command failures, cancellation)");
    }
}
