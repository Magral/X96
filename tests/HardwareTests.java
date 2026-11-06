package ru.x96.local;
import static ru.x96.local.CoreTests.*;
public final class HardwareTests {
 public static void main(String[] args){
 check(HardwareReport.mhz("1800000").equals("1800"),"kHz to MHz");
 check(HardwareReport.mhz("999999999999").equals("?"),"overflow unknown");
 check(HardwareReport.mhz("-1").equals("?"),"negative unknown");
 check(HardwareReport.parse("").contains("недоступны"),"missing not zero");
 check(HardwareReport.parse("T|0|cpu-thermal|65000").contains("65.0 °C"),"thermal millidegrees");
 check(HardwareReport.parse("T|0|cpu|999999").contains("недоступны"),"invalid thermal rejected");
 check(HardwareReport.parse("C|0|schedutil|1000000|100000|1800000|2000000").contains("1000 МГц"),"frequency report");
 check(HardwareReport.parse("C|0|||||").contains("нет данных"),"partial sysfs unknown");
 check(HardwareReport.parse("T|0|cpu|65000\nT|0|cpu|75000").indexOf("75.0")<0,"duplicate zone ignored");
 check(HardwareReport.parse("T|9|cpu|65000").contains("недоступны"),"zone bounds");
 System.out.println("HardwareTests: "+checks+" checks passed");
 }
}
