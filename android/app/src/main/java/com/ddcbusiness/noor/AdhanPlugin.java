package com.ddcbusiness.noor;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * NoorAdhan — جسر الواجهة إلى الأذان الكامل والتطبيق مغلق (native-bridge.js).
 *
 * isAvailable() → {service, exact, playing, muteUntil, sounds[]}: service=true فقط إن كانت AdhanPlayerService معلَنة في البيان
 *   مع أذونات الخدمة الأمامية (مقترح adhan_manifest.diff) — وإلا يبقى الجسر على إشعار الإضافة بالنغمة القصيرة.
 * schedule({items:[{id,at,prayer,sound,title,body}], stream, labels:{stop,mute,chName,chDesc}, tz:{nbTzT,nbTzB,nbTzC}})
 *   → {scheduled}: القائمة الكاملة للأفق (الجسر يحسبها كما يحسب إشعارات الإضافة) — ما غاب عنها يُلغى.
 * cancelAll() · stop() (يوقف الخدمة والمعاينة) · preview({sound, stream}) · stopPreview() · getState() · muteToday() · unmute()
 */
@CapacitorPlugin(name = "NoorAdhan")
public class AdhanPlugin extends Plugin {
    private static MediaPlayer preview;

    @PluginMethod
    public void isAvailable(PluginCall call) {
        call.resolve(state(getContext()));
    }

    @PluginMethod
    public void getState(PluginCall call) {
        call.resolve(state(getContext()));
    }

    private static JSObject state(Context ctx) {
        JSObject r = new JSObject();
        r.put("plugin", true);
        r.put("service", AdhanScheduler.serviceDeclared(ctx));
        r.put("exact", AdhanScheduler.canExact(ctx));
        r.put("playing", AdhanPlayerService.isPlaying());
        r.put("prayer", AdhanPlayerService.playingPrayer());
        r.put("muteUntil", AdhanScheduler.muteUntil(ctx));
        r.put("pending", AdhanScheduler.pendingCount(ctx));
        r.put("lastKey", AdhanScheduler.lastKey(ctx));
        JSArray a = new JSArray();
        for (String s : AdhanLogic.SOUNDS) a.put(s);
        r.put("sounds", a);
        return r;
    }

    @PluginMethod
    public void schedule(PluginCall call) {
        try {
            JSONArray arr = call.getArray("items", new JSArray());
            List<AdhanLogic.Item> items = new ArrayList<AdhanLogic.Item>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                int id = o.optInt("id", -1);
                long at = o.optLong("at", 0L);
                if (id < AdhanLogic.ID_MIN || id >= AdhanLogic.ID_MAX || at <= 0) continue;
                items.add(new AdhanLogic.Item(id, at, o.optString("prayer", ""), o.optString("sound", ""), o.optString("title", ""), o.optString("body", "")));
            }
            JSObject lb = call.getObject("labels", new JSObject());
            JSObject tz = call.getObject("tz", new JSObject());
            int n = AdhanScheduler.schedule(getContext(), items, call.getString("stream", AdhanLogic.STREAM_ALARM),
                lb.getString("stop"), lb.getString("mute"), lb.getString("chName"), lb.getString("chDesc"),
                tz.getString("nbTzT"), tz.getString("nbTzB"), tz.getString("nbTzC"));
            JSObject r = new JSObject();
            r.put("scheduled", n);
            r.put("service", AdhanScheduler.serviceDeclared(getContext()));
            call.resolve(r);
        } catch (Throwable t) {
            call.reject("adhan-schedule: " + t.getMessage());
        }
    }

    @PluginMethod
    public void cancelAll(PluginCall call) {
        try { AdhanScheduler.cancelAll(getContext()); call.resolve(); } catch (Throwable t) { call.reject("adhan-cancel: " + t.getMessage()); }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopPreviewPlayer();
        try { AdhanPlayerService.stop(getContext()); } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void muteToday(PluginCall call) {
        AdhanScheduler.muteToday(getContext());
        try { AdhanPlayerService.stop(getContext()); } catch (Exception ignored) {}
        call.resolve(state(getContext()));
    }

    @PluginMethod
    public void unmute(PluginCall call) {
        AdhanScheduler.unmute(getContext());
        call.resolve(state(getContext()));
    }

    /** معاينة داخل التطبيق (بلا خدمة — تعمل قبل تطبيق مقترح البيان): الملف المضمّن على المجرى المختار */
    @PluginMethod
    public void preview(PluginCall call) {
        String sound = call.getString("sound", AdhanLogic.DEFAULT_SOUND);
        boolean alarm = AdhanLogic.STREAM_ALARM.equals(AdhanLogic.normStream(call.getString("stream", AdhanLogic.STREAM_ALARM)));
        if (!AdhanLogic.isBundled(sound)) { call.reject("unknown sound"); return; }
        stopPreviewPlayer();
        try {
            Context ctx = getContext();
            int resId = ctx.getResources().getIdentifier(sound, "raw", ctx.getPackageName());
            if (resId == 0) { call.reject("missing resource"); return; }
            MediaPlayer m = new MediaPlayer();
            m.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(alarm ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            AssetFileDescriptor fd = ctx.getResources().openRawResourceFd(resId);
            m.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            m.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { public void onCompletion(MediaPlayer p) { stopPreviewPlayer(); } });
            m.prepare();
            preview = m;
            m.start();
            JSObject r = new JSObject();
            r.put("duration", m.getDuration());
            call.resolve(r);
        } catch (Throwable t) {
            stopPreviewPlayer();
            call.reject("adhan-preview: " + t.getMessage());
        }
    }

    @PluginMethod
    public void stopPreview(PluginCall call) {
        stopPreviewPlayer();
        call.resolve();
    }

    private static synchronized void stopPreviewPlayer() {
        MediaPlayer m = preview;
        preview = null;
        if (m == null) return;
        try { if (m.isPlaying()) m.stop(); } catch (Exception ignored) {}
        try { m.release(); } catch (Exception ignored) {}
    }

    @Override
    protected void handleOnDestroy() { stopPreviewPlayer(); }
}
