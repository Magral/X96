// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;

import java.io.IOException;
import java.util.*;

/** One read-only ADB session; every result must have its own successful exit code. */
final class TvSnapshot {
    enum Field {
        ID("getprop ro.serialno; getprop ro.boot.serialno; settings get secure android_id; getprop ro.product.device"),
        MODEL("getprop ro.product.model"), ANDROID("getprop ro.build.version.release"), UID("id -u"),
        PACKAGES("pm list packages --user 0"), DISABLED("pm list packages -d --user 0"), SYSTEM("pm list packages -s --user 0"),
        HOMES("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME"),
        HOME("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME");
        final String command;Field(String c){command=c;}
    }
    static String nonce(){return UUID.randomUUID().toString().replace("-","");}
    private static void validate(String nonce){if(nonce==null||!nonce.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid snapshot token");}
    static String begin(String nonce,Field f){return "X96_READ_"+nonce+"_"+f.name();}
    static String end(String nonce,Field f){return "X96_END_"+nonce+"_"+f.name();}
    static String command(String nonce){
        validate(nonce);StringBuilder b=new StringBuilder();
        for(Field f:Field.values()){
            if(b.length()>0)b.append("; ");
            b.append("printf '").append(begin(nonce,f)).append("\\n'; ( ").append(f.command).append(" ); x96_read_status=$?; printf '\\n")
                .append(end(nonce,f)).append(":%s\\n' \"$x96_read_status\"");
        }
        return b.toString();
    }
    static Map<Field,String> read(TvShell shell) throws Exception {String n=nonce();return parse(shell.shell(command(n)),n);}
    static Map<Field,String> parse(String raw,String nonce) throws IOException {
        validate(nonce);if(raw==null||raw.length()>2*1024*1024)throw invalid();
        String text=raw.replace("\r\n","\n").trim();int at=0;Map<Field,String> values=new EnumMap<>(Field.class);
        for(Field f:Field.values()){
            String head=begin(nonce,f)+"\n",tail="\n"+end(nonce,f)+":";
            if(!text.startsWith(head,at))throw invalid();int from=at+head.length(),stop=text.indexOf(tail,from);
            if(stop<0)throw invalid();int code=stop+tail.length(),next=text.indexOf('\n',code);if(next<0)next=text.length();
            if(!text.substring(code,next).equals("0"))throw new IOException("Приставка не смогла прочитать сведения. Состояние не подтверждено; проверь соединение и обнови данные.");
            values.put(f,text.substring(from,stop).trim());at=next<text.length()?next+1:next;
        }
        if(at!=text.length())throw invalid();return values;
    }
    private static IOException invalid(){return new IOException("Получен неполный ответ приставки. Обнови соединение перед изменениями.");}
}
