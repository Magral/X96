// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;
import java.util.*;

/** Fixed local catalogue: no downloaded commands or arbitrary settings. */
public enum Option {
    ANIMATIONS("Быстрые переходы", "Меню и окна открываются с анимацией вдвое короче. Это ускоряет переходы, но не загрузку видео и игр.", "global", new String[]{"window_animation_scale","transition_animation_scale","animator_duration_scale"}, "0.5", true),
    LARGE_TEXT("Текст крупнее", "Увеличивает системный шрифт на 15%. Удобнее читать с дивана; некоторые приложения используют свой размер текста.", "system", new String[]{"font_scale"}, "1.15", true),
    QUIET("Тихое меню", "Отключает системные щелчки при перемещении по меню. Звук фильмов, музыки и игр сохраняется.", "system", new String[]{"sound_effects_enabled"}, "0", false),
    NO_SAVER("Без заставки", "Отключает заставку Android. Для OLED при неподвижной картинке заставку лучше сохранить. Сон телевизора настраивается отдельно.", "secure", new String[]{"screensaver_enabled"}, "0", false),
    AUTO_TIME("Автоматическое время", "Разрешает Android сверять часы по сети. Правильная дата важна для входа в сервисы и проверки сертификатов. Сетевой обмен выполняет сама система ТВ.", "global", new String[]{"auto_time"}, "1", false),
    WIFI_SCAN("Без лишнего поиска Wi-Fi", "Отключает фоновый поиск сетей, когда Wi-Fi выключен. Рабочее Wi-Fi-соединение не отключается.", "global", new String[]{"wifi_scan_always_enabled"}, "0", false),
    BLE_SCAN("Без лишнего поиска Bluetooth", "Отключает поиск BLE-устройств при выключенном Bluetooth. Подключение пульта и включённый Bluetooth не меняются.", "global", new String[]{"ble_scan_always_enabled"}, "0", false),
    CLEAN_OVERLAYS("Без отладочных наложений", "Убирает точки касаний и координаты указателя, если они включены в режиме разработчика. Сам режим разработчика сохраняется.", "system", new String[]{"show_touches","pointer_location"}, "0", false),
    SLEEP_30("Сон Android через 30 минут", "Задаёт системный таймер сна при бездействии. Прошивка может учитывать дополнительные условия; выключение самого телевизора зависит от HDMI-CEC.", "secure", new String[]{"sleep_timeout"}, "1800000", false);
    public final String title,description,namespace,target;public final String[] keys;public final boolean allowAbsent;
    public String category(){
        switch(this){
            case ANIMATIONS:case LARGE_TEXT:case QUIET:return "Меню и удобство";
            case NO_SAVER:case SLEEP_30:return "Сон и заставка";
            case WIFI_SCAN:case BLE_SCAN:return "Фоновый поиск устройств";
            default:return "Системные настройки";
        }
    }
    Option(String t,String d,String ns,String[] k,String value,boolean absent){title=t;description=d;namespace=ns;keys=k;target=value;allowAbsent=absent;}
    public boolean valid(String value) {
        if("null".equals(value))return allowAbsent;
        if(this==SLEEP_30)return value!=null&&value.matches("-?\\d{1,10}")&&Long.parseLong(value)>=-1&&Long.parseLong(value)<=2147483647L;
        if(value==null||!value.matches("[0-9]{1,2}(\\.[0-9]{1,4})?"))return false;
        double n=Double.parseDouble(value);
        if(this==ANIMATIONS)return n>=0&&n<=10;
        if(this==LARGE_TEXT)return n>=0.5&&n<=3;
        return value.equals("0")||value.equals("1");
    }
    public String readCommand(){List<String> commands=new ArrayList<>();for(String key:keys)commands.add("settings --user 0 get "+namespace+" "+key);return String.join("; ",commands);}
    public static String readAllCommand(){List<String> c=new ArrayList<>();for(Option o:values()){c.add("printf 'X96_OPTION_"+o.name()+"\\n'");c.add(o.readCommand());}return String.join("; ",c);}
    public static Map<Option,String[]> parseAll(String text){
        Map<Option,String[]> out=new EnumMap<>(Option.class);Option current=null;StringBuilder value=new StringBuilder();
        for(String line:text.split("\\r?\\n")){
            if(line.startsWith("X96_OPTION_")){if(current!=null)out.put(current,current.parse(value.toString()));value.setLength(0);try{current=valueOf(line.substring(11));}catch(Exception e){current=null;}}
            else if(current!=null)value.append(line).append('\n');
        }
        if(current!=null)out.put(current,current.parse(value.toString()));return out;
    }
    public String writeCommand(int i,String value) {
        if(i<0||i>=keys.length||!valid(value))throw new IllegalArgumentException("Некорректное значение настройки");
        return "null".equals(value)?"settings --user 0 delete "+namespace+" "+keys[i]:"settings --user 0 put "+namespace+" "+keys[i]+" "+value;
    }
    public String[] parse(String text){String[] a=text.trim().split("\\r?\\n",-1);if(a.length!=keys.length)return null;for(int i=0;i<a.length;i++){a[i]=a[i].trim();if(!valid(a[i]))return null;}return a;}
    public boolean matches(String[] values){if(values==null)return false;for(String v:values)if("null".equals(v)||Double.parseDouble(v)!=Double.parseDouble(target))return false;return true;}
    public static final class Status {
        public final boolean supported,active,saved;
        public Status(boolean available,boolean enabled,boolean recorded){supported=available;active=enabled;saved=recorded;}
    }
}
