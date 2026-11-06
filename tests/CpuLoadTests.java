package ru.x96.local;
import static ru.x96.local.CoreTests.*;
public final class CpuLoadTests {
 public static void main(String[] args){
 String a="cpu 100 0 50 800 50 0 0 0 0 0",b="cpu 125 0 75 840 60 0 0 0 0 0";
 check(CpuLoad.parse(a+"\n"+b).contains("50.0%"),"busy excludes idle and iowait");
 check(CpuLoad.parse(a+"\n"+b).contains("10.0%"),"iowait separate");
 check(CpuLoad.parse(a+"\n"+"cpu 125 0 75 840 60 0 0 0 10 10").contains("50.0%"),"guest counters not double counted");
 for(String raw:new String[]{"",a,a+"\n"+a,a+"\n"+b.replace("125","99"),a+"\n"+b.replace("cpu ","cpu0 "),a+"\n"+b.replace("125","-1"),a+"\n"+b+"\nextra"})check(CpuLoad.parse(raw).contains("недоступен"),"invalid sample unknown");
 check(CpuLoad.parse("cpu 0 0 0 0\ncpu 0 0 0 100").contains("0.0%"),"fully idle CPU");
 System.out.println("CpuLoadTests: "+checks+" checks passed");
 }
}
