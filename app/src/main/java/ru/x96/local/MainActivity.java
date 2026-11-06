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
    private final int[] scrollPositions=new int[4];private ScrollView pageScroll;private ListView packageList;private int listPosition=0,listTop=0,renderedTab=0;
    private ProjectivyTools.Report piReport,launchBaseline;
    private String launchResult="";
    private static final int PICK_APK=41;
    private volatile ApkSelection selectedApk;
    private volatile boolean installing=false;
    private String cancelledMessage="Соединение закрыто.";
    private long progressAt=0;

    private static final Map<String,String> NAMES=new HashMap<>();
    static {
        NAMES.put("com.google.android.videos","Google TV / Фильмы");NAMES.put("com.google.android.music","Google Play Музыка");
        NAMES.put("com.google.android.play.games","Google Play Игры");NAMES.put("com.google.android.apps.tachyon","Google Duo / Meet");
        NAMES.put("com.google.android.feedback","Отчёты об ошибках Google");NAMES.put("com.netflix.ninja","Netflix");
        NAMES.put("com.amazon.amazonvideo.livingroom","Prime Video");NAMES.put("com.google.android.youtube.tv","YouTube");
        NAMES.put("org.videolan.vlc","VLC");NAMES.put("com.plexapp.android","Plex");NAMES.put("org.xbmc.kodi","Kodi");
    }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if(b!=null){tab=Math.max(0,Math.min(3,b.getInt("tab",0)));recoveryOnly=b.getBoolean("mine",false);query=b.getString("query","");int[] positions=b.getIntArray("scroll");if(positions!=null&&positions.length==4)System.arraycopy(positions,0,scrollPositions,0,4);listPosition=b.getInt("list",0);listTop=b.getInt("top",0);}
        ApkSelection.sweep(getApplicationContext());
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(14),dp(8),dp(14),dp(4));root.setBackgroundColor(BG);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(dp(14),Math.max(dp(8),insets.getSystemWindowInsetTop()),dp(14),Math.max(dp(4),insets.getSystemWindowInsetBottom()));return insets;});
        LinearLayout heading=row();LinearLayout title=new LinearLayout(this);title.setOrientation(1);title.addView(text("X96 Локально",24,TEXT));title.addView(text("Удобные настройки приставки · 0.5.0",12,MUTED));heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
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
        LinearLayout tabs=row();String[] names={"Улучшения","Приложения","Пульт","Приставка"};
        for(int i=0;i<names.length;i++){final int target=i;Button btn=button(names[i],()->{capturePosition();tab=target;renderBody();},false);navigation.add(btn);tabs.addView(btn,weighted());}root.addView(tabs);
        body=new FrameLayout(this);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        if(b!=null){ip.setText(b.getString("ip",ip.getText().toString()));port.setText(b.getString("port",port.getText().toString()));}
        render();
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams weighted(){return new LinearLayout.LayoutParams(0,dp(48),1);}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);return l;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(3),dp(3),dp(3),dp(3));return t;}
    private EditText edit(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(14);return e;}
    private GradientDrawable shape(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));return d;}
    private LinearLayout card(LinearLayout parent){LinearLayout c=new LinearLayout(this);c.setOrientation(1);c.setPadding(dp(12),dp(9),dp(12),dp(9));c.setBackground(shape(CARD));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);parent.addView(c,p);return c;}
    private Button button(String label,Runnable task,boolean gate){Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setAllCaps(false);b.setTextColor(ACCENT);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(5),0,dp(5),0);b.setOnClickListener(v->task.run());if(gate){gated.add(b);b.setEnabled(!busy);}return b;}
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
    private void disconnect(){cancelledMessage=installing?"Соединение закрыто. Если APK уже был отправлен, установка могла завершиться на ТВ. Проверь установленную версию перед повтором.":"Соединение закрыто.";ApkSelection selection=selectedApk;selectedApk=null;if(selection!=null)selection.close();synchronized(sessionLock){generation++;AdbClient c=adb;if(c!=null)c.cancel();repo=null;adb=null;state=null;piReport=null;launchBaseline=null;launchResult="";currentIp="";}if(status!=null){status.setText("Отключено. Для полного закрытия доступа выключи сетевую отладку на ТВ.");render();}}
    private void run(String message,Job job){run(message,job,false);}
    private void run(String message,Job job,boolean connecting){run(message,job,connecting,null);}
    private void run(String message,Job job,boolean connecting,Runnable onSuccess){
        if(busy||destroyed)return;if(repo==null&&!connecting){status.setText("Сначала подключи приставку.");return;}
        final int token; synchronized(sessionLock){token=generation;}final TvRepository session=repo;
        busy=true;progress.setIndeterminate(true);status.setText(message);render();
        worker.execute(()->{Exception failure=null;TvRepository.State recovered=null;try{job.execute();}catch(Exception e){failure=e;if(session!=null)try{recovered=session.refresh();}catch(Exception ignored){}}
            final Exception result=failure;final TvRepository.State recovery=recovered;runOnUiThread(()->{if(destroyed)return;busy=false;
                if(token!=generation){state=null;repo=null;status.setText(cancelledMessage);}
                else if(result!=null){if(recovery!=null){state=recovery;}else{if(adb!=null)adb.cancel();adb=null;repo=null;state=null;}error(result);}
                else status.setText(state==null?"Отключено.":"Готово · соединение по Wi-Fi");render();if(result==null&&token==generation&&onSuccess!=null)onSuccess.run();});});
    }
    private void confirm(String title,String message,Job job){
        if(busy)return;if(repo==null){status.setText("Сначала подключи приставку.");return;}
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Отмена",null).setPositiveButton("Продолжить",(d,w)->run("Применяю и проверяю…",job)).show();
    }
    private void error(Exception e){String message=e.getMessage();if(message==null||message.trim().isEmpty())message=e.getClass().getSimpleName();status.setText("Не удалось завершить: "+message+"\nЕсли действие уже началось, проверь его результат перед повтором.");}
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
        if(tab==0)options(content);else if(tab==2)remote(content);else devicePage(content);
        ScrollView current=pageScroll;int target=scrollPositions[tab];current.post(()->current.scrollTo(0,target));
    }
    private void options(LinearLayout content){
        TextView lead=text("Настрой приставку под себя",20,TEXT);lead.setTypeface(null,Typeface.BOLD);content.addView(lead);content.addView(text("Каждый переключатель сохраняет прежние значения. Выключение возвращает их обратно.",13,MUTED));
        projectivyCard(content);
        launchCheck(content);
        for(Option o:Option.values()){
            LinearLayout c=card(content);LinearLayout heading=row();TextView label=text(o.title,17,TEXT);heading.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            Option.Status s=state==null?null:state.options.get(o);Switch toggle=new Switch(this);toggle.setContentDescription(o.title);toggle.setChecked(s!=null&&(s.saved||s.active));
            toggle.setEnabled(!busy&&s!=null&&(s.saved||s.supported&&!s.active));heading.addView(toggle);c.addView(heading);c.addView(text(o.description,13,MUTED));
            String note=s==null?"Состояние появится после подключения":!s.supported&&!s.saved?"Недоступно на этой прошивке":s.saved?(s.active?"Включено · прежнее значение сохранено":"Есть изменение для возврата"):s.active?"Уже включено на приставке":"Можно включить";
            c.addView(text(note,12,s!=null&&s.saved?ACCENT:MUTED));
            toggle.setOnCheckedChangeListener((v,on)->run(on?"Включаю: "+o.title:"Возвращаю: "+o.title,()->state=repo.option(o,on)));
        }
        LinearLayout storage=card(content);storage.addView(text("Освободить место",17,TEXT));storage.addView(text("Просит Android освободить место за счёт кеша, если свободно меньше 1 ГБ. Аккаунты, настройки и личные данные приложений сохраняются. Фактический объём очистки определяет Android.",13,MUTED));
        storage.addView(button("Очистить кеш при нехватке места",()->confirm("Освободить место?","Android может удалить временные файлы. Приложения загрузят их заново при необходимости. Если места достаточно, очистка может ничего не изменить.",()->state=repo.trimCache()),true));
        boolean hasChanges=false;if(state!=null)for(Option.Status s:state.options.values())if(s.saved)hasChanges=true;
        if(hasChanges){LinearLayout restore=card(content);restore.addView(text("Возврат настроек",17,TEXT));restore.addView(text("Отменяет изменения переключателей выше. Приложения и домашний экран возвращаются отдельно.",13,MUTED));restore.addView(button("Вернуть прежние настройки",()->confirm("Отменить настройки?","Вернём значения, сохранённые перед включением переключателей.",()->state=repo.restoreOptions()),true));}
        content.addView(text("Если настройка не поддерживается прошивкой, она недоступна. Изменения не применяются автоматически.",12,MUTED));
    }
    private void applications(){
        LinearLayout content=new LinearLayout(this);content.setOrientation(1);body.addView(content);
        content.addView(text(recoveryOnly?"Мои изменения":"Управление приложениями",20,TEXT));
        content.addView(button("Установить APK с телефона",()->chooseApk(),true));
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
            action.setEnabled(!busy);row.addView(action,new LinearLayout.LayoutParams(-1,dp(44)));if(!owned){Button more=button("Открыть / закрыть",()->new AlertDialog.Builder(MainActivity.this).setTitle(label(pkg)).setItems(new String[]{"Открыть на телевизоре","Закрыть, если зависло"},(d,w)->{if(w==0)run("Открываю приложение…",()->repo.openApp(pkg));else confirm("Закрыть приложение?","Оно завершит работу. Настройки и данные сохранятся; затем приложение можно открыть снова.",()->state=repo.closeApp(pkg));}).show(),false);more.setEnabled(!busy);row.addView(more);}return row;
        }
    }
    private void remote(LinearLayout content){
        content.addView(text("Пульт на телефоне",20,TEXT));content.addView(text("Управление меню приставки по Wi-Fi. Громкость меняется на приставке; управление громкостью телевизора зависит от подключения.",13,MUTED));
        LinearLayout c=card(content);addKeys(c,new String[]{"↑"},new int[]{19});addKeys(c,new String[]{"←","OK","→"},new int[]{21,23,22});addKeys(c,new String[]{"↓"},new int[]{20});
        addKeys(c,new String[]{"Назад","Домой","Пауза / ▶"},new int[]{4,3,85});addKeys(c,new String[]{"Тише","Без звука","Громче"},new int[]{25,164,24});
        LinearLayout settings=card(content);settings.addView(text("Открыть на телевизоре",17,TEXT));
        String[] labels={"Настройки приставки","Wi-Fi и сеть","Память и накопители","Экран и разрешение","Звук","Специальные возможности","Приложения","Дата и время"};for(int i=0;i<labels.length;i++){final int kind=i;settings.addView(button(labels[i],()->run("Открываю настройки на телевизоре…",()->repo.openSettings(kind)),true));}
    }
    private void addKeys(LinearLayout parent,String[] labels,int[] codes){LinearLayout r=row();for(int i=0;i<labels.length;i++){final int code=codes[i];r.addView(button(labels[i],()->run("Отправляю кнопку…",()->repo.remote(code)),true),weighted());}parent.addView(r);}
    private void refreshPi() throws Exception {piReport=repo.diagnoseProjectivy(false);piReport.comparison=launchResult;}
    private void launchCheck(LinearLayout content){
        LinearLayout c=card(content);c.addView(text("Проверка первого нажатия пульта",18,TEXT));
        c.addView(text("После загрузки приставки, не трогая пульт, сохрани первый замер с телефона. Затем нажми одну кнопку пульта и выполни второй замер. Во время проверки не меняй настройки и не обновляй лаунчер.",13,MUTED));
        c.addView(text(launchResult.isEmpty()?(launchBaseline==null?"Первый замер ещё не сохранён.":"Первый замер: "+launchBaseline.measuredAt+". Теперь нажми кнопку пульта."):launchResult,13,ACCENT));
        c.addView(button(launchBaseline==null?"1. Сохранить замер до нажатия":"1. Начать новый замер",()->run("Сохраняю первый замер…",()->{refreshPi();launchBaseline=piReport;launchResult="";piReport.comparison="";}),true));
        Button compare=button("2. Сравнить после нажатия",()->run("Сравниваю запуск…",()->{refreshPi();launchResult=ProjectivyTools.compare(launchBaseline,piReport);piReport.comparison=launchResult;}),false);
        compare.setEnabled(!busy&&state!=null&&launchBaseline!=null);c.addView(compare);
        c.addView(text("Замеры только читают состояние. Сравнение сбрасывается при отключении; результат входит в копируемый отчёт на вкладке «Приставка».",12,MUTED));
    }
    private void projectivyCard(LinearLayout content){
        LinearLayout c=card(content);c.addView(text("Projectivy: запуск без повторной заставки",18,TEXT));
        c.addView(text(piReport==null?"Проверим версию, системный домашний экран и службу перехвата. После перезагрузки можно сравнить процесс до и после первого нажатия.":"Версия: "+piReport.version+"\n"+piReport.advice,13,MUTED));
        c.addView(button("Проверить Projectivy",()->run("Проверяю запуск Projectivy…",()->refreshPi()),true));
        c.addView(button("Установить APK с телефона",()->chooseApk(),true));
        c.addView(button("Официальные обновления Projectivy",()->openLink("https://github.com/spocky/miproja1/releases/latest"),false));
        c.addView(button("Открыть Projectivy в Google Play на ТВ",()->run("Открываю Google Play на телевизоре…",()->repo.openProjectivyStore()),true));
        c.addView(button("Настроить системный HOME",()->confirm("Запустить Projectivy как домашний экран?","Сохраним прежний домашний экран, назначим Projectivy и явно запустим его как HOME-задачу. Проверить результат нужно также после перезагрузки.",()->{state=repo.repairProjectivy(false);refreshPi();}),true));
        if(piReport!=null&&piReport.intercept)c.addView(button("Попробовать без двойного перехвата",()->confirm("Отключить перехват Projectivy?","Сначала назначим и проверим системный HOME. Затем отключим только службу специальных возможностей Projectivy. Его ярлыки пульта и другие функции этой службы перестанут работать; службы других приложений сохранятся. Есть отдельный возврат."+(piReport.oldVersion?"\n\nСначала рекомендуется обновить Projectivy до 4.73 или новее.":""),()->{state=repo.repairProjectivy(true);refreshPi();}),true));
        if(piReport!=null&&piReport.interceptSaved)c.addView(button("Вернуть службу Projectivy",()->confirm("Вернуть перехват?","Добавим обратно только ранее отключённую службу Projectivy, сохранив текущие службы других приложений.",()->{state=repo.restoreProjectivyIntercept();refreshPi();}),true));
        if(state!=null&&state.homeSaved)c.addView(button("Вернуть прежний домашний экран",()->confirm("Вернуть прежний экран?","Восстановим выбор, сохранённый перед назначением Projectivy.",()->{state=repo.restoreHome();refreshPi();}),true));
    }
    private void openLink(String url){try{startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url)));}catch(Exception e){status.setText("На телефоне не найден браузер для открытия ссылки.");}}
    private Button rootButton(String label,Runnable action){Button b=button(label,action,false);b.setEnabled(!busy&&state!=null&&piReport!=null&&piReport.rootReady);return b;}
    private void devicePage(LinearLayout content){
        content.addView(text("Приставка и root",21,TEXT));content.addView(text("Действия выполняются только по нажатию. Неизвестные возможности прошивки не включаются автоматически.",13,MUTED));
        LinearLayout health=card(content);health.addView(text("Память и температура",18,TEXT));health.addView(text(piReport==null?"Нажми «Обновить диагностику», чтобы получить замер.":"Замер: "+piReport.measuredAt+"\n"+piReport.health.text()+"\nТемпература: "+piReport.temperature+"\nПоказан доступный датчик прошивки; это не обязательно температура процессора.",14,MUTED));
        LinearLayout info=card(content);info.addView(text("Прошивка и состояние",18,TEXT));
        info.addView(text(piReport==null?"Считаем сведения о плате, сборке Android, Projectivy и памяти. Серийный номер, MAC, IP и учётные записи в отчёт не добавляются.":piReport.text(),13,MUTED));
        info.addView(button("Обновить диагностику",()->run("Считываю сведения о приставке…",()->refreshPi()),true));
        Button copy=button("Скопировать отчёт",()->{if(piReport!=null){android.content.ClipboardManager m=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);m.setPrimaryClip(android.content.ClipData.newPlainText("Диагностика X96",piReport.text()));status.setText("Отчёт скопирован. Его можно вставить в наш чат.");}},false);copy.setEnabled(piReport!=null);info.addView(copy);
        LinearLayout root=card(content);root.addView(text("Доступ root",18,TEXT));root.addView(text(piReport==null?"Проверка root нужна перед расширенными действиями.":"Состояние: "+piReport.root,13,MUTED));
        root.addView(button("Проверить root / запросить su",()->confirm("Проверить root на приставке?","Если ADB уже работает с root, используем его. Иначе отправим su -c id -u: разреши запрос на экране телевизора. На телефоне root не нужен.",()->piReport=repo.diagnoseProjectivy(true)),true));
        LinearLayout code=card(content);code.addView(text("Оптимизация кода Projectivy",18,TEXT));code.addView(text("Для Android 8–13: заранее компилирует код установленного лаунчера. Может уменьшить работу компилятора при запуске, но не гарантирует ускорение и не исправляет конфликт HOME. Настройки Projectivy сохраняются. Выполнение может занять до трёх минут.",13,MUTED));
        code.addView(rootButton("Оптимизировать код",()->confirm("Оптимизировать Projectivy?","Android выполнит компиляцию приложения. Дождись завершения. Закрытие соединения не гарантирует остановку операции на приставке.",()->{state=repo.compileProjectivy(false);refreshPi();})));
        code.addView(rootButton("Сбросить оптимизацию кода",()->confirm("Сбросить компиляцию?","Android сбросит артефакты компиляции приложения к состоянию установки. Это не возврат старого профиля и не удаление настроек Projectivy.",()->{state=repo.compileProjectivy(true);refreshPi();})));
        LinearLayout ram=card(content);ram.addView(text("Временный приоритет Projectivy",18,TEXT));ram.addView(text("Повышает приоритет текущего процесса при нехватке памяти. Android может изменить его обратно; после перезапуска процесса настройка не переносится. Это не закрепление навсегда и не лечение двойного запуска. Остальным приложениям может оставаться меньше памяти.",13,MUTED));
        ram.addView(rootButton("Повысить приоритет текущего процесса",()->confirm("Повысить приоритет?","Root изменит только oom_score_adj проверенного процесса Projectivy. Сохраним прежнее значение; перед записью проверим PID, время старта и текущую загрузку системы.",()->{state=repo.prioritizeProjectivy(true);refreshPi();})));
        if(piReport!=null&&piReport.prioritySaved)ram.addView(rootButton("Вернуть прежний приоритет",()->confirm("Вернуть приоритет?","Вернём сохранённое значение только тому же процессу. Если процесс уже заменился, уберём старую запись, не затрагивая другой PID.",()->{state=repo.prioritizeProjectivy(false);refreshPi();})));
        LinearLayout firmware=card(content);firmware.addView(text("Информация о прошивках X96",18,TEXT));firmware.addView(text("Для X96 Max+ Ultra S905X4 найдены штатная прошивка и slimBOXtv. Совместимость зависит от ревизии платы, сетевого модуля и памяти; одного названия 4/64 недостаточно. Эти кнопки только открывают источники, прошивку приложение не устанавливает.",13,MUTED));
        firmware.addView(button("Штатная прошивка: источник",()->openLink("https://x96mini.com/pages/x96-max-ultra-android-smart-tv-box"),false));firmware.addView(button("slimBOXtv для Max Plus Ultra",()->openLink("https://slimboxtv.ru/x96max-plus-ultra/"),false));
    }
    private void chooseApk(){
        if(busy)return;if(repo==null){status.setText("Сначала подключи приставку, затем выбери APK.");return;}
        android.content.Intent pick=new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*");
        pick.putExtra(android.content.Intent.EXTRA_MIME_TYPES,new String[]{"application/vnd.android.package-archive","application/octet-stream"});
        try{startActivityForResult(pick,PICK_APK);}catch(Exception e){status.setText("На телефоне не найден выбор файлов.");}
    }
    @Override protected void onActivityResult(int request,int result,android.content.Intent data){
        super.onActivityResult(request,result,data);if(request!=PICK_APK||result!=RESULT_OK||data==null||data.getData()==null)return;
        if(repo==null||busy){status.setText("Для выбора APK снова подключи приставку.");return;}
        final TvRepository target=repo;final int token;synchronized(sessionLock){token=generation;}
        android.net.Uri uri=data.getData();
        run("Читаю выбранный APK…",()->{
            ApkSelection picked=ApkSelection.read(getApplicationContext(),uri,()->{synchronized(sessionLock){return token!=generation||destroyed;}});
            synchronized(sessionLock){if(token!=generation||destroyed){picked.close();throw new java.io.IOException("Выбор APK отменён");}selectedApk=picked;}
        },false,()->confirmApk(target,token));
    }
    private void confirmApk(TvRepository target,int token){
        final ApkSelection picked=selectedApk;if(picked==null)return;
        if(repo!=target||token!=generation){picked.close();selectedApk=null;return;}
        boolean update=state!=null&&state.packages.contains(picked.pkg);
        String detail=picked.label+"\n"+picked.pkg+"\nВерсия: "+picked.version+" ("+picked.code+")\nРазмер: "+String.format(Locale.ROOT,"%.1f МБ",picked.file.length()/1048576.0)+"\nМинимальный Android API: "+picked.minSdk+"\nSHA-256 файла:\n"+picked.sha256+
            (picked.pkg.equals(Policy.PROJECTIVY)?"\n\nСертификат совпадает с официальной GitHub-сборкой Projectivy.":"\n\nИсточник APK выбираешь ты; проверка файла не гарантирует безопасность приложения.")+"\n\n"+(update?"Обновление на приставке. Данные установленного приложения сохраняются стандартным установщиком Android.":"Новое приложение на приставке.");
        final boolean[] started={false};
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Установить на приставку?").setMessage(detail).setNegativeButton("Отмена",null).setPositiveButton("Установить",(d,w)->{
            if(busy||repo!=target||token!=generation){picked.close();selectedApk=null;status.setText("Соединение изменилось. Выбери файл заново.");return;}
            started[0]=true;installing=true;progressAt=0;
            run("Начинаю передачу APK…",()->{
                try{state=target.installApk(picked.file,picked.pkg,picked.code,picked.minSdk,(sent,total)->installProgress(token,sent,total));piReport=null;}
                finally{installing=false;picked.close();if(selectedApk==picked)selectedApk=null;}
            },false,()->status.setText("Установлено: "+picked.label+" "+picked.version+". Версия проверена на приставке."));
        }).create();
        dialog.setOnDismissListener(d->{if(!started[0]){picked.close();if(selectedApk==picked)selectedApk=null;}});dialog.show();
    }
    private void installProgress(int token,long sent,long total){
        long now=System.nanoTime();if(sent!=total&&now-progressAt<150000000L)return;progressAt=now;int percent=(int)(sent*100/total);
        runOnUiThread(()->{if(!destroyed&&busy&&installing&&token==generation){progress.setIndeterminate(false);progress.setMax(100);progress.setProgress(percent);status.setText(sent==total?"APK передан. Android выполняет установку на приставке…":"Передача APK: "+percent+"%");}});
    }
    @Override protected void onSaveInstanceState(Bundle saved){
        capturePosition();saved.putInt("tab",tab);saved.putBoolean("mine",recoveryOnly);saved.putString("query",query);saved.putIntArray("scroll",scrollPositions.clone());saved.putInt("list",listPosition);saved.putInt("top",listTop);saved.putString("ip",ip.getText().toString());saved.putString("port",port.getText().toString());super.onSaveInstanceState(saved);
    }
    private void about(){new AlertDialog.Builder(this).setTitle("X96 Локально 0.4.0").setMessage("Настройки, приложения и пульт для домашней Wi-Fi-сети.\n\nНет облака, аккаунтов, аналитики, внешних загрузок и автоматических обновлений. Прежние значения переключателей сохраняются на телефоне. Не удаляй приложение и его данные до восстановления.\n\nОтладка ADB даёт широкие права и не шифруется. После работы выключай сетевую отладку на приставке. Root-прошивка остаётся отдельным источником риска.\n\nВ этой версии подключение вручную по IPv4 и порту; шестизначное сопряжение Android 11+ не поддерживается.\n\nИсходный код: GPL-3.0-only. Правила защиты адаптированы из Tellyscope с атрибуцией CleanYourTV (MIT). Независимый проект.").setPositiveButton("Понятно",null).show();}
    @Override protected void onDestroy(){destroyed=true;disconnect();worker.shutdownNow();super.onDestroy();}
}
