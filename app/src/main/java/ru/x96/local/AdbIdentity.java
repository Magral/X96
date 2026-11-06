// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.security.*;
import java.security.spec.*;

/** Private app storage only, backup and device transfer excluded by manifest. */
final class AdbIdentity {
    static synchronized KeyPair load(Context ctx) throws Exception {
        AtomicFile file=new AtomicFile(new File(ctx.getFilesDir(),"adb-identity.bin"));
        if(file.getBaseFile().exists() || new File(file.getBaseFile()+".bak").exists()) {
            try(DataInputStream in=new DataInputStream(file.openRead())) {
                int n=in.readInt();if(n<256||n>8192)throw new IOException("Повреждён ключ ADB");
                byte[] a=new byte[n];in.readFully(a);int m=in.readInt();if(m<128||m>4096)throw new IOException("Повреждён ключ ADB");
                byte[] b=new byte[m];in.readFully(b);KeyFactory f=KeyFactory.getInstance("RSA");
                return new KeyPair(f.generatePublic(new X509EncodedKeySpec(b)),f.generatePrivate(new PKCS8EncodedKeySpec(a)));
            }
        }
        KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048,new SecureRandom());KeyPair pair=g.generateKeyPair();
        FileOutputStream stream=null;
        try {
            stream=file.startWrite();DataOutputStream out=new DataOutputStream(stream);
            byte[] a=pair.getPrivate().getEncoded(),b=pair.getPublic().getEncoded();
            out.writeInt(a.length);out.write(a);out.writeInt(b.length);out.write(b);out.flush();file.finishWrite(stream);
        } catch(Exception e) {if(stream!=null)file.failWrite(stream);throw e;}
        return pair;
    }
}
