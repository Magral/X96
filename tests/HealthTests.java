// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;
import static ru.x96.local.CoreTests.*;

public final class HealthTests {
    static final String BOOT="12345678-1234-1234-1234-123456789abc";
    static String stat(String pid,String start){StringBuilder b=new StringBuilder(pid+" (name with ) spaces) S");for(int i=1;i<19;i++)b.append(" 0");return b.append(' ').append(start).append(" 0").toString();}
    public static void main(String[] args) throws Exception {
        String raw="MemTotal:       4000000 kB\nMemAvailable:    1500000 kB\n/dev/block/dm-1 56000000 15000000 40000000 28% /data\n";
        DeviceHealth h=DeviceHealth.parse(raw);
        check(h.ramTotal==4000000&&h.ramAvailable==1500000,"meminfo uses available not free");
        check(h.dataTotal==56000000&&h.dataAvailable==40000000,"df block counts and reserved blocks");
        check(h.text().contains("1.43 ГиБ")&&!h.text().contains("/dev/"),"human units without raw paths");
        check(DeviceHealth.parse("").ramAvailable==-1,"missing RAM is unknown");
        check(DeviceHealth.parse(null).dataAvailable==-1,"null is unknown");
        check(DeviceHealth.parse(raw.replace("1500000 kB","5000000 kB")).ramAvailable==-1,"reject available above total");
        check(DeviceHealth.parse(raw.replace("1500000 kB","-1 kB")).ramAvailable==-1,"reject negative RAM");
        check(DeviceHealth.parse(raw.replace("1500000 kB","999999999999999999999999 kB")).ramAvailable==-1,"reject overflow");
        check(DeviceHealth.parse(raw+"MemAvailable: 5 kB\n").ramAvailable==-1,"reject duplicate memory field");
        check(DeviceHealth.parse(raw.replace("/data","/storage/emulated")).dataAvailable==-1,"do not mistake other volume for data");
        check(DeviceHealth.parse(raw.replace("40000000 28%","55000000 28%")).dataAvailable==-1,"reject inconsistent disk counts");
        check(DeviceHealth.parse(raw.replace("28%","101%")).dataAvailable==-1,"reject impossible percent");
        check(DeviceHealth.parse(raw.replace("40000000 28%","0 100%")).dataAvailable==0,"full disk has actual zero");
        check(DeviceHealth.parse(raw.replace("40000000 28%","0 100%")).text().contains("меньше 1 ГиБ"),"low storage actionable advice");
        check(!h.text().contains("меньше 1 ГиБ"),"no unnecessary cache recommendation");
        check(DeviceHealth.parse(new String(new char[8193])).ramTotal==-1,"bounded input");
        check(h.difference(DeviceHealth.parse(raw.replace("1500000 kB","1400000 kB"))).contains("+98 МиБ"),"positive delta");
        check(h.difference(DeviceHealth.parse(raw.replace("1500000 kB","1600000 kB"))).contains("−98 МиБ"),"negative delta");
        check(h.difference(DeviceHealth.parse("")).contains("нет сопоставимых"),"missing delta unknown");
        check(h.difference(DeviceHealth.parse(raw.replace("4000000 kB","4100000 kB"))).contains("нет сопоставимых"),"different RAM total not comparable");
        String identity=ProjectivyTools.processIdentity("234",BOOT+"\n"+stat("234","777"));
        check(identity.equals(BOOT+"|234|777"),"parse stat comm with spaces and parentheses");
        check(ProjectivyTools.processIdentity("234",BOOT+"\n"+stat("235","777")).isEmpty(),"reject changed PID");
        check(ProjectivyTools.processIdentity("234","\n"+stat("234","777")).isEmpty(),"missing boot identity unknown");
        check(ProjectivyTools.processIdentity("234",BOOT+"\n234 (x) S 0").isEmpty(),"short stat unknown");
        check(ProjectivyTools.processIdentity("234;id",BOOT+"\n"+stat("234","777")).isEmpty(),"validate PID");
        ProjectivyTools.Report before=new ProjectivyTools.Report(),after=new ProjectivyTools.Report();before.identity=identity;after.identity=identity;
        check(ProjectivyTools.compare(before,after).contains("остался тем же"),"same process");
        after.identity=BOOT+"|234|778";
        check(ProjectivyTools.compare(before,after).contains("сменился"),"reused PID with new start is new process");
        after.identity=BOOT+"|235|778";
        check(ProjectivyTools.compare(before,after).contains("сменился"),"new PID is new process");
        after.identity="abcdefab-1234-1234-1234-123456789abc|234|777";
        check(ProjectivyTools.compare(before,after).contains("перезагрузилась"),"reboot is separate conclusion");
        after.identity="";check(ProjectivyTools.compare(before,after).contains("не предоставила"),"missing identity not claimed equal");
        after.version="4.73";check(ProjectivyTools.compare(before,after).contains("изменились версия"),"settings/version change flagged");
        check(ProjectivyTools.compare(null,after).contains("Сначала"),"missing baseline");
        after.identity=identity;after.comparison=ProjectivyTools.compare(before,after);
        check(!after.text().contains(BOOT)&&!after.text().contains("|234|"),"private boot identity not exported");
        check(after.text().contains("Проверка первого нажатия"),"result included in report");
        System.out.println("HealthTests: "+checks+" checks passed");
    }
}
