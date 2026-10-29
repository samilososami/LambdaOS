/* SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Lambda Project.
 * Native setup shell. Credentials, biometric enrollment, backup and emergency
 * calls remain owned by Android/Seedvault, never by mock controls or shell scripts.
 */
package org.lineageos.setupwizard;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.hardware.biometrics.BiometricManager;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.provider.Settings;
import android.service.persistentdata.PersistentDataBlockManager;
import android.telecom.TelecomManager;
import android.telephony.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.google.android.setupcompat.util.WizardManagerHelper;
import org.lineageos.setupwizard.util.SetupWizardUtils;
import java.text.DateFormat;
import java.util.*;

public class LambdaSetupActivity extends BaseSetupWizardActivity {
    private LambdaUi ui;
    private LinearLayout root,body,footer;
    private LambdaUi.Battery battery;
    private SharedPreferences prefs;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String page="welcome",pending="",message="",selectedSsid="";
    private String renderedPage="";
    private Configuration renderedConfiguration;
    private ScanResult selectedNetwork;
    private boolean scanning,connecting,registered,completing;
    private Button wifiNext,simNext,simLater;
    private TextView applyingStatus;
    private LambdaUi.SetupProgress applyingMark;
    private long applyingStarted;
    private boolean hiddenNetwork;
    private long scanStarted;
    private final ArrayDeque<String> history=new ArrayDeque<>();
    private ActivityResultLauncher<Intent> external;
    private WifiManager wifi;
    private ConnectivityManager connectivity;
    private Network wifiNetwork;
    private boolean wifiValidated,wifiPortal,batteryCharging;
    private int connectionGeneration;
    private int batteryLevel=100;
    private final Runnable connectionTimeout=()->{
        if(connecting){connecting=false;
            if(wifiNetwork!=null && ssid().equals(selectedSsid)){go(wifiValidated?"wifi-success":wifiPortal?"wifi-portal":"wifi-local");return;}
            message=t("No se ha podido conectar. Revisa la contraseña o inténtalo de nuevo.","Couldn't connect. Check the password or try again.");go("wifi-error");}
    };
    private final Runnable scanTimeout=()->{scanning=false;if(page.equals("wifi"))renderWifiRows();};
    private LinearLayout wifiRows;
    private LinearLayout simRows;
    private TextView timePreview,dayPreview;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            String action=i.getAction();
            if(Intent.ACTION_BATTERY_CHANGED.equals(action)){
                int scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,100);
                batteryLevel=100*i.getIntExtra(BatteryManager.EXTRA_LEVEL,100)/Math.max(1,scale);
                batteryCharging=i.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
                if(battery!=null)battery.update(batteryLevel,batteryCharging);
            }else if(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)){
                handler.removeCallbacks(scanTimeout);
                handler.postDelayed(scanTimeout,Math.max(0,700-(SystemClock.uptimeMillis()-scanStarted)));
            }else if(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(action)&&connecting&&i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR,-1)==WifiManager.ERROR_AUTHENTICATING){
                connecting=false;handler.removeCallbacks(connectionTimeout);message=t("Contraseña incorrecta. Inténtalo de nuevo.","Incorrect password. Try again.");go("wifi-error");
            }else if("android.intent.action.SIM_STATE_CHANGED".equals(action)&&page.equals("sim"))renderSimRows();
        }
    };
    private final ConnectivityManager.NetworkCallback networkCallback=new ConnectivityManager.NetworkCallback(){
        @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){handler.post(()->{
            wifiNetwork=n;wifiValidated=c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);wifiPortal=c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
            if(connecting && ssid().equals(selectedSsid)){
                if(wifiValidated){connecting=false;handler.removeCallbacks(connectionTimeout);message="";go("wifi-success");}
                else if(wifiPortal){connecting=false;handler.removeCallbacks(connectionTimeout);go("wifi-portal");}
                // Leave the original deadline intact; repeated capabilities must not
                // postpone a local-only network indefinitely.
            }
            if(page.equals("wifi"))renderWifiRows();
        });}
        @Override public void onLost(Network n){handler.post(()->{if(n.equals(wifiNetwork)){wifiNetwork=null;wifiValidated=false;wifiPortal=false;if(page.equals("wifi"))renderWifiRows();}});}
    };

    @Override protected void onCreate(Bundle state){
        setTheme(new LambdaUi(this).isLight()?R.style.Theme_LambdaSetup_Light:R.style.Theme_LambdaSetup);
        super.onCreate(state);
        prefs=getSharedPreferences("LambdaSetup",MODE_PRIVATE);
        renderedConfiguration=new Configuration(getResources().getConfiguration());
        ui=new LambdaUi(this);
        wifi=getSystemService(WifiManager.class);connectivity=getSystemService(ConnectivityManager.class);
        external=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
            String action=pending;pending="";prefs.edit().remove("pending").apply();
            if(action.equals("lock")){if(getSystemService(KeyguardManager.class).isDeviceSecure())continueTo("fingerprint");else render();}
            else if(action.equals("remove-lock")){
                // A canceled confirmation must never skip the security step.
                // Trust the native result AND the actual system credential state.
                if(result.getResultCode()==RESULT_OK&&!getSystemService(KeyguardManager.class).isDeviceSecure())continueTo("theme");
                else {render();if(result.getResultCode()==RESULT_OK)Toast.makeText(this,t("El dispositivo sigue requiriendo un bloqueo de pantalla.","This device still requires a screen lock."),Toast.LENGTH_LONG).show();}
            }
            else if(action.equals("fingerprint")){continueTo("theme");}
            else if(action.equals("restore")){
                if(result.getResultCode()==RESULT_OK){prefs.edit().putBoolean("restored",true).apply();go("restore-done");}else render();
            }else if(action.equals("wifi-settings")){go("wifi");scan();}
            else render();
        });
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){@Override public void handleOnBackPressed(){back();}});
        String saved=prefs.getString("page","welcome");
        page=state==null?saved:state.getString("page",saved);
        if(state!=null){ArrayList<String> list=state.getStringArrayList("history");if(list!=null)history.addAll(list);}
        if(page.startsWith("wifi-"))page="wifi";
        if(page.equals("applying")||page.equals("error"))page="summary";
        if(page.equals("emergency"))page="welcome";
        if(state==null&&!page.equals("welcome")&&!page.equals("finished")){prefs.edit().putString("resume",page).apply();page="resume";}
        pending=prefs.getString("pending","");
        LambdaWindow.configure(this);
        render();
    }
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("page",page);b.putStringArrayList("history",new ArrayList<>(history));}
    @Override protected void onStart(){super.onStart();IntentFilter f=new IntentFilter(Intent.ACTION_BATTERY_CHANGED);f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);f.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);f.addAction("android.intent.action.SIM_STATE_CHANGED");registerReceiver(receiver,f,Context.RECEIVER_EXPORTED);registered=true;
        connectivity.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),networkCallback);}
    @Override protected void onStop(){if(registered){unregisterReceiver(receiver);connectivity.unregisterNetworkCallback(networkCallback);registered=false;}super.onStop();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override public void onConfigurationChanged(Configuration c){
        int changes=renderedConfiguration==null?~0:renderedConfiguration.diff(c);
        super.onConfigurationChanged(c);renderedConfiguration=new Configuration(c);
        if((changes&~android.content.pm.ActivityInfo.CONFIG_UI_MODE)==0){repaintAppearance();return;}
        ui=new LambdaUi(this);render();
    }
    private int nightMode(String value){return value.equals("light")?UiModeManager.MODE_NIGHT_NO:value.equals("auto")?UiModeManager.MODE_NIGHT_AUTO:UiModeManager.MODE_NIGHT_YES;}
    private void repaintAppearance(){
        if(ui==null||root==null)return;
        setTheme(ui.isLight()?R.style.Theme_LambdaSetup_Light:R.style.Theme_LambdaSetup);
        root.setBackground(new LambdaBackground(ui.isLight()));ui.refreshTheme();if(battery!=null)battery.invalidate();LambdaWindow.configure(this);
    }
    @Override protected void applyForwardTransition(){overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);}
    @Override protected void applyBackwardTransition(){overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);}
    private String t(String es,String en){return Locale.getDefault().getLanguage().equals("es")?es:en;}
    private String next(){return t("Continuar","Continue");}
    private void editSection(String section){prefs.edit().putString("edit_section",section).apply();go(section);}
    private void continueTo(String target){
        if(!prefs.getString("edit_section","").isEmpty()){
            prefs.edit().remove("edit_section").apply();history.clear();go("summary");
        }else go(target);
    }
    private void go(String target){
        if(target.equals("welcome"))prefs.edit().remove("edit_section").apply();
        if(!target.equals(page)){history.push(page);page=target;}
        prefs.edit().putString("page",page).apply();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(),0);
        message=page.equals("wifi-error")||page.equals("error")?message:"";render();
    }
    private void back(){if(completing)return;if(connecting){connecting=false;connectionGeneration++;handler.removeCallbacks(connectionTimeout);}
        if(page.equals("welcome"))return;
        if(page.equals(prefs.getString("edit_section",""))){continueTo("summary");return;}
        if(page.equals("skip")){history.clear();page="welcome";}else page=history.isEmpty()?"welcome":history.pop();
        if(page.startsWith("wifi-"))page="wifi";
        if(page.equals("applying"))page="summary";
        prefs.edit().putString("page",page).apply();render();}
    private void shell(String title,String icon){
        ui.clearThemeBindings();root=ui.column();root.setBackground(new LambdaBackground(ui.isLight()));root.setPadding(ui.dp(24),0,ui.dp(24),ui.dp(12));root.setFocusableInTouchMode(true);
        setContentView(root);
        LambdaWindow.insetContent(root,ui);
        LinearLayout status=ui.line();View space=new View(this);status.addView(space,new LinearLayout.LayoutParams(0,ui.dp(24),1));battery=new LambdaUi.Battery(this);battery.update(batteryLevel,batteryCharging);status.addView(battery,new LinearLayout.LayoutParams(ui.dp(20),ui.dp(11)));root.addView(status);
        LinearLayout top=ui.line();ImageButton back=ui.iconButton("chevron-left",t("Atrás","Back"),this::back);back.setVisibility(page.equals("welcome")?View.INVISIBLE:View.VISIBLE);top.addView(back,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));LinearLayout.LayoutParams topParams=new LinearLayout.LayoutParams(-1,ui.dp(48));topParams.topMargin=page.equals("welcome")?0:-ui.dp(12);root.addView(top,topParams);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);body=ui.column();scroll.addView(body,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(title!=null){ui.gap(body,22);LinearLayout heading=ui.line();TextView text=ui.text(title,32,LambdaUi.FG);text.setAccessibilityHeading(true);heading.addView(text,new LinearLayout.LayoutParams(0,-2,1));if(icon!=null){LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(ui.dp(64),ui.dp(64));ip.setMarginStart(ui.dp(18));ip.setMarginEnd(ui.dp(6));heading.addView(ui.icon(icon,64),ip);}body.addView(heading);ui.gap(body,18);}
        footer=ui.column();root.addView(footer,new LinearLayout.LayoutParams(-1,-2));
    }
    private Button primary(String label,Runnable r){Button b=ui.button(label,true,r);footer.addView(b,new LinearLayout.LayoutParams(-1,-2));ui.gap(footer,8);return b;}
    private void secondary(String label,Runnable r){Button b=ui.button(label,false,r);b.setTextSize(15);ui.textColor(b,LambdaUi.MUTED);footer.addView(b,new LinearLayout.LayoutParams(-1,-2));}
    private void note(String text){TextView p=ui.text(text,15,LambdaUi.MUTED);body.addView(p);ui.gap(body,16);}
    private void centered(String title,String detail,String icon){shell(null,null);LinearLayout c=ui.column();c.setGravity(Gravity.CENTER);c.setPadding(0,ui.dp(60),0,ui.dp(36));if(icon!=null)c.addView(ui.icon(icon,120));ui.gap(c,30);TextView h=ui.text(title,34,LambdaUi.FG);h.setGravity(Gravity.CENTER);h.setAccessibilityHeading(true);c.addView(h);if(detail!=null){ui.gap(c,18);TextView p=ui.text(detail,16,LambdaUi.MUTED);p.setGravity(Gravity.CENTER);c.addView(p);}body.addView(c,new LinearLayout.LayoutParams(-1,0,1));}
    private void render(){
        boolean entering=!page.equals(renderedPage);renderedPage=page;
        switch(page){
            case "welcome":welcome();break;
            case "skip":skip();break;
            case "emergency":emergencyPage();break;
            case "language":language();break;
            case "region":region();break;
            case "accessibility":accessibility();break;
            case "text-size":textSize();break;
            case "sim":sim();break;
            case "wifi":wifiPage();break;
            case "wifi-password":wifiPassword();break;
            case "wifi-add":addNetwork();break;
            case "wifi-connecting":connectingPage();break;
            case "wifi-success":centered(t("Conectado correctamente","Connected successfully"),selectedSsid,"circle-check");primary(next(),()->continueTo("datetime"));secondary(t("Volver a las redes","Back to networks"),()->go("wifi"));break;
            case "wifi-error":centered(t("No se ha podido conectar","Couldn't connect"),message,"circle-alert");primary(t("Intentar de nuevo","Try again"),()->go(hiddenNetwork?"wifi-add":"wifi-password"));secondary(t("Volver a las redes","Back to networks"),()->go("wifi"));break;
            case "wifi-portal":centered(t("Inicia sesión en la red","Sign in to the network"),ssid(),"globe");primary(t("Abrir portal","Open portal"),()->{if(wifiNetwork!=null)connectivity.startCaptivePortalApp(wifiNetwork);});secondary(next(),()->continueTo("datetime"));break;
            case "wifi-local":centered(t("Sin acceso a Internet","No internet access"),t("Puedes continuar con esta red o escoger otra.","You can continue with this network or choose another."),"globe-x");primary(next(),()->continueTo("datetime"));secondary(t("Cambiar red","Change network"),()->go("wifi"));break;
            case "offline":centered(t("¿Configurar sin conexión?","Set up offline?"),t("Puedes conectarte a una red más tarde desde Ajustes.","You can connect to a network later in Settings."),"wifi-off");primary(next(),()->continueTo("datetime"));secondary(t("Volver a las redes","Back to networks"),()->go("wifi"));break;
            case "datetime":dateTime();break;
            case "timezone":timeZone();break;
            case "restore":restore();break;
            case "restore-done":centered(t("Restauración completada","Restore complete"),t("Tu copia se ha restaurado con Seedvault.","Your backup was restored with Seedvault."),"circle-check");primary(next(),()->go("location"));break;
            case "location":location();break;
            case "lock":lock();break;
            case "skip-lock":withoutLock();break;
            case "fingerprint":fingerprint();break;
            case "theme":appearance();break;
            case "layout":choices("layout",t("Tu forma de usar\nLambda Operator","Your way to use\nLambda Operator"),new String[]{t("Iconos","Icons"),t("Texto","Text")},new String[]{"icons","text"},"navigation");break;
            case "navigation":choices("navigation",t("Muévete a tu manera","Find your way"),new String[]{t("Gestos","Gestures"),t("Botones","Buttons")},new String[]{"gestures","buttons"},"haptics");break;
            case "haptics":haptics();break;
            case "summary":summary();break;
            case "applying":applyingPage();break;
            case "finished":finished();break;
            case "resume":centered(t("Continuemos","Let's continue"),t("Tu configuración se ha guardado.","Your setup progress has been saved."),"rotate-ccw");primary(next(),()->go(prefs.getString("resume","language")));secondary(t("Volver al inicio","Back to welcome"),()->go("welcome"));break;
            case "error":centered(t("No se ha podido completar","Couldn't complete setup"),message,"circle-alert");primary(t("Intentar de nuevo","Try again"),this::apply);secondary(t("Revisar ajustes","Review settings"),()->go("summary"));break;
            default:page="welcome";welcome();
        }
        // Animate only an actual page entrance; the background/status/back stay still.
        if(entering&&!page.equals("finished")){ui.enter(body);ui.enter(footer);}
    }
    private void welcome(){
        shell(null,null);body.addView(ui.welcomeHero(),new LinearLayout.LayoutParams(-1,0,1));
        primary("Start",()->go("language"));LinearLayout links=ui.line();Button skip=ui.link("Skip setup","fast-forward",()->go("skip"));Button emergency=ui.link("Emergency call","phone",this::emergencyPage);skip.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);emergency.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);links.addView(skip,new LinearLayout.LayoutParams(-2,ui.dp(48)));links.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));links.addView(emergency,new LinearLayout.LayoutParams(-2,ui.dp(48)));footer.addView(links);
    }
    private boolean canComplete(){
        if(!SetupWizardUtils.isOwner()||SetupWizardUtils.isManagedProfile(this))return false;
        if(getSystemService(android.app.admin.DevicePolicyManager.class).isDeviceManaged())return false;
        if(Settings.Secure.getInt(getContentResolver(),Settings.Secure.USER_SETUP_COMPLETE,0)!=0)return false;
        try{PersistentDataBlockManager block=getSystemService(PersistentDataBlockManager.class);return block==null||!block.isFactoryResetProtectionActive();}catch(RuntimeException e){return false;}
    }
    private void skip(){
        shell("Skip setup?","fast-forward");note("Continue with the default settings.");
        ui.row(body,"Language","English","languages",null);
        ui.row(body,"Wi-Fi",wifiNetwork==null?"Not configured":"Existing connection is kept","wifi-off",null);
        ui.row(body,"Backup","Nothing restored by Skip setup","archive-restore",null);
        ui.row(body,"Screen lock",getSystemService(KeyguardManager.class).isDeviceSecure()?"Existing screen lock is kept":"Not set","lock-keyhole",null);
        ui.gap(body,18);note("You can change these settings later.");
        Button b=primary("Skip setup",()->{
            if(!canComplete()){blocked();return;}
            prefs.edit().putString("theme","dark").putString("layout","icons").putString("navigation","gestures").putBoolean("haptics",true).putBoolean("skipped",true).putString("page","summary").commit();
            page="summary";com.android.internal.app.LocalePicker.updateLocale(Locale.US);apply();
        });b.setEnabled(canComplete());b.setAlpha(b.isEnabled()?1:.5f);if(!b.isEnabled())note("Setup cannot be skipped on a provisioned, managed or factory-reset-protected device.");secondary("Back to setup",()->go("welcome"));
    }
    private void language(){
        shell(t("Elige tu idioma","Choose your language"),"languages");
        List<com.android.internal.app.LocalePicker.LocaleInfo> locales=new ArrayList<>(com.android.internal.app.LocalePicker.getAllAssetLocales(this,false));
        // Development pseudo-locales are not meaningful language choices.
        locales.removeIf(info->info.getLocale().getCountry().matches("X[ABC]")||info.getLocale().getLanguage().equals("zz"));
        String selected=prefs.getString("selected_locale",Locale.getDefault().toLanguageTag());
        String[] labels=new String[locales.size()];int index=0;boolean exact=false;
        for(int i=0;i<labels.length;i++){Locale candidate=locales.get(i).getLocale();labels[i]=locales.get(i).getLabel();if(!exact&&candidate.getLanguage().equals(Locale.forLanguageTag(selected).getLanguage()))index=i;if(candidate.toLanguageTag().equals(selected)){index=i;exact=true;}}
        if(locales.isEmpty()){note(t("No hay idiomas disponibles","No languages available"));return;}
        LambdaLanguageWheel wheel=new LambdaLanguageWheel(this,ui,labels,index,i->prefs.edit().putString("selected_locale",locales.get(i).getLocale().toLanguageTag()).apply());
        LinearLayout wheelGroup=ui.column();wheelGroup.setGravity(Gravity.CENTER_VERTICAL);body.addView(wheelGroup,new LinearLayout.LayoutParams(-1,0,1));
        ImageButton search=ui.iconButton("search",t("Buscar idioma","Search languages"),()->{
            LinearLayout box=ui.column();box.setPadding(ui.dp(20),ui.dp(8),ui.dp(20),ui.dp(12));
            EditText input=ui.field(box,t("Buscar idioma","Search languages"),InputType.TYPE_CLASS_TEXT);
            ListView results=new ListView(this);box.addView(results,new LinearLayout.LayoutParams(-1,ui.dp(260)));
            ArrayList<Integer> matches=new ArrayList<>();ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,new ArrayList<>()){
                @Override public View getView(int p,View v,ViewGroup parent){TextView text=(TextView)super.getView(p,v,parent);text.setTextColor(ui.color(LambdaUi.FG));text.setTypeface(ui.regular);text.setTextSize(17);return text;}
            };results.setAdapter(adapter);
            Runnable filter=()->{matches.clear();adapter.clear();String q=input.getText().toString().toLowerCase(Locale.ROOT);for(int i=0;i<labels.length;i++)if(labels[i].toLowerCase(Locale.ROOT).contains(q)||locales.get(i).getLocale().getDisplayName().toLowerCase(Locale.ROOT).contains(q)){matches.add(i);adapter.add(labels[i]);}};
            AlertDialog dialog=new AlertDialog.Builder(this).setView(box).setNegativeButton(t("Cancelar","Cancel"),null).create();
            results.setOnItemClickListener((p,v,position,id)->{int match=matches.get(position);wheel.picker.setValue(match);prefs.edit().putString("selected_locale",locales.get(match).getLocale().toLanguageTag()).apply();dialog.dismiss();});
            input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){filter.run();}});filter.run();dialog.show();input.requestFocus();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48));searchParams.gravity=Gravity.CENTER_HORIZONTAL;wheelGroup.addView(search,searchParams);ui.gap(wheelGroup,8);wheelGroup.addView(wheel,new LinearLayout.LayoutParams(-1,ui.dp(238)));ui.gap(wheelGroup,40);
        primary(next(),()->{Locale choice=locales.get(wheel.picker.getValue()).getLocale();prefs.edit().putString("selected_locale",choice.toLanguageTag()).apply();go("region");if(!choice.equals(Locale.getDefault()))com.android.internal.app.LocalePicker.updateLocale(choice);});
        secondary(t("Accesibilidad","Accessibility"),()->go("accessibility"));
    }
    private void region(){
        shell(t("Tu región","Your region"),"globe");
        Locale language=Locale.forLanguageTag(prefs.getString("selected_locale",Locale.getDefault().toLanguageTag()));
        String suggested=language.getCountry();
        ui.gap(body,16);note(t("La región ajusta los formatos de fecha y números. No cambia tu zona horaria ni tu ubicación.","Your region sets date and number formats. It does not change your time zone or location."));
        String[] countries=Locale.getISOCountries();java.text.Collator collator=java.text.Collator.getInstance();
        Arrays.sort(countries,(a,b)->collator.compare(new Locale("",a).getDisplayCountry(),new Locale("",b).getDisplayCountry()));
        String[] labels=new String[countries.length];int selected=0;
        for(int i=0;i<countries.length;i++){labels[i]=new Locale("",countries[i]).getDisplayCountry();if(countries[i].equals(suggested))selected=i;}
        LinearLayout group=ui.column();group.setGravity(Gravity.CENTER_VERTICAL);body.addView(group,new LinearLayout.LayoutParams(-1,0,1));
        LambdaLanguageWheel wheel=new LambdaLanguageWheel(this,ui,labels,selected,i->{});group.addView(wheel,new LinearLayout.LayoutParams(-1,ui.dp(238)));ui.gap(group,48);
        primary(next(),()->selectRegion(language,countries[wheel.picker.getValue()]));
    }
    private void selectRegion(Locale language,String country){
        Locale selected=new Locale.Builder().setLocale(language).setRegion(country).build();
        prefs.edit().putString("selected_locale",selected.toLanguageTag()).apply();continueTo("sim");
        if(!selected.equals(Locale.getDefault()))com.android.internal.app.LocalePicker.updateLocale(selected);
    }
    private void accessibility(){shell(t("Accesibilidad","Accessibility"),"accessibility");
        ui.row(body,t("Tamaño del texto","Text size"),null,null,()->go("text-size"));
        android.view.accessibility.AccessibilityManager manager=getSystemService(android.view.accessibility.AccessibilityManager.class);
        List<android.accessibilityservice.AccessibilityServiceInfo> services=manager.getInstalledAccessibilityServiceList();
        android.accessibilityservice.AccessibilityServiceInfo reader=null;
        for(var service:services)if((service.feedbackType&android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN)!=0){reader=service;break;}
        if(reader!=null){final var service=reader;ui.row(body,service.getResolveInfo().loadLabel(getPackageManager()).toString(),t("Lector de pantalla","Screen reader"),"speech",()->launch(new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME,ComponentName.unflattenFromString(service.getId())),"accessibility"));}
        else {ui.gap(body,22);note(t("No hay ningún lector de pantalla instalado en esta versión.","No screen reader is installed on this build."));}
        ui.row(body,t("Más opciones","More options"),null,null,()->launch(new Intent("android.settings.ACCESSIBILITY_SETTINGS_FOR_SUW"),"accessibility"));primary(next(),()->go("language"));}
    private void textSize(){shell(t("Tamaño del texto","Text size"),"accessibility");
        float[] scales={.85f,1f,1.15f,1.3f,1.5f};float current=Settings.System.getFloat(getContentResolver(),Settings.System.FONT_SCALE,1f);int index=0;for(int i=1;i<scales.length;i++)if(Math.abs(scales[i]-current)<Math.abs(scales[index]-current))index=i;
        TextView sample=ui.text(t("Así se verá el texto.\nElige el tamaño que te resulte más cómodo.","This is how text will look.\nChoose a comfortable size."),22,LambdaUi.FG);body.addView(sample);ui.gap(body,32);
        SeekBar slider=new SeekBar(this);slider.setMax(scales.length-1);slider.setProgress(index);slider.setContentDescription(t("Tamaño del texto","Text size"));slider.setThumbTintList(android.content.res.ColorStateList.valueOf(ui.color(LambdaUi.FG)));slider.setProgressTintList(android.content.res.ColorStateList.valueOf(ui.color(LambdaUi.MUTED)));body.addView(slider,new LinearLayout.LayoutParams(-1,ui.dp(56)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int p,boolean user){sample.setTextSize(22*scales[p]/current);}});
        primary(t("Guardar","Save"),()->{Settings.System.putFloat(getContentResolver(),Settings.System.FONT_SCALE,scales[slider.getProgress()]);go("accessibility");});}
    private void sim(){shell(t("Tu tarjeta SIM","Your SIM card"),"card-sim");simRows=ui.column();body.addView(simRows);simNext=primary(next(),()->{go("wifi");scan();});simLater=ui.button(t("Configurar más tarde","Set up later"),false,()->{go("wifi");scan();});simLater.setTextSize(15);ui.textColor(simLater,LambdaUi.MUTED);footer.addView(simLater,new LinearLayout.LayoutParams(-1,-2));renderSimRows();}
    private void renderSimRows(){if(simRows==null||!page.equals("sim"))return;simRows.removeAllViews();TelephonyManager tm=getSystemService(TelephonyManager.class);SubscriptionManager subscriptions=getSystemService(SubscriptionManager.class);
        List<SubscriptionInfo> active=subscriptions==null?null:subscriptions.getActiveSubscriptionInfoList();boolean present=false;
        int slots=tm==null?0:tm.getActiveModemCount();
        for(int slot=0;slot<slots;slot++){int state=tm.getSimState(slot);if(state==TelephonyManager.SIM_STATE_ABSENT||state==TelephonyManager.SIM_STATE_UNKNOWN)continue;present=true;
            String label="SIM "+(slot+1);if(active!=null)for(SubscriptionInfo info:active)if(info.getSimSlotIndex()==slot)label=info.getDisplayName().toString();
            String status=state==TelephonyManager.SIM_STATE_READY||state==TelephonyManager.SIM_STATE_LOADED?t("Detectada y lista","Detected and ready"):state==TelephonyManager.SIM_STATE_PIN_REQUIRED?t("Se necesita el PIN","PIN required"):state==TelephonyManager.SIM_STATE_PUK_REQUIRED?t("Se necesita el PUK","PUK required"):state==TelephonyManager.SIM_STATE_CARD_IO_ERROR?t("Error de lectura de la SIM","SIM read error"):t("Comprobando tarjeta…","Checking card…");
            ui.row(simRows,label,status,"card-sim",null);
        }
        simNext.setEnabled(true);simNext.setAlpha(1);simNext.setText(next());simLater.setVisibility(present?View.VISIBLE:View.GONE);
        if(!present){simRows.addView(ui.text(t("No se ha detectado ninguna SIM.\nInsértala ahora o configúrala más tarde.","No SIM detected.\nInsert one now or set it up later."),17,LambdaUi.MUTED));ui.gap(simRows,28);simRows.addView(ui.text(t("La detectaremos automáticamente al insertarla.","We'll detect it automatically when you insert it."),15,LambdaUi.MUTED));}
        else ui.row(simRows,t("Configurar servicios móviles","Set up mobile service"),t("Selecciona la SIM para datos, llamadas y mensajes.","Choose a SIM for data, calls and messages."),"settings",()->launch(new Intent("android.settings.NETWORK_PROVIDER_SETUP"),"sim"));
    }
    private String ssid(){if(wifi==null)return "";WifiInfo i=wifi.getConnectionInfo();String s=i==null?null:i.getSSID();return s==null||s.equals(WifiManager.UNKNOWN_SSID)?"":s.replaceAll("^\"|\"$","");}
    private void wifiPage(){shell(t("Conéctate\na una red","Connect to\na network"),"wifi");
        // Widen the scroll viewport, not its drawing clip. The stripe reaches
        // both screen edges while off-screen rows stay behind neither footer nor status.
        ScrollView scroll=(ScrollView)body.getParent();LinearLayout.LayoutParams viewport=(LinearLayout.LayoutParams)scroll.getLayoutParams();viewport.setMargins(-ui.dp(24),0,-ui.dp(24),0);scroll.setLayoutParams(viewport);scroll.setClipChildren(true);scroll.setClipToPadding(true);root.setClipToPadding(false);
        body.setPadding(ui.dp(24),0,ui.dp(24),0);body.setClipChildren(false);body.setClipToPadding(false);
        LinearLayout.LayoutParams actions=(LinearLayout.LayoutParams)footer.getLayoutParams();actions.setMargins(-ui.dp(24),0,-ui.dp(24),0);footer.setLayoutParams(actions);
        footer.setPadding(ui.dp(24),ui.dp(12),ui.dp(24),0);final View wifiFooter=footer;ui.bindTheme(()->wifiFooter.setBackgroundColor(ui.color(LambdaUi.BG)));
        wifiRows=ui.column();LinearLayout.LayoutParams rows=new LinearLayout.LayoutParams(-1,-2);rows.setMargins(-ui.dp(24),0,-ui.dp(24),0);body.addView(wifiRows,rows);wifiNext=primary(next(),()->{if(wifiValidated)continueTo("datetime");else go(wifiPortal?"wifi-portal":"wifi-local");});secondary(t("Configurar sin conexión","Set up offline"),()->go("offline"));renderWifiRows();}
    private void scan(){if(wifi==null||scanning)return;message="";try{wifi.setWifiEnabled(true);scanning=wifi.startScan();scanStarted=SystemClock.uptimeMillis();handler.removeCallbacks(scanTimeout);if(scanning)handler.postDelayed(scanTimeout,10000);else message=t("No se ha podido iniciar otra búsqueda todavía. Se muestran las últimas redes detectadas.","A new scan couldn't start yet. Showing the latest detected networks.");if(page.equals("wifi"))renderWifiRows();}catch(SecurityException e){scanning=false;message=t("No se pueden consultar las redes. Abre los ajustes de Wi-Fi.","Can't read networks. Open Wi-Fi settings.");if(page.equals("wifi"))renderWifiRows();}}
    private void renderWifiRows(){if(wifiRows==null||!page.equals("wifi"))return;wifiRows.removeAllViews();String current=ssid();wifiNext.setEnabled(wifiNetwork!=null&&!current.isEmpty());wifiNext.setAlpha(wifiNext.isEnabled()?1:.45f);
        if(wifiNetwork!=null&&!current.isEmpty()){
            WifiInfo info=wifi.getConnectionInfo();boolean secured=info!=null&&info.getCurrentSecurityType()!=WifiInfo.SECURITY_TYPE_OPEN&&info.getCurrentSecurityType()!=WifiInfo.SECURITY_TYPE_OWE&&info.getCurrentSecurityType()!=WifiInfo.SECURITY_TYPE_UNKNOWN;
            ui.networkRow(wifiRows,current,wifiValidated?t("Conectado · Acceso a Internet","Connected · Internet access"):wifiPortal?t("Toca para iniciar sesión","Tap to sign in"):t("Conectado sin Internet","Connected without Internet"),secured,true,wifiValidated?"circle-check":"circle-alert",wifiPortal?()->go("wifi-portal"):null);
        }
        try{List<ScanResult> scans=wifi==null?List.of():new ArrayList<>(wifi.getScanResults());scans.sort((a,b)->Integer.compare(b.level,a.level));Set<String> seen=new HashSet<>();if(wifiNetwork!=null)seen.add(current);
            for(ScanResult result:scans){if(result.SSID.isEmpty()||!seen.add(result.SSID))continue;boolean open=!result.capabilities.matches(".*(PSK|SAE|EAP|WEP|OWE).*" );
                boolean locked=!open&&!result.capabilities.contains("OWE");
                ui.networkRow(wifiRows,result.SSID,locked?t("Protegida","Secured"):t("Abierta","Open"),locked,false,"chevron-right",()->{hiddenNetwork=false;selectedNetwork=result;selectedSsid=result.SSID;if(result.capabilities.contains("EAP")||result.capabilities.contains("WEP")){wifiPicker();}else if(open||result.capabilities.contains("OWE")){connect("");}else go("wifi-password");});}
            if(seen.isEmpty())wifiMessage(scanning?t("Buscando redes…","Searching for networks…"):t("No hay redes disponibles","No networks available"));
        }catch(SecurityException e){wifiMessage(t("Abre los ajustes para buscar redes.","Open settings to search for networks."));}
        if(!message.isEmpty())wifiMessage(message);
        LinearLayout actions=ui.column();actions.setPadding(ui.dp(24),0,ui.dp(24),0);wifiRows.addView(actions);
        ui.row(actions,t("Añadir red","Add network"),null,"plus",()->go("wifi-add"));
        LinearLayout refresh=ui.row(actions,scanning?t("Buscando redes…","Searching for networks…"):t("Buscar de nuevo","Scan again"),null,scanning?null:"rotate-ccw",scanning?null:this::scan);
        if(scanning){LinearLayout.LayoutParams icon=new LinearLayout.LayoutParams(ui.dp(26),ui.dp(26));icon.setMarginEnd(ui.dp(18));refresh.addView(ui.new WifiProgress(),0,icon);}
    }
    private void wifiMessage(String message){TextView text=ui.text(message,14,LambdaUi.MUTED);text.setPadding(ui.dp(24),ui.dp(12),ui.dp(24),ui.dp(12));wifiRows.addView(text);}
    private void wifiPicker(){
        // The exported setup-aware picker supports hidden and enterprise networks;
        // the general Settings activity can return immediately before provisioning.
        launch(new Intent("android.net.wifi.PICK_WIFI_NETWORK").setPackage("com.android.settings"),"wifi-settings");
    }
    private void addNetwork(){
        shell(t("Añadir red","Add network"),"wifi");
        EditText name=ui.field(body,t("Nombre de la red (SSID)","Network name (SSID)"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        String[] security={"WPA2-Personal","WPA3-Personal",t("Sin contraseña","No password"),"Enhanced Open (OWE)"};int[] choice={0};
        TextView selected=ui.text(security[0],17,LambdaUi.FG);LinearLayout securityRow=ui.row(body,t("Seguridad","Security"),null,"lock-keyhole",null);securityRow.addView(selected);
        ui.gap(body,20);EditText pass=ui.field(body,t("Contraseña","Password"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);pass.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        CheckBox hidden=new CheckBox(this);hidden.setText(t("Red oculta","Hidden network"));hidden.setTextColor(ui.color(LambdaUi.MUTED));hidden.setTypeface(ui.regular);hidden.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.color(LambdaUi.MUTED)));body.addView(hidden);ui.gap(body,16);
        LambdaNetworkOptions options=new LambdaNetworkOptions(ui,body);TextView error=ui.text("",14,LambdaUi.FG);error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(error);
        Button connectButton=primary(t("Conectar","Connect"),()->{
            try{String networkName=name.getText().toString();if(networkName.isEmpty()||networkName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32)throw new IllegalArgumentException(t("El nombre debe ocupar entre 1 y 32 bytes.","The network name must be between 1 and 32 bytes."));
                String secret=pass.getText().toString();if(choice[0]<2&&!LambdaWifiInput.validPassword(secret,choice[0]==1))throw new IllegalArgumentException(t("Revisa la contraseña.","Check the password."));
                IpConfiguration ip=options.build();selectedSsid=networkName;selectedNetwork=new ScanResult();selectedNetwork.SSID=networkName;selectedNetwork.capabilities=choice[0]==0?"PSK":choice[0]==1?"SAE":choice[0]==3?"OWE":"";hiddenNetwork=hidden.isChecked();pass.getText().clear();connect(secret,ip);
            }catch(IllegalArgumentException invalid){error.setText(invalid.getMessage());}
        });
        securityRow.setFocusable(true);securityRow.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(t("Seguridad","Security")).setSingleChoiceItems(security,choice[0],(dialog,which)->{choice[0]=which;selected.setText(security[which]);pass.setEnabled(which<2);pass.setAlpha(which<2?1:.35f);dialog.dismiss();}).setNegativeButton(t("Cancelar","Cancel"),null).show());
        secondary(t("Red empresarial u otra seguridad","Enterprise or other security"),this::wifiPicker);
    }
    private void wifiPassword(){if(selectedNetwork==null){go("wifi");return;}shell(selectedSsid,"wifi");
        EditText pass=ui.field(body,t("Contraseña","Password"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        CheckBox show=new CheckBox(this);show.setText(t("Mostrar contraseña","Show password"));show.setTextColor(ui.color(LambdaUi.MUTED));show.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.color(LambdaUi.MUTED)));show.setOnCheckedChangeListener((b,on)->{pass.setTransformationMethod(on?null:android.text.method.PasswordTransformationMethod.getInstance());pass.setSelection(pass.length());});body.addView(show);
        show.setTypeface(ui.regular);
        LambdaNetworkOptions options=new LambdaNetworkOptions(ui,body);TextView error=ui.text("",14,LambdaUi.FG);error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(error);
        Button b=primary(t("Conectar","Connect"),()->{try{IpConfiguration ip=options.build();String value=pass.getText().toString();pass.getText().clear();connect(value,ip);}catch(IllegalArgumentException invalid){error.setText(invalid.getMessage());}});b.setEnabled(false);b.setAlpha(.45f);
        pass.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int before,int count){boolean valid=selectedNetwork.capabilities.contains("OWE")||LambdaWifiInput.validPassword(s.toString(),selectedNetwork.capabilities.contains("SAE"));b.setEnabled(valid);b.setAlpha(valid?1:.45f);}});
        secondary(t("Cancelar","Cancel"),()->go("wifi"));
    }
    private void connectingPage(){shell(null,null);LinearLayout c=ui.column();c.setGravity(Gravity.CENTER);c.addView(ui.new WifiProgress(),new LinearLayout.LayoutParams(ui.dp(116),ui.dp(116)));ui.gap(c,28);
        TextView title=ui.text(t("Conectando…","Connecting…"),32,LambdaUi.FG);title.setGravity(Gravity.CENTER);c.addView(title);ui.gap(c,14);TextView name=ui.text(selectedSsid,17,LambdaUi.MUTED);name.setGravity(Gravity.CENTER);c.addView(name);body.addView(c,new LinearLayout.LayoutParams(-1,0,1));
        secondary(t("Cancelar","Cancel"),()->{connecting=false;connectionGeneration++;handler.removeCallbacks(connectionTimeout);go("wifi");});}
    private void connect(String password){connect(password,new IpConfiguration.Builder().build());}
    private void connect(String password,IpConfiguration ip){if(wifi==null||selectedNetwork==null)return;WifiConfiguration config=new WifiConfiguration();config.SSID=LambdaWifiInput.quote(selectedSsid);config.hiddenSSID=hiddenNetwork;String caps=selectedNetwork.capabilities;config.setIpConfiguration(ip);
        if(caps.contains("SAE")){config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);config.preSharedKey=LambdaWifiInput.quote(password);}else if(caps.contains("PSK")){config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);config.preSharedKey=password.matches("[0-9A-Fa-f]{64}")?password:LambdaWifiInput.quote(password);}else if(caps.contains("OWE")){config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OWE);}else config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN);
        connecting=true;final int attempt=++connectionGeneration;go("wifi-connecting");handler.removeCallbacks(connectionTimeout);handler.postDelayed(connectionTimeout,30000);
        try{wifi.connect(config,new WifiManager.ActionListener(){public void onSuccess(){}public void onFailure(int reason){handler.post(()->{if(!connecting||attempt!=connectionGeneration)return;connecting=false;handler.removeCallbacks(connectionTimeout);message=t("No se ha podido iniciar la conexión.","Couldn't start the connection.");go("wifi-error");});}});}catch(RuntimeException e){connecting=false;handler.removeCallbacks(connectionTimeout);message=t("No se ha podido iniciar la conexión.","Couldn't start the connection.");go("wifi-error");}
    }
    private void dateTime(){shell(t("Fecha y hora","Date and time"),null);boolean automatic=Settings.Global.getInt(getContentResolver(),Settings.Global.AUTO_TIME,1)==1;
        ui.gap(body,18);timePreview=ui.text("",58,LambdaUi.FG);timePreview.setGravity(Gravity.CENTER);body.addView(timePreview);ui.gap(body,10);dayPreview=ui.text("",16,LambdaUi.MUTED);dayPreview.setGravity(Gravity.CENTER);body.addView(dayPreview);ui.gap(body,36);
        LinearLayout rows=ui.column();
        ui.binary(body,t("Automática","Automatic"),t("Manual","Manual"),automatic,on->{Settings.Global.putInt(getContentResolver(),Settings.Global.AUTO_TIME,on?1:0);renderDateRows(rows);});
        ui.row(body,t("Zona horaria","Time zone"),TimeZone.getDefault().getID(),null,()->go("timezone"));
        body.addView(rows);renderDateRows(rows);primary(next(),()->continueTo("restore"));}
    private void renderDateRows(LinearLayout rows){rows.removeAllViews();boolean automatic=Settings.Global.getInt(getContentResolver(),Settings.Global.AUTO_TIME,1)==1;
        timePreview.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));dayPreview.setText(DateFormat.getDateInstance(DateFormat.FULL).format(new Date()));
        LinearLayout date=ui.row(rows,t("Fecha","Date"),DateFormat.getDateInstance().format(new Date()),null,automatic?null:()->{Calendar c=Calendar.getInstance();new DatePickerDialog(this,(v,y,m,d)->{Calendar value=Calendar.getInstance();value.set(y,m,d);getSystemService(AlarmManager.class).setTime(value.getTimeInMillis());renderDateRows(rows);},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();});
        LinearLayout time=ui.row(rows,t("Hora","Time"),DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()),null,automatic?null:()->{Calendar c=Calendar.getInstance();new TimePickerDialog(this,(v,h,m)->{Calendar value=Calendar.getInstance();value.set(Calendar.HOUR_OF_DAY,h);value.set(Calendar.MINUTE,m);getSystemService(AlarmManager.class).setTime(value.getTimeInMillis());renderDateRows(rows);},c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE),true).show();});
        date.setEnabled(!automatic);time.setEnabled(!automatic);date.setAlpha(automatic?.4f:1);time.setAlpha(automatic?.4f:1);
        if(automatic){ui.gap(rows,16);rows.addView(ui.text(t("Sincronizadas con la red. Selecciona «Manual» para editarlas.","Synced with the network. Select “Manual” to edit."),14,LambdaUi.MUTED));}
    }
    private void timeZone(){shell(t("Zona horaria","Time zone"),"globe");EditText search=ui.field(body,t("Buscar","Search"),InputType.TYPE_CLASS_TEXT);LinearLayout list=ui.column();body.addView(list);Runnable update=()->{list.removeAllViews();String q=search.getText().toString().toLowerCase(Locale.ROOT);for(String id:TimeZone.getAvailableIDs()){if(!id.contains("/")||!id.toLowerCase(Locale.ROOT).contains(q))continue;ui.row(list,id,null,null,()->{Settings.Global.putInt(getContentResolver(),Settings.Global.AUTO_TIME_ZONE,0);getSystemService(AlarmManager.class).setTimeZone(id);go("datetime");});}};search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){update.run();}});update.run();}
    private void restore(){shell(t("Empieza de nuevo o continúa donde lo dejaste","Start fresh or pick up where you left off"),null);
        ui.gap(body,40);ui.actionCard(body,t("Configurar como nuevo","Set up as new"),t("Un comienzo desde cero","A fresh start"),"plus",()->go("location"));
        ui.actionCard(body,t("Restaurar copia","Restore backup"),"Seedvault","archive-restore",()->launch(new Intent(SetupWizardApp.ACTION_RESTORE_FROM_BACKUP),"restore"));}
    private void location(){shell(t("Tu ubicación","Your location"),"map-pin");note(t("Elige si las aplicaciones pueden solicitar tu ubicación. Podrás cambiarlo más tarde.","Choose whether apps can request your location. You can change this later."));ui.gap(body,48);ui.binary(body,t("Permitir","Allow"),t("No permitir","Don't allow"),prefs.getBoolean("location",false),on->prefs.edit().putBoolean("location",on).apply());note(t("Cada aplicación seguirá pidiéndote permiso para acceder.","Each app will still ask for permission to access it."));primary(next(),()->continueTo("lock"));}
    private void lock(){shell(t("Protege tu teléfono","Protect your phone"),"lock-keyhole");boolean secure=getSystemService(KeyguardManager.class).isDeviceSecure();note(secure?t("Ya tienes un bloqueo configurado. Puedes mantenerlo o cambiarlo.","A screen lock is already set. You can keep it or change it."):t("Elige cómo desbloquear el dispositivo.","Choose how to unlock your device."));
        String[] labels={"PIN",t("Patrón","Pattern"),t("Contraseña","Password")};
        int[] quality={android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC,android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_SOMETHING,android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC};
        for(int index=0;index<labels.length;index++){final int q=quality[index];ui.row(body,labels[index],null,null,()->launch(new Intent("com.android.settings.SETUP_LOCK_SCREEN").putExtra("lockscreen.password_type",q),"lock"));}
        ui.row(body,t("Sin modo de desbloqueo","No screen lock"),secure?t("Quitar el bloqueo y las huellas guardadas","Remove your screen lock and saved fingerprints"):t("Continuar sin PIN, patrón ni contraseña","Continue without a PIN, pattern or password"),null,()->go("skip-lock"));
        if(secure)primary(t("Mantener mi bloqueo","Keep my screen lock"),()->continueTo("fingerprint"));}
    private void withoutLock(){
        boolean secure=getSystemService(KeyguardManager.class).isDeviceSecure();
        centered(secure?t("¿Quitar el bloqueo?","Remove your screen lock?"):t("¿Continuar sin bloqueo?","Continue without a screen lock?"),
                secure?t("Se eliminarán el bloqueo y las huellas guardadas. Cualquier persona con el teléfono podrá acceder a tus datos. Confirma tu PIN, patrón o contraseña actual para continuar.","Your screen lock and saved fingerprints will be removed. Anyone with your phone will be able to access your data. Confirm your current PIN, pattern or password to continue."):
                t("Cualquier persona con el teléfono podrá acceder a tus datos. No se configurará un PIN, patrón ni contraseña.","Anyone with your phone will be able to access your data. No PIN, pattern or password will be set."),"shield-alert");
        primary(secure?t("Quitar bloqueo","Remove screen lock"):t("Continuar sin bloqueo","Continue without a screen lock"),()->launch(new Intent("com.android.settings.SETUP_LOCK_SCREEN")
                .setPackage("com.android.settings")
                .putExtra("lockscreen.password_type",android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED)
                .putExtra("org.lambdaproject.setup.DISABLE_LOCK_SCREEN",true),"remove-lock"));
        secondary(t("Volver","Go back"),this::back);
    }
    private void fingerprint(){boolean secure=getSystemService(KeyguardManager.class).isDeviceSecure();boolean supported=getPackageManager().hasSystemFeature("android.hardware.fingerprint");
        centered(supported?t("Tu huella, tu acceso","Your fingerprint, your access"):t("Huella no disponible","Fingerprint unavailable"),supported?t("Desbloquea el teléfono con el sensor de huella.","Unlock your phone using its fingerprint sensor."):t("Puedes continuar con tu bloqueo de pantalla.","You can continue with your screen lock."),"fingerprint-pattern");
        if(supported&&secure)primary(t("Añadir huella","Add fingerprint"),()->launch(new Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,BiometricManager.Authenticators.BIOMETRIC_STRONG),"fingerprint"));secondary(t("Ahora no","Not now"),()->go("theme"));}
    private String appearanceLabel(){String value=prefs.getString("theme","dark");return value.equals("light")?t("Claro","Light"):value.equals("auto")?t("Automático","Automatic"):t("Oscuro","Dark");}
    private void appearance(){
        shell(t("Elige tu aspecto","Choose your appearance"),null);
        LinearLayout group=ui.column();group.setGravity(Gravity.CENTER);body.addView(group,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout previews=ui.line();previews.setGravity(Gravity.CENTER);group.addView(previews,new LinearLayout.LayoutParams(-1,-2));
        String[] values={"dark","light","auto"},labels={t("Oscuro","Dark"),t("Claro","Light"),t("Automático","Automatic")};
        ArrayList<View> cards=new ArrayList<>(),images=new ArrayList<>(),dots=new ArrayList<>();
        String selected=prefs.getString("theme","dark");
        for(int i=0;i<values.length;i++){
            final int choice=i;String value=values[i];LinearLayout card=ui.column();card.setGravity(Gravity.CENTER);card.setPadding(ui.dp(4),0,ui.dp(4),0);
            previews.addView(card,new LinearLayout.LayoutParams(0,-2,1));
            View image=ui.themePreview(value);image.setSelected(value.equals(selected));card.addView(image,new LinearLayout.LayoutParams(-1,ui.dp(230)));ui.gap(card,14);
            TextView label=ui.text(labels[i],15,LambdaUi.FG);label.setGravity(Gravity.CENTER);card.addView(label,new LinearLayout.LayoutParams(-1,-2));ui.gap(card,8);
            View dot=ui.selectionDot();dot.setSelected(value.equals(selected));card.addView(dot,new LinearLayout.LayoutParams(-1,ui.dp(36)));
            card.setContentDescription(labels[i]);card.setFocusable(true);card.setSelected(value.equals(selected));ui.checkable(card);cards.add(card);images.add(image);dots.add(dot);
            card.setOnClickListener(v->{
                if(card.isSelected())return;LambdaUi.haptic(v);prefs.edit().putString("theme",value).apply();
                // Repaint the existing controls in place: no root replacement,
                // entrance fade, Activity restart, focus loss or scroll reset.
                repaintAppearance();getSystemService(UiModeManager.class).setNightMode(nightMode(value));
                for(int n=0;n<cards.size();n++){cards.get(n).setSelected(n==choice);images.get(n).setSelected(n==choice);dots.get(n).setSelected(n==choice);}
            });
        }
        ui.gap(group,26);TextView hint=ui.text(t("Puedes cambiarlo después desde Ajustes.","You can change this later in Settings."),15,LambdaUi.MUTED);hint.setGravity(Gravity.CENTER);group.addView(hint);
        primary(next(),()->continueTo("layout"));
    }
    private void choices(String key,String title,String[] labels,String[] values,String target){shell(title,null);String selected=prefs.getString(key,values[0]);LinearLayout previews=ui.line();previews.setGravity(Gravity.TOP);body.addView(previews);
        ArrayList<View> cards=new ArrayList<>(),images=new ArrayList<>();
        for(int i=0;i<labels.length;i++){final int choice=i;String value=values[i];LinearLayout card=ui.column();card.setGravity(Gravity.CENTER);card.setPadding(ui.dp(6),0,ui.dp(6),0);previews.addView(card,new LinearLayout.LayoutParams(0,-2,1));View view=ui.preview(value,selected.equals(value));card.addView(view,new LinearLayout.LayoutParams(-1,ui.dp(292)));ui.gap(card,16);TextView label=ui.text(labels[i],16,LambdaUi.FG);label.setGravity(Gravity.CENTER);card.addView(label);card.setContentDescription(labels[i]);card.setFocusable(true);card.setSelected(selected.equals(value));ui.checkable(card);cards.add(card);images.add(view);card.setOnClickListener(v->{if(card.isSelected())return;LambdaUi.haptic(v);prefs.edit().putString(key,value).apply();for(int n=0;n<cards.size();n++){cards.get(n).setSelected(n==choice);images.get(n).setSelected(n==choice);}});}
        ui.gap(body,30);note(t("Puedes cambiarlo después desde Ajustes.","You can change this later in Settings."));primary(next(),()->continueTo(target));}
    private void toggle(String label,boolean value,java.util.function.Consumer<Boolean> change){LinearLayout row=ui.line();row.setMinimumHeight(ui.dp(64));TextView text=ui.text(label,18,LambdaUi.FG);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));Switch toggle=new Switch(this);toggle.setContentDescription(label);toggle.setChecked(value);toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(ui.color(LambdaUi.FG)));toggle.setTrackTintList(android.content.res.ColorStateList.valueOf(ui.color(LambdaUi.LINE)));toggle.setOnCheckedChangeListener((b,on)->{LambdaUi.haptic(b);change.accept(on);});row.addView(toggle);body.addView(row);ui.divider(body);}
    private void haptics(){shell(t("Siente cada acción","Feel each action"),null);note(t("Una respuesta breve al tocar y confirmar.","A subtle response when you select or confirm."));
        LinearLayout demo=ui.column();demo.setGravity(Gravity.CENTER);body.addView(demo,new LinearLayout.LayoutParams(-1,0,1));
        ImageButton test=ui.iconButton("vibrate",t("Probar vibración","Try haptics"),()->{});test.setPadding(ui.dp(34),ui.dp(34),ui.dp(34),ui.dp(34));test.setBackground(ui.shape(ui.color(LambdaUi.SURFACE),60));demo.addView(test,new LinearLayout.LayoutParams(ui.dp(120),ui.dp(120)));ui.gap(demo,22);TextView hint=ui.text(t("Toca para probar","Tap to try"),16,LambdaUi.MUTED);hint.setGravity(Gravity.CENTER);demo.addView(hint);ui.gap(demo,42);
        boolean enabled=prefs.getBoolean("haptics",true);test.setEnabled(enabled);test.setAlpha(enabled?1:.4f);test.setOnClickListener(v->{if(prefs.getBoolean("haptics",true))v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);});
        ui.binary(body,t("Activada","On"),t("Desactivada","Off"),enabled,on->{prefs.edit().putBoolean("haptics",on).apply();test.setEnabled(on);test.setAlpha(on?1:.4f);});primary(next(),()->continueTo("summary"));}
    private void summary(){shell(t("Comprueba que todo esté bien","Check everything looks right"),null);ui.row(body,t("Idioma","Language"),Locale.getDefault().getDisplayName(),"languages",()->editSection("language"));ui.row(body,"Wi-Fi",wifiNetwork==null?t("Sin configurar","Not configured"):ssid(),"wifi",()->editSection("wifi"));ui.row(body,t("Bloqueo de pantalla","Screen lock"),getSystemService(KeyguardManager.class).isDeviceSecure()?t("Configurado","Configured"):t("Sin bloqueo","Not set"),"lock-keyhole",()->editSection("lock"));ui.row(body,t("Aspecto","Appearance"),appearanceLabel(),null,()->editSection("theme"));ui.row(body,"Lambda Operator",prefs.getString("layout","icons").equals("text")?t("Texto","Text"):t("Iconos","Icons"),null,()->editSection("layout"));ui.row(body,t("Navegación","Navigation"),prefs.getString("navigation","gestures").equals("buttons")?t("Botones","Buttons"):t("Gestos","Gestures"),null,()->editSection("navigation"));primary(t("Aplicar","Apply"),this::apply);}
    private void apply(){
        if(completing)return;if(!canComplete()){blocked();return;}
        completing=true;applyingStarted=SystemClock.uptimeMillis();page="applying";prefs.edit().putString("page",page).commit();render();
        new Thread(()->{
            try{
                var resolver=getContentResolver();
                getSystemService(android.location.LocationManager.class).setLocationEnabledForUser(prefs.getBoolean("location",false),UserHandle.of(UserHandle.myUserId()));
                Settings.System.putInt(resolver,Settings.System.HAPTIC_FEEDBACK_ENABLED,prefs.getBoolean("haptics",true)?1:0);
                Settings.Secure.putString(resolver,"lambda_layout",prefs.getString("layout","icons"));
                String theme=prefs.getString("theme","dark");
                Settings.Secure.putString(resolver,"lambda_theme",theme);
                hydrateSettingsBundle();
                LambdaInitialWallpaper.apply(this,prefs,theme);
                getSystemService(UiModeManager.class).setNightMode(nightMode(theme));
                handler.post(this::appliedSuccessfully);
            }catch(Exception e){handler.post(()->{completing=false;message=t("No se han podido aplicar todos los ajustes. Puedes revisarlos y volver a intentarlo.","Some settings could not be applied. Review them and try again.");go("error");});}
        },"LambdaSetupApply").start();
    }
    private void applyingPage(){
        shell(null,null);LinearLayout hero=ui.column();hero.setGravity(Gravity.CENTER);
        applyingMark=ui.new SetupProgress();hero.addView(applyingMark,new LinearLayout.LayoutParams(ui.dp(88),ui.dp(88)));ui.gap(hero,36);
        TextView heading=ui.text(t("Preparando tu\nGalaxy S10","Getting your\nGalaxy S10 ready"),30,LambdaUi.FG);heading.setGravity(Gravity.CENTER);heading.setAccessibilityHeading(true);hero.addView(heading,new LinearLayout.LayoutParams(-1,-2));ui.gap(hero,20);
        applyingStatus=ui.text(t("Aplicando tus preferencias…","Applying your preferences…"),16,LambdaUi.MUTED);applyingStatus.setGravity(Gravity.CENTER);applyingStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);hero.addView(applyingStatus,new LinearLayout.LayoutParams(-1,-2));body.addView(hero,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void appliedSuccessfully(){
        if(isFinishing()||isDestroyed()||!page.equals("applying"))return;
        boolean motion=android.animation.ValueAnimator.areAnimatorsEnabled();
        // Brief visual pacing, never a fake workload or percentage. Success is
        // shown only after every requested setting has actually been applied.
        handler.postDelayed(()->{
            if(isFinishing()||isDestroyed()||!page.equals("applying"))return;
            applyingStatus.setText(t("Tu configuración está lista","Your setup is ready"));applyingMark.complete();
            handler.postDelayed(()->{
                if(isFinishing()||isDestroyed()||!page.equals("applying"))return;
                if(!motion){completing=false;go("finished");return;}
                body.animate().alpha(0).setDuration(160).withEndAction(()->{completing=false;go("finished");}).start();
            },motion?600:0);
        },motion?Math.max(0,1200-(SystemClock.uptimeMillis()-applyingStarted)):0);
    }
    private void hydrateSettingsBundle(){
        SetupWizardApp.getSettingsBundle().putBoolean(SetupWizardApp.KEY_SEND_METRICS,false);
        SetupWizardApp.getSettingsBundle().putBoolean(SetupWizardApp.ENABLE_RECOVERY_UPDATE,false);
        SetupWizardApp.getSettingsBundle().putString(SetupWizardApp.NAVIGATION_OPTION_KEY,prefs.getString("navigation","gestures").equals("buttons")?"com.android.internal.systemui.navbar.threebutton":"com.android.internal.systemui.navbar.gestural");
    }
    private void finished(){shell(null,null);LinearLayout hero=ui.column();hero.setGravity(Gravity.CENTER);View lamb=ui.mascot(true);hero.addView(lamb,new LinearLayout.LayoutParams(ui.dp(180),ui.dp(180)));ui.gap(hero,26);TextView h=ui.text(t("Todo listo","All set"),44,LambdaUi.FG);h.setGravity(Gravity.CENTER);h.setAccessibilityHeading(true);hero.addView(h,new LinearLayout.LayoutParams(-1,-2));ui.gap(hero,18);TextView text=ui.text(t("Bienvenido a LambdaOS.","Welcome to LambdaOS."),20,LambdaUi.MUTED);text.setGravity(Gravity.CENTER);hero.addView(text,new LinearLayout.LayoutParams(-1,-2));body.addView(hero,new LinearLayout.LayoutParams(-1,0,1));primary(t("Empezar","Start"),()->{if(!canComplete()){blocked();return;}hydrateSettingsBundle();SetupWizardUtils.finishSetupWizard(this);});ui.finishEntrance(lamb,0);ui.finishEntrance(h,100);ui.finishEntrance(text,180);ui.finishEntrance(footer,260);}
    private void blocked(){message=t("No se puede completar la configuración en este estado. No se ha modificado la protección del dispositivo.","Setup can't be completed in this state. Device protection has not been changed.");page="error";render();}
    private void emergencyPage(){
        // Exactly one dialer. Style the real TeleService activity, not a second
        // imitation keypad. Do not place calls or bypass emergency-number checks.
        TelecomManager telecom=getSystemService(TelecomManager.class);
        if(telecom!=null)launch(telecom.createLaunchEmergencyDialerIntent(null),"emergency");
    }
    private void launch(Intent intent,String kind){
        if(intent==null||intent.resolveActivity(getPackageManager())==null){Toast.makeText(this,t("Esta función no está disponible en esta versión.","This feature is unavailable on this build."),Toast.LENGTH_LONG).show();return;}
        pending=kind;prefs.edit().putString("pending",kind).apply();
        intent.putExtra(WizardManagerHelper.EXTRA_IS_FIRST_RUN,true).putExtra(WizardManagerHelper.EXTRA_IS_SETUP_FLOW,true).putExtra(WizardManagerHelper.EXTRA_THEME,"glif_v4");
        try{external.launch(intent);}catch(ActivityNotFoundException|SecurityException e){pending="";prefs.edit().remove("pending").apply();Toast.makeText(this,t("No se ha podido abrir esta función.","Couldn't open this feature."),Toast.LENGTH_LONG).show();}
    }
}
