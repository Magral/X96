// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;

import android.app.*;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Native local-only UI. Options preserve their original values before changing the TV. */
public final class MainActivity extends Activity {
    private final int BG=Color.rgb(12,20,34),CARD=Color.rgb(24,38,57),TEXT=Color.rgb(235,242,252),MUTED=Color.rgb(156,177,199),ACCENT=Color.rgb(85,223,192);
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Object sessionLock=new Object();private int generation=0;
    private AdbClient adb;private TvRepository repo;private TvRepository.State state;
    private TextView status,device;private EditText ip,port;private LinearLayout root,connectFields;private FrameLayout body;private ProgressBar progress;
    private boolean busy=false,recoveryOnly=false,destroyed=false;private int tab=0;private String currentIp="",query="";
    private final List<Button> navigation=new ArrayList<>();private final List<View> gated=new ArrayList<>();
    private final int[] scrollPositions=new int[3];private ScrollView pageScroll;private ListView packageList;private int listPosition=0,listTop=0,renderedTab=0;
    private static final Map<String,String> NAMES=new HashMap<>();
    static {
        NAMES.put("com.google.android.videos","Google TV / Фильмы");NAMES.put("com.google.android.music","Google Play Музыка");
        NAMES.put("com.google.android.play.games","Google Play Игры");NAMES.put("com.google.android.apps.tachyon","Google Duo / Meet");
        NAMES.put("com.google.android.feedback","Отчёты об ошибках Google");NAMES.put("com.netflix.ninja","Netflix");
        NAMES.put("com.amazon.amazonvideo.livingroom","Prime Video");NAMES.put("com.google.android.youtube.tv","YouTube");
        NAMES.put("org.videolan.vlc","VLC");NAMES.put("com.plexapp.android","Plex");NAMES.put("org.xbmc.kodi","Kodi");
    }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(14),dp(8),dp(14),dp(4));root.setBackgroundColor(BG);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(dp(14),Math.max(dp(8),insets.getSystemWindowInsetTop()),dp(14),Math.max(dp(4),insets.getSystemWindowInsetBottom()));return insets;});
        LinearLayout heading=row();LinearLayout title=new LinearLayout(this);title.setOrientation(1);title.addView(text("X96 Локально",24,TEXT));title.addView(text("Удобные настройки приставки · 0.2.0",12,MUTED));heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(button("?",()->about(),false),new LinearLayout.LayoutParams(dp(44),dp(48)));root.addView(heading);
        LinearLayout connection=card(root);device=text("Подключение к приставке",16,TEXT);connection.addView(device);
        connectFields=new LinearLayout(this);connectFields.setOrientation(1);LinearLayout address=row();ip=edit("IP приставки");port=edit("Порт");
        ip.setInputType(17);port.setInputType(2);ip.setText(getPreferences(0).getString("ip",""));port.setText(getPreferences(0).getString("port","5555"));
        address.addView(ip,new LinearLayout.LayoutParams(0,dp(44),3));address.addView(port,new LinearLayout.LayoutParams(0,dp(44),1));connectFields.addView(address);
        connectFields.addView(button("Подключить по Wi-Fi",()->connect(),true));connection.addView(connectFields);
        LinearLayout controls=row();controls.addView(button("Обновить",()->run("Обновляю состояние…",()->state=repo.refresh()),true),weighted());
        controls.addView(button("Отключить",()->disconnect(),false),weighted());connection.addView(controls);
        status=text("Укажи IP из настроек сети приставки. Порт обычно 5555.",12,MUTED);connection.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        LinearLayout tabs=row();String[] names={"Улучшения","Приложения","Пульт"};
        for(int i=0;i<names.length;i++){final int target=i;Button btn=button(names[i],()->{capturePosition();tab=target;renderBody();},false);navigation.add(btn);tabs.addView(btn,weighted());}root.addView(tabs);
        body=new FrameLayout(this);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));render();
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams weighted(){return new LinearLayout.LayoutParams(0,dp(48),1);}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);return l;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(3),dp(3),dp(3),dp(3));return t;}
    private EditText edit(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(14);return e;}
    private GradientDrawable shape(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));return d;}
    private LinearLayout card(LinearLayout parent){LinearLayout c=new LinearLayout(this);c.setOrientation(1);c.setPadding(dp(12),dp(9),dp(12),dp(9));c.setBackground(shape(CARD));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);parent.addView(c,p);return c;}
    private Button button(String label,Runnable task,boolean gate){Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setAllCaps(false);b.setTextColor(ACCENT);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(5),0,dp(5),0);b.setOnClickListener(v->task.run());if(gate)gated.add(b);return b;}
    private interface Job {void execute() throws Exception;}
    private void connect(){
        if(busy)return;String host=ip.getText().toString().trim(),portText=port.getText().toString().trim();int p;
        try{Policy.privateAddress(host);p=Policy.port(portText);}catch(Exception e){error(e);return;}
        disconnect();currentIp=host;final int token; synchronized(sessionLock){token=generation;}
        run("Подключаюсь. Если ТВ запросит разрешение отладки — подтверди его…",()->{
            AdbClient candidate=new AdbClient(host,p,AdbIdentity.load(getApplicationContext()),WifiSockets.select(getApplicationContext()));
            synchronized(sessionLock){if(token!=generation){candidate.cancel();throw new java.io.IOException("Подключение отменено");}adb=candidate;}
            try{TvRepository r=new TvRepository(candidate,new AndroidRecoveryStore(getApplicationContext()));TvRepository.State s=r.refresh();synchronized(sessionLock){if(token!=generation)throw new java.io.IOException("Подключение отменено");repo=r;state=s;}getPreferences(0).edit().putString("ip",host).putString("port",portText).apply();}
            catch(Exception e){candidate.cancel();adb=null;repo=null;state=null;throw e;}
        },true);
    }
    private void disconnect(){synchronized(sessionLock){generation++;AdbClient c=adb;if(c!=null)c.cancel();repo=null;adb=null;state=null;currentIp="";}if(status!=null){status.setText("Отключено. Для полного закрытия доступа выключи сетевую отладку на ТВ.");render();}}
    private void run(String message,Job job){run(message,job,false);}
    private void run(String message,Job job,boolean connecting){
        if(busy||destroyed)return;if(repo==null&&!connecting){status.setText("Сначала подключи приставку.");return;}
        final int token; synchronized(sessionLock){token=generation;}final TvRepository session=repo;
        busy=true;status.setText(message);render();
        worker.execute(()->{Exception failure=null;TvRepository.State recovered=null;try{job.execute();}catch(Exception e){failure=e;if(session!=null)try{recovered=session.refresh();}catch(Exception ignored){}}
            final Exception result=failure;final TvRepository.State recovery=recovered;runOnUiThread(()->{if(destroyed)return;busy=false;
                if(token!=generation){state=null;repo=null;status.setText("Соединение закрыто.");}
                else if(result!=null){if(recovery!=null){state=recovery;}else{if(adb!=null)adb.cancel();adb=null;repo=null;state=null;}error(result);}
                else status.setText(state==null?"Отключено.":"Готово · соединение по Wi-Fi");render();});});
    }
    private void confirm(String title,String message,Job job){
        if(busy)return;if(repo==null){status.setText("Сначала подключи приставку.");return;}
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Отмена",null).setPositiveButton("Продолжить",(d,w)->run("Применяю и проверяю…",job)).show();
    }
    private void error(Exception e){String message=e.getMessage();if(message==null||message.trim().isEmpty())message=e.getClass().getSimpleName();status.setText("Не удалось завершить: "+message+"\nПодключись снова, чтобы проверить результат и при необходимости вернуть изменения.");}
    private void capturePosition(){if(pageScroll!=null)scrollPositions[renderedTab]=pageScroll.getScrollY();if(packageList!=null){listPosition=packageList.getFirstVisiblePosition();View v=packageList.getChildAt(0);listTop=v==null?0:v.getTop();}}
    private void render(){
        if(body==null)return;device.setText(state==null?"Подключение к приставке":state.model+" · Android "+state.android);
        connectFields.setVisibility(state==null?View.VISIBLE:View.GONE);ip.setEnabled(!busy);port.setEnabled(!busy);progress.setVisibility(busy?View.VISIBLE:View.INVISIBLE);
        renderBody();for(View v:gated)v.setEnabled(!busy);for(Button b:navigation)b.setEnabled(!busy);
    }
    private void renderBody(){
        capturePosition();renderedTab=tab;pageScroll=null;packageList=null;body.removeAllViews();
        // Only top-level connection controls remain in this list between page rebuilds.
        while(gated.size()>2)gated.remove(gated.size()-1);
        for(int i=0;i<navigation.size();i++)navigation.get(i).setTextColor(i==tab?TEXT:ACCENT);
        if(tab==1){applications();return;}
        pageScroll=new ScrollView(this);pageScroll.setFillViewport(false);LinearLayout content=new LinearLayout(this);content.setOrientation(1);content.setPadding(0,0,0,dp(14));pageScroll.addView(content);body.addView(pageScroll);
        if(tab==0)options(content);else remote(content);
        ScrollView current=pageScroll;int target=scrollPositions[tab];current.post(()->current.scrollTo(0,target));
    }
    private void options(LinearLayout content){
        TextView lead=text("Настрой приставку под себя",20,TEXT);lead.setTypeface(null,Typeface.BOLD);content.addView(lead);content.addView(text("Каждый переключатель сохраняет прежние значения. Выключение возвращает их обратно.",13,MUTED));
        for(Option o:Option.values()){
            LinearLayout c=card(content);LinearLayout heading=row();TextView label=text(o.title,17,TEXT);heading.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            Option.Status s=state==null?null:state.options.get(o);Switch toggle=new Switch(this);toggle.setContentDescription(o.title);toggle.setChecked(s!=null&&(s.saved||s.active));
            toggle.setEnabled(!busy&&s!=null&&(s.saved||s.supported&&!s.active));heading.addView(toggle);c.addView(heading);c.addView(text(o.description,13,MUTED));
            String note=s==null?"Состояние появится после подключения":!s.supported&&!s.saved?"Недоступно на этой прошивке":s.saved?(s.active?"Включено · прежнее значение сохранено":"Есть изменение для возврата"):s.active?"Уже включено на приставке":"Можно включить";
            c.addView(text(note,12,s!=null&&s.saved?ACCENT:MUTED));
            toggle.setOnCheckedChangeListener((v,on)->run(on?"Включаю: "+o.title:"Возвращаю: "+o.title,()->state=repo.option(o,on)));
        }
        LinearLayout home=card(content);home.addView(text("Домашний экран Projectivy",17,TEXT));home.addView(text("Сделай установленный Projectivy основным экраном. Прежний выбор сохраняется для возврата.",13,MUTED));
        home.addView(button("Выбрать Projectivy",()->confirm("Выбрать Projectivy?","Сохраним прежний домашний экран и проверим новый выбор.",()->state=repo.projectivy()),true));
        if(state!=null&&state.homeSaved)home.addView(button("Вернуть прежний экран",()->confirm("Вернуть прежний экран?","Восстановим домашний экран, выбранный до Projectivy.",()->state=repo.restoreHome()),true));
        home.addView(button("Проверить Home на телевизоре",()->run("Открываю домашний экран…",()->repo.homeKey()),true));
        LinearLayout storage=card(content);storage.addView(text("Освободить место",17,TEXT));storage.addView(text("Просит Android освободить место за счёт кеша, если свободно меньше 1 ГБ. Аккаунты, настройки и личные данные приложений сохраняются. Фактический объём очистки определяет Android.",13,MUTED));
        storage.addView(button("Очистить кеш при нехватке места",()->confirm("Освободить место?","Android может удалить временные файлы. Приложения загрузят их заново при необходимости. Если места достаточно, очистка может ничего не изменить.",()->state=repo.trimCache()),true));
        boolean hasChanges=false;if(state!=null)for(Option.Status s:state.options.values())if(s.saved)hasChanges=true;
        if(hasChanges){LinearLayout restore=card(content);restore.addView(text("Возврат настроек",17,TEXT));restore.addView(text("Отменяет изменения переключателей выше. Приложения и домашний экран возвращаются отдельно.",13,MUTED));restore.addView(button("Вернуть прежние настройки",()->confirm("Отменить настройки?","Вернём значения, сохранённые перед включением переключателей.",()->state=repo.restoreOptions()),true));}
        content.addView(text("Если настройка не поддерживается прошивкой, она недоступна. Изменения не применяются автоматически.",12,MUTED));
    }
    private void applications(){
        LinearLayout content=new LinearLayout(this);content.setOrientation(1);body.addView(content);
        content.addView(text(recoveryOnly?"Мои изменения":"Управление приложениями",20,TEXT));
        content.addView(text("Защищённые системные службы скрыты. Здесь только доступные действия и восстановление твоих изменений.",13,MUTED));
        Button filter=button(recoveryOnly?"Показать доступные приложения":"Показать мои изменения",()->{capturePosition();recoveryOnly=!recoveryOnly;listPosition=0;listTop=0;renderBody();},false);content.addView(filter);
        EditText search=edit("Поиск приложения");search.setText(query);content.addView(search,new LinearLayout.LayoutParams(-1,dp(46)));
        List<String> visible=new ArrayList<>();PackageAdapter adapter=new PackageAdapter(visible);
        packageList=new ListView(this);packageList.setDividerHeight(dp(6));packageList.setDivider(new android.graphics.drawable.ColorDrawable(BG));packageList.setAdapter(adapter);content.addView(packageList,new LinearLayout.LayoutParams(-1,0,1));
        Runnable fill=()->{visible.clear();if(state!=null){Set<String> all=new TreeSet<>(state.packages);all.addAll(state.owned);for(String p:all)if(TvRepository.visible(state,p,recoveryOnly)&&(p+" "+label(p)).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))visible.add(p);}adapter.notifyDataSetChanged();};fill.run();
        packageList.setSelectionFromTop(listPosition,listTop);
        TextView empty=text(state==null?"Подключи приставку, чтобы увидеть приложения.":"Подходящих приложений нет.",14,MUTED);content.addView(empty);packageList.setEmptyView(empty);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString();fill.run();}public void afterTextChanged(Editable e){}});
    }
    private String label(String pkg){String name=NAMES.get(pkg);return name==null?pkg:name;}
    private final class PackageAdapter extends BaseAdapter {
        final List<String> items;PackageAdapter(List<String> p){items=p;}
        public int getCount(){return items.size();}public Object getItem(int n){return items.get(n);}public long getItemId(int n){return n;}
        public View getView(int n,View recycled,android.view.ViewGroup parent){
            final String pkg=items.get(n);boolean owned=state.owned.contains(pkg),off=state.disabled.contains(pkg);
            LinearLayout row=new LinearLayout(MainActivity.this);row.setOrientation(1);row.setPadding(dp(12),dp(8),dp(12),dp(6));row.setBackground(shape(CARD));
            TextView name=text(label(pkg),16,TEXT);name.setTypeface(null,Typeface.BOLD);row.addView(name);if(!label(pkg).equals(pkg))row.addView(text(pkg,11,MUTED));
            row.addView(text(off?"Отключено":owned?"Можно завершить восстановление":"Включено",12,MUTED));
            Button action=button(owned?"Вернуть приложение":"Отключить",()->confirm(owned?"Вернуть приложение?":"Отключить приложение?",label(pkg)+(owned?"\nВключим его и проверим результат.":"\nОно перестанет запускаться. Данные сохранятся; вернуть можно в разделе «Мои изменения»."),()->state=owned?repo.restore(pkg):repo.disable(pkg)),false);
            action.setEnabled(!busy);row.addView(action,new LinearLayout.LayoutParams(-1,dp(44)));return row;
        }
    }
    private void remote(LinearLayout content){
        content.addView(text("Пульт на телефоне",20,TEXT));content.addView(text("Управление меню приставки по Wi-Fi. Громкость меняется на приставке; управление громкостью телевизора зависит от подключения.",13,MUTED));
        LinearLayout c=card(content);addKeys(c,new String[]{"↑"},new int[]{19});addKeys(c,new String[]{"←","OK","→"},new int[]{21,23,22});addKeys(c,new String[]{"↓"},new int[]{20});
        addKeys(c,new String[]{"Назад","Домой","Пауза / ▶"},new int[]{4,3,85});addKeys(c,new String[]{"Тише","Без звука","Громче"},new int[]{25,164,24});
        LinearLayout settings=card(content);settings.addView(text("Открыть на телевизоре",17,TEXT));
        String[] labels={"Настройки приставки","Wi-Fi и сеть","Память и накопители"};for(int i=0;i<labels.length;i++){final int kind=i;settings.addView(button(labels[i],()->run("Открываю настройки на телевизоре…",()->repo.openSettings(kind)),true));}
    }
    private void addKeys(LinearLayout parent,String[] labels,int[] codes){LinearLayout r=row();for(int i=0;i<labels.length;i++){final int code=codes[i];r.addView(button(labels[i],()->run("Отправляю кнопку…",()->repo.remote(code)),true),weighted());}parent.addView(r);}
    private void about(){new AlertDialog.Builder(this).setTitle("X96 Локально 0.2.0").setMessage("Настройки, приложения и пульт для домашней Wi-Fi-сети.\n\nНет облака, аккаунтов, аналитики, внешних загрузок и автоматических обновлений. Прежние значения переключателей сохраняются на телефоне. Не удаляй приложение и его данные до восстановления.\n\nОтладка ADB даёт широкие права и не шифруется. После работы выключай сетевую отладку на приставке. Root-прошивка остаётся отдельным источником риска.\n\nВ этой версии подключение вручную по IPv4 и порту; шестизначное сопряжение Android 11+ не поддерживается.\n\nИсходный код: GPL-3.0-only. Правила защиты адаптированы из Tellyscope с атрибуцией CleanYourTV (MIT). Независимый проект.").setPositiveButton("Понятно",null).show();}
    @Override protected void onDestroy(){destroyed=true;disconnect();worker.shutdownNow();super.onDestroy();}
}
