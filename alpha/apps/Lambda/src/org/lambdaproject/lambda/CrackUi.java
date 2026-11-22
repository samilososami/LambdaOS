package org.lambdaproject.lambda;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.*;
import android.view.Gravity;
import android.widget.*;
import org.json.*;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;

/** Dedicated offline workflow. Progress is completed batches, never a fake timer. */
final class CrackUi {
    final MainActivity a;final Handler main=new Handler(Looper.getMainLooper());final ExecutorService io=Executors.newSingleThreadExecutor();
    String captureId,id,dictionary="probable-long",engine="auto";boolean visible,resumed=true,dead,pending,active;int generation;
    String boundJob;JSONArray dictionaries=new JSONArray();TextView phase,rate,stats,start,dictionaryButton,engineButton,targetText;LinearLayout resultBox;ProgressBar bar;
    final Runnable poll=()->refresh();
    CrackUi(MainActivity a){this.a=a;id=a.getPreferences(0).getString("crack_job",null);captureId=a.getPreferences(0).getString("crack_capture",null);dictionary=a.getPreferences(0).getString("crack_dictionary","probable-long");}
    String t(String es,String en){return a.tr(es,en);}
    JSONObject p(String k,Object v){try{return new JSONObject().put(k,v);}catch(Exception e){throw new IllegalStateException(e);}}
    interface Reply{void done(JSONObject r)throws Exception;}
    void call(String method,JSONObject arg,Reply cb){int epoch=generation;io.execute(()->{JSONObject r;try{Bundle b=a.getContentResolver().call(CoreProvider.URI,method,arg.toString(),null);r=new JSONObject(b.getString("json"));}catch(Exception e){r=CoreEngine.error(e);}JSONObject response=r;main.post(()->{pending=false;if(dead||epoch!=generation)return;try{cb.done(response);}catch(Exception e){a.notice(e.getMessage());}});});}
    void beforeRender(){visible=false;generation++;main.removeCallbacks(poll);}
    void pause(){resumed=false;main.removeCallbacks(poll);}
    void resume(){resumed=true;if(visible)refresh();}
    void destroy(){dead=true;main.removeCallbacksAndMessages(null);io.shutdownNow();}
    boolean back(){if(!visible)return false;a.navigate("category");return true;}
    void openCapture(String source){captureId=source;id=null;boundJob=null;active=false;dictionary=a.getPreferences(0).getString("crack_dictionary","probable-long");engine="auto";a.getPreferences(0).edit().putString("crack_capture",source).remove("crack_job").apply();a.navigate("cracking");}
    void openJob(String job){id=job;boundJob=null;a.getPreferences(0).edit().putString("crack_job",job).apply();a.navigate("cracking");}
    TextView button(String label,Runnable action){TextView b=a.text(label,16,true,a.foreground);b.setGravity(Gravity.CENTER);b.setMinHeight(a.dp(48));b.setPadding(a.dp(12),a.dp(14),a.dp(12),a.dp(14));a.card(b,a.surface,16);a.touch(b,label,action);a.content.addView(b,new LinearLayout.LayoutParams(-1,-2));a.space(a.content,8);return b;}
    void render(){visible=true;a.heading("Cracking",t("Comprobación local por diccionario","Local dictionary checking"));
        LinearLayout panel=a.column();panel.setPadding(a.dp(22),a.dp(18),a.dp(22),a.dp(18));a.card(panel,a.surface,22);
        phase=a.text(t("Listo","Ready"),15,true,a.secondary);panel.addView(phase);a.space(panel,12);
        targetText=a.text("",12,false,a.secondary);panel.addView(targetText);a.space(panel,4);
        rate=a.text("—",44,true,a.foreground);panel.addView(rate);panel.addView(a.text(t("claves / segundo · tiempo total","keys / second · end-to-end"),12,false,a.secondary));a.space(panel,16);
        bar=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(1000);bar.setProgressTintList(android.content.res.ColorStateList.valueOf(a.foreground));bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(a.divider));panel.addView(bar,new LinearLayout.LayoutParams(-1,a.dp(4)));a.space(panel,12);
        stats=a.text(t("La captura se conserva al detener.","Stopping preserves the capture."),14,false,a.secondary);stats.setLineSpacing(a.dp(5),1);panel.addView(stats);a.content.addView(panel);a.space(a.content,16);
        dictionaryButton=a.text(t("Diccionario: comprobando…","Dictionary: checking…"),15,true,a.foreground);dictionaryButton.setMinHeight(a.dp(48));dictionaryButton.setPadding(a.dp(18),a.dp(15),a.dp(18),a.dp(15));a.card(dictionaryButton,a.surface,16);a.touch(dictionaryButton,t("Elegir diccionario","Choose dictionary"),this::selectDictionary);a.content.addView(dictionaryButton);a.space(a.content,8);
        button(t("Importar diccionario .txt","Import .txt dictionary"),()->{if(active){a.notice(t("Detén la tarea antes de cambiar el diccionario.","Stop the task before changing dictionaries."));return;}a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),85);});
        engineButton=button(t("Motor: ","Engine: ")+engineLabel(),()->{if(active)return;new AlertDialog.Builder(a).setTitle(t("Motor de cálculo","Compute engine")).setItems(new String[]{t("Automático · CPU / híbrido","Automatic · CPU / hybrid"),"CPU · Aircrack",t("Híbrido · CPU + GPU","Hybrid · CPU + GPU")},(d,n)->{engine=new String[]{"auto","cpu","hybrid"}[n];a.render();}).show();});
        start=a.text(t("Iniciar cracking","Start cracking"),17,true,a.background);start.setGravity(Gravity.CENTER);start.setMinHeight(a.dp(48));start.setPadding(a.dp(12),a.dp(14),a.dp(12),a.dp(14));a.card(start,a.foreground,16);a.touch(start,t("Iniciar o detener","Start or stop"),()->{if(pending)return;if(active){pending=true;call("cancel",p("id",id),r->refresh());}else confirm();});a.content.addView(start,new LinearLayout.LayoutParams(-1,-2));a.space(a.content,20);
        resultBox=a.column();a.content.addView(resultBox);
        a.content.addView(a.text(t("Automático: CPU para listas cortas; CPU + GPU a partir de 24.000 entradas. Aircrack verifica el handshake. La velocidad incluye preparación y validación.","Automatic: CPU for short lists; CPU + GPU from 24,000 entries. Aircrack verifies the handshake. Speed includes preparation and validation."),13,false,a.secondary));a.space(a.content,12);
        a.content.addView(a.text(t("Importación: ASCII, 8–15 caracteres, hasta 64 MiB. Las líneas no válidas se excluyen. Sesiones de hasta 1 h, con protección térmica. No se transmite tráfico Wi-Fi.","Import: ASCII, 8–15 characters, up to 64 MiB. Invalid lines are excluded. Sessions up to 1 h with thermal protection. No Wi-Fi traffic is transmitted."),12,false,a.secondary));
        a.space(a.content,16);TextView attribution=a.text("Probable-Wordlists · berzerk0 · CC BY-SA 4.0\n"+t("Listas filtradas a 8–15 caracteres. Fuente y licencia ↗","Lists filtered to 8–15 characters. Source and license ↗"),12,false,a.secondary);a.touch(attribution,t("Fuente y licencia","Source and license"),()->a.startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/berzerk0/Probable-Wordlists"))));a.content.addView(attribution);
        call("crack_dictionaries",new JSONObject(),r->{if(r.optBoolean("ok")){dictionaries=r.getJSONArray("dictionaries");dictionaryLabel();}else a.notice(r.optString("detail"));});refresh();
    }
    String engineLabel(){return engine.equals("cpu")?"CPU":engine.equals("hybrid")?"CPU + GPU":t("automático","automatic");}
    void dictionaryLabel(){for(int i=0;i<dictionaries.length();i++){JSONObject d=dictionaries.optJSONObject(i);if(d.optString("id").equals(dictionary)){dictionaryButton.setText(d.optString("name"));return;}}dictionaryButton.setText(dictionary.equals("quick")?"Probable WPA · 4.800":t("Diccionario no disponible","Dictionary unavailable"));}
    void selectDictionary(){if(active)return;String[] names=new String[dictionaries.length()];for(int i=0;i<names.length;i++)names[i]=dictionaries.optJSONObject(i).optString("name");new AlertDialog.Builder(a).setTitle(t("Diccionario","Dictionary")).setItems(names,(d,n)->{dictionary=dictionaries.optJSONObject(n).optString("id");a.getPreferences(0).edit().putString("crack_dictionary",dictionary).apply();dictionaryLabel();}).show();}
    void confirm(){if(captureId==null){a.notice(t("Abre una captura completa desde Sesiones.","Open a complete capture from Sessions."));return;}
        call("get",p("id",captureId),r->{if(!r.optBoolean("ok")){a.notice(r.optString("detail"));return;}JSONObject job=r.optJSONObject("job"),s=job==null?null:job.optJSONObject("result"),target=s==null?null:s.optJSONObject("target");
            String name=target==null?"LambdaBenchmark":target.optString("ssid")+"\n"+target.optString("bssid");
            new AlertDialog.Builder(a).setTitle(t("Comprobar contraseña","Check password")).setMessage(name+t("\n\nConfirma que tienes autorización para comprobar esta captura. La tarea es local y no desconecta dispositivos.","\n\nConfirm you are authorized to check this capture. This is offline and does not disconnect devices."))
                .setNegativeButton(t("Cancelar","Cancel"),null).setPositiveButton(t("Iniciar","Start"),(d,w)->submit()).show();});
    }
    void submit(){if(pending)return;pending=true;try{JSONObject args=p("capture_id",captureId).put("dictionary_id",dictionary).put("authorized",true).put("engine",engine);
        call("submit",p("tool","crack.wpa").put("args",args).put("request_id",UUID.randomUUID().toString()),r->{if(!r.optBoolean("ok")){a.notice(r.optString("detail"));return;}id=r.getJSONObject("job").getString("id");a.getPreferences(0).edit().putString("crack_job",id).apply();refresh();});}catch(Exception e){pending=false;a.notice(e.getMessage());}}
    void refresh(){main.removeCallbacks(poll);if(!visible||!resumed||id==null)return;call("get",p("id",id),r->{if(!r.optBoolean("ok")){a.notice(r.optString("detail"));return;}JSONObject j=r.getJSONObject("job");captureId=j.getJSONObject("args").optString("capture_id",captureId);active=Arrays.asList("queued","running").contains(j.optString("state"));
        if(!id.equals(boundJob)){boundJob=id;dictionary=j.getJSONObject("args").optString("dictionary_id","quick");engine=j.getJSONObject("args").optString("engine","auto");dictionaryLabel();engineButton.setText(t("Motor: ","Engine: ")+engineLabel());}
        start.setText(active?t("Detener","Stop"):t("Iniciar otra comprobación","Start another check"));JSONObject data=active?j.optJSONObject("progress"):j.optJSONObject("result");if(data==null)data=new JSONObject();
        JSONObject target=data.optJSONObject("target");targetText.setText((target==null?"":target.optString("ssid")+" · ")+(data.optString("engine").equals("hybrid-pmk-aircrack")?"CPU + GPU":"CPU"));
        long tested=data.optLong("tested"),total=data.optLong("total");double speed=data.optDouble("keys_per_second");rate.setText(speed>0?String.format(Locale.getDefault(),"%,.0f",speed):"—");bar.setProgress(total>0?(int)Math.min(1000,tested*1000/total):0);
        double seconds=active?data.optDouble("seconds"):data.optLong("wall_ms")/1000.0;
        stats.setText(String.format(Locale.getDefault(),"%,d / %,d · %.1f %%\n%.1f s",tested,total,total>0?100.0*tested/total:0,seconds));
        if(data.optString("outcome").equals("found")){bar.setProgress(1000);stats.setText(t("Verificada","Verified")+String.format(Locale.getDefault()," · %.1f s\n",seconds)+t("Hasta ","Up to ")+data.optLong("tested_upper_bound")+t(" candidatos procesados"," candidates processed"));}
        JSONObject temp=data.optJSONObject("temperature_c");if(temp==null&&data.optJSONObject("after")!=null)temp=data.optJSONObject("after").optJSONObject("temperature_c");if(temp!=null&&temp.has("battery"))stats.append(String.format(Locale.getDefault(),"\n%.1f °C · ",temp.optDouble("battery"))+t("batería","battery"));
        phase.setText(active?j.optBoolean("cancel_requested")?t("Deteniendo…","Stopping…"):data.optString("phase").equals("crack_gpu_starting")?t("Preparando CPU + GPU…","Preparing CPU + GPU…"):t("Comprobando diccionario…","Checking dictionary…"):outcome(data.optString("outcome",j.optString("state"))));
        resultBox.removeAllViews();if(!active){if(data.optString("outcome").equals("found")){TextView secret=a.text(data.optString("password"),24,true,a.foreground);resultBox.addView(secret);a.space(resultBox,12);resultBox.addView(a.text(t("Contraseña verificada","Verified password"),13,false,a.secondary));if(data.optBoolean("cryptographic_validation")&&!data.optBoolean("synthetic"))a.verifiedWifi.attach(resultBox,id);}
            else{resultBox.addView(a.text(data.optString("outcome").equals("exhausted")?t("No está en este diccionario. No significa que sea irrecuperable.","Not in this dictionary. This does not mean it is unrecoverable."):stopMessage(data.optString("stop_reason",j.optString("error",j.optString("state")))),14,false,a.secondary));}a.space(resultBox,20);}
        if(active&&visible)main.postDelayed(poll,650);
    });}
    String outcome(String o){if(o.equals("found"))return t("Contraseña encontrada","Password found");if(o.equals("exhausted"))return t("Diccionario completado","Dictionary completed");if(o.equals("cancelled"))return t("Comprobación detenida","Check stopped");return t("Comprobación incompleta","Check incomplete");}
    String stopMessage(String reason){
        if(reason.contains("thermal_limit"))return t("Detenido por temperatura. Deja enfriar el teléfono antes de iniciar otra comprobación. La captura se conserva.","Stopped by the thermal limit. Let the phone cool before starting another check. The capture is preserved.");
        if(reason.contains("memory_limit"))return t("Memoria disponible insuficiente. La captura se conserva.","Insufficient available memory. The capture is preserved.");
        if(reason.contains("cancel"))return t("Tarea detenida. La captura y el resultado parcial se conservan; una nueva comprobación empieza desde el principio.","Task stopped. The capture and partial result are preserved; a new check starts from the beginning.");
        return t("No se ha completado la comprobación. Detalle: ","The check did not complete. Details: ")+reason;
    }
    boolean activityResult(int request,int code,Intent data){if(request!=85)return false;if(code!=android.app.Activity.RESULT_OK||data==null)return true;pending=true;io.execute(()->{JSONObject imported=null;String error=null;try(InputStream in=a.getContentResolver().openInputStream(data.getData())){if(in==null)throw new java.io.IOException("document_unavailable");imported=CrackStore.importText(a,in);}catch(Exception e){error=e.getMessage();}JSONObject ready=imported;String message=error;main.post(()->{pending=false;if(dead)return;if(ready!=null){dictionary=ready.optString("id");a.getPreferences(0).edit().putString("crack_dictionary",dictionary).apply();a.notice(ready.optLong("candidates")+t(" entradas · omitidas: "," entries · skipped: ")+ready.optInt("skipped"));if(visible)a.render();}else a.notice(message);});});return true;}
}
