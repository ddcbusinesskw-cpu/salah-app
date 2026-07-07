package com.ddcbusiness.noor;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "SilentSpeech")
public class SilentSpeechPlugin extends Plugin {

    private static final int[] MUTE_STREAMS = {
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_SYSTEM
    };

    private boolean muted = false;

    @PluginMethod
    public void muteBeep(PluginCall call) {
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am != null && !muted) {
                for (int stream : MUTE_STREAMS) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0);
                    } else {
                        am.setStreamMute(stream, true);
                    }
                }
                muted = true;
            }
            call.resolve(new JSObject().put("ok", true));
        } catch (Exception e) {
            call.resolve(new JSObject().put("ok", false));
        }
    }

    @PluginMethod
    public void unmuteBeep(PluginCall call) {
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am != null && muted) {
                for (int stream : MUTE_STREAMS) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
                    } else {
                        am.setStreamMute(stream, false);
                    }
                }
                muted = false;
            }
            call.resolve(new JSObject().put("ok", true));
        } catch (Exception e) {
            call.resolve(new JSObject().put("ok", false));
        }
    }

    @PluginMethod
    public void isMuted(PluginCall call) {
        call.resolve(new JSObject().put("muted", muted));
    }

    /* ── شبكة أمان: لا يبقى الهاتف مكتوماً إن قُتل التطبيق داخل نافذة الكتم ── */
    private void unmuteAllSafe() {
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am != null && muted) {
                for (int stream : MUTE_STREAMS) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
                    } else {
                        am.setStreamMute(stream, false);
                    }
                }
                muted = false;
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void handleOnPause() {
        if (muted) unmuteAllSafe();
        super.handleOnPause();
    }

    @Override
    protected void handleOnDestroy() {
        if (muted) unmuteAllSafe();
        super.handleOnDestroy();
    }
}
