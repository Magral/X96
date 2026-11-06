// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;
import java.util.*;

/** Fixed local catalogue: no downloaded commands or arbitrary settings. */
public enum Option {
    ANIMATIONS("Быстрые переходы", "Меню и окна открываются с анимацией вдвое короче. Это ускоряет переходы, но не загрузку видео и игр.", "global", new String[]{"window_animation_scale","transition_animation_scale","animator_duration_scale"}, "0.5", true),
    LARGE_TEXT("Текст крупнее", "Увеличивает системный шрифт на 15%. Удобнее читать с дивана; некоторые приложения используют свой размер текста.", "system", new String[]{"font_scale"}, "1.15", true),
    QUIET("Тихое меню", "Отключает системные щелчки при перемещении по меню. Звук фильмов, музыки и игр сохраняется.", "system", new String[]{"sound_effects_enabled"}, "0", false),
    NO_SAVER("Без заставки", "Отключает автоматическую заставку Android. Сон и выключение телевизора настраиваются отдельно.", "secure", new String[]{"screensaver_enabled"}, "0", false);
    public final String title,description,namespace,target;public final String[] keys;public final boolean allowAbsent;
    Option(String t,String d,String ns,String[] k,String value,boolean absent){title=t;description=d;namespace=ns;keys=k;target=value;allowAbsent=absent;}
    public boolean valid(String value) {
        if("null".equals(value))return allowAbsent;
        if(value==null||!value.matches("[0-9]{1,2}(\\.[0-9]{1,4})?"))return false;
        double n=Double.parseDouble(value);
        if(this==ANIMATIONS)return n>=0&&n<=10;
        if(this==LARGE_TEXT)return n>=0.5&&n<=3;
        return value.equals("0")||value.equals("1");
    }
    public String readCommand(){List<String> commands=new ArrayList<>();for(String key:keys)commands.add("settings --user 0 get "+namespace+" "+key);return String.join("; ",commands);}
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
