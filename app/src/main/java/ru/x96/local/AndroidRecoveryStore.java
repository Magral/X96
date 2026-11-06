// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
final class AndroidRecoveryStore implements RecoveryStore {
    private final SharedPreferences prefs;
    AndroidRecoveryStore(Context c){prefs=c.getSharedPreferences("recovery",Context.MODE_PRIVATE);}
    public Set<String> disabled(String d){return new HashSet<>(prefs.getStringSet(d+".disabled",Collections.emptySet()));}
    public boolean disabled(String d,Set<String> packages){return prefs.edit().putStringSet(d+".disabled",new HashSet<>(packages)).commit();}
    public String home(String d){return prefs.getString(d+".home",null);}
    public boolean home(String d,String c){return prefs.edit().putString(d+".home",c).commit();}
    public boolean forgetHome(String d){return prefs.edit().remove(d+".home").commit();}
    public String option(String d,String id){return prefs.getString(d+".option."+id,null);}
    public boolean option(String d,String id,String s){return prefs.edit().putString(d+".option."+id,s).commit();}
    public boolean forgetOption(String d,String id){return prefs.edit().remove(d+".option."+id).commit();}
}
