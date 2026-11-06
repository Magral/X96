// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;

import java.io.*;
import java.math.BigInteger;
import java.nio.*;
import java.security.interfaces.RSAPublicKey;

/** ADB packet codec. No network, Android API or external dependencies. */
public final class AdbProtocol {
    public static final int CNXN=0x4e584e43, AUTH=0x48545541, OPEN=0x4e45504f, OKAY=0x59414b4f, WRTE=0x45545257, CLSE=0x45534c43;
    public static final int MAX_PAYLOAD=1024*1024;
    public static final class Packet {
        public final int command,arg0,arg1; public final byte[] data;
        Packet(int c,int a,int b,byte[] d) { command=c;arg0=a;arg1=b;data=d; }
    }
    public static void send(OutputStream out,int cmd,int a,int b,byte[] data) throws IOException {
        if(data.length>MAX_PAYLOAD)throw new IOException("Слишком большой пакет ADB");
        int sum=0;for(byte v:data)sum+=v&255;
        out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(cmd).putInt(a).putInt(b).putInt(data.length).putInt(sum).putInt(cmd^0xffffffff).array());out.write(data);out.flush();
    }
    public static Packet read(InputStream in) throws IOException {
        byte[] header=new byte[24];new DataInputStream(in).readFully(header);
        ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int cmd=b.getInt(),a=b.getInt(),c=b.getInt(),len=b.getInt(),sum=b.getInt(),magic=b.getInt();
        if(magic!=(cmd^0xffffffff)||len<0||len>MAX_PAYLOAD)throw new IOException("Некорректный пакет ADB");
        byte[] payload=new byte[len];new DataInputStream(in).readFully(payload);
        int actual=0;for(byte v:payload)actual+=v&255;
        if(actual!=sum)throw new IOException("Ошибка контрольной суммы ADB");
        return new Packet(cmd,a,c,payload);
    }
    private static byte[] little(BigInteger n,int len) {
        byte[] a=n.toByteArray(),out=new byte[len]; for(int i=0;i<len && i<a.length;i++)out[i]=a[a.length-1-i];return out;
    }
    public static byte[] publicKey(RSAPublicKey key) {
        BigInteger n=key.getModulus(),base=BigInteger.ONE.shiftLeft(32);
        if(n.bitLength()!=2048)throw new IllegalArgumentException("ADB requires RSA-2048");
        BigInteger inv=base.subtract(n.modInverse(base)).mod(base);
        BigInteger rr=BigInteger.ONE.shiftLeft(4096).mod(n);
        return ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN).putInt(64).putInt(inv.intValue()).put(little(n,256)).put(little(rr,256)).putInt(key.getPublicExponent().intValue()).array();
    }
    private AdbProtocol() {}
}
