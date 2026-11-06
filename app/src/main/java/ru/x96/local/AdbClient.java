// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import javax.crypto.Cipher;
import java.util.UUID;

/** Classic authenticated TCP ADB only. One allowlisted LAN endpoint; no HTTP, DNS or cloud. */
public final class AdbClient implements TvShell {
    private final byte[] address; private final int port; private final KeyPair key;
    interface SocketFactory {Socket create() throws IOException;}
    private final SocketFactory sockets;
    private volatile boolean cancelled;
    private volatile Socket live;
    AdbClient(String ip,int p,KeyPair pair,SocketFactory factory) {
        address=Policy.privateAddress(ip);port=Policy.port(Integer.toString(p));key=pair;sockets=factory;
    }
    public void cancel() { cancelled=true;Socket s=live;if(s!=null)try{s.close();}catch(IOException ignored){} }
    private void checkCancelled() throws IOException {if(cancelled)throw new IOException("Подключение закрыто");}
    /** Only fixed commands assembled in TvRepository reach this transport; no shell UI. */
    public synchronized String shell(String command) throws Exception {
        checkCancelled();
        String marker="X96_EXIT_"+UUID.randomUUID().toString().replace("-","")+":";
        String wrapped=command+"; printf '\\n"+marker+"%s\\n' \"$?\"";
        Socket s=sockets.create();live=s;
        try {
            checkCancelled();s.connect(new InetSocketAddress(InetAddress.getByAddress(address),port),5000);s.setSoTimeout(65000);s.setTcpNoDelay(true);
            InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();
            AdbProtocol.send(out,AdbProtocol.CNXN,0x01000000,4096,"host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed=false,sentPublic=false,connected=false;
            for(int i=0;i<8;i++) {
                AdbProtocol.Packet p=AdbProtocol.read(in);
                if(p.command==AdbProtocol.CNXN){connected=true;break;}
                if(p.command!=AdbProtocol.AUTH||p.arg0!=1||p.data.length!=20)throw new IOException("Неожиданный ответ ADB");
                if(!signed) {
                    byte[] prefix={0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};
                    byte[] digest=new byte[35];System.arraycopy(prefix,0,digest,0,15);System.arraycopy(p.data,0,digest,15,20);
                    Cipher cipher=Cipher.getInstance("RSA/ECB/PKCS1Padding");cipher.init(Cipher.ENCRYPT_MODE,key.getPrivate());
                    AdbProtocol.send(out,AdbProtocol.AUTH,2,0,cipher.doFinal(digest));signed=true;
                } else if(!sentPublic) {
                    String pub=java.util.Base64.getEncoder().encodeToString(AdbProtocol.publicKey((RSAPublicKey)key.getPublic()))+" x96-local@android\0";
                    AdbProtocol.send(out,AdbProtocol.AUTH,3,0,pub.getBytes(StandardCharsets.UTF_8));sentPublic=true;
                } else throw new IOException("Приставка отклонила ключ. Подтверди отладку на ТВ.");
            }
            if(!connected)throw new IOException("Нет подтверждения ADB");
            checkCancelled();s.setSoTimeout(20000);
            AdbProtocol.send(out,AdbProtocol.OPEN,1,0,("shell:"+wrapped+"\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result=new ByteArrayOutputStream();int remote=0;boolean opened=false,closed=false;
            long deadline=System.nanoTime()+30000000000L;
            while(!closed) {
                checkCancelled();if(System.nanoTime()>deadline)throw new IOException("Команда выполняется слишком долго");
                AdbProtocol.Packet p=AdbProtocol.read(in);
                if(p.arg1!=1)throw new IOException("Неверный поток ADB");
                if(p.command==AdbProtocol.OKAY){if(opened&&remote!=p.arg0)throw new IOException("Поток ADB изменился");remote=p.arg0;opened=true;}
                else if(p.command==AdbProtocol.WRTE) {
                    if(!opened||p.arg0!=remote)throw new IOException("Неожиданные данные ADB");
                    if(result.size()+p.data.length>2*1024*1024)throw new IOException("Ответ приставки слишком большой");
                    result.write(p.data);AdbProtocol.send(out,AdbProtocol.OKAY,1,remote,new byte[0]);
                } else if(p.command==AdbProtocol.CLSE) { AdbProtocol.send(out,AdbProtocol.CLSE,1,p.arg0,new byte[0]);closed=true; }
                else throw new IOException("Неожиданный пакет ADB");
            }
            String text=new String(result.toByteArray(),StandardCharsets.UTF_8).replace("\r","");
            int pos=text.lastIndexOf("\n"+marker);if(pos<0)throw new IOException("Команда не завершилась. Обнови состояние перед повтором.");
            String exit=text.substring(pos+marker.length()+1).trim();
            if(!exit.equals("0"))throw new IOException("Приставка отклонила команду (код "+exit+")");
            return text.substring(0,pos).trim();
        } finally {try{s.close();}finally{live=null;}}
    }
}
