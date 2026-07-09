package com.ddcbusiness.noor;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.util.Base64;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.File;
import java.io.FileInputStream;

@CapacitorPlugin(
    name = "AudioRecorder",
    permissions = {
        @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "microphone")
    }
)
public class AudioRecorderPlugin extends Plugin {

    private MediaRecorder recorder = null;
    private String outputPath = null;

    /* ── Permission bridge methods ── */

    @PluginMethod
    public void checkPermissions(PluginCall call) {
        JSObject result = new JSObject();
        result.put("microphone", permStateText());
        call.resolve(result);
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        if (sysPermGranted()) {
            JSObject result = new JSObject();
            result.put("microphone", "granted");
            call.resolve(result);
        } else {
            requestPermissionForAlias("microphone", call, "onReqPermResult");
        }
    }

    @PermissionCallback
    private void onReqPermResult(PluginCall call) {
        JSObject result = new JSObject();
        result.put("microphone", permStateText());
        call.resolve(result);
    }

    /* ── Recording ── */

    @PluginMethod
    public void start(PluginCall call) {
        if (!sysPermGranted()) {
            // Only request from OS if the system layer says it's not granted
            requestPermissionForAlias("microphone", call, "onStartPermResult");
            return;
        }
        doStart(call);
    }

    @PermissionCallback
    private void onStartPermResult(PluginCall call) {
        if (sysPermGranted()) {
            doStart(call);
        } else {
            call.reject("PERMISSION_DENIED: ContextCompat.checkSelfPermission returned DENIED after dialog");
        }
    }

    private void doStart(PluginCall call) {
        // Double-check with the OS — never trust only Capacitor's cached state
        if (!sysPermGranted()) {
            call.reject("PERMISSION_DENIED: ContextCompat.checkSelfPermission returned DENIED in doStart");
            return;
        }

        // Release any previous recorder
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }

        try {
            outputPath = getContext().getCacheDir().getAbsolutePath() + "/tasmee_rec.m4a";
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            // No setAudioSamplingRate — use device default; JS resamples to 16kHz
            recorder.setAudioEncodingBitRate(128000);
            recorder.setOutputFile(outputPath);
            recorder.prepare();
            recorder.start();
            call.resolve();
        } catch (Exception e) {
            if (recorder != null) {
                try { recorder.reset(); } catch (Exception ignored) {}
                try { recorder.release(); } catch (Exception ignored) {}
                recorder = null;
            }
            call.reject(buildExceptionDetail(e));
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (recorder == null) {
            call.reject("NOT_RECORDING");
            return;
        }
        try {
            recorder.stop();
            recorder.release();
            recorder = null;
            File file = new File(outputPath);
            int fileSize = (int) file.length();
            byte[] bytes = new byte[fileSize];
            FileInputStream fis = new FileInputStream(file);
            int offset = 0, read;
            while (offset < fileSize && (read = fis.read(bytes, offset, fileSize - offset)) != -1) {
                offset += read;
            }
            fis.close();
            file.delete();
            JSObject result = new JSObject();
            result.put("recordDataBase64", Base64.encodeToString(bytes, Base64.NO_WRAP));
            result.put("mimeType", "audio/mp4");
            call.resolve(result);
        } catch (Exception e) {
            // فشل الإيقاف (إيقاف فوري بعد البدء مثلاً): حرّر المسجّل — لا يبقى المايك محجوزاً
            try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
            recorder = null;
            if (outputPath != null) { try { new File(outputPath).delete(); } catch (Exception ignored) {} }
            call.reject(buildExceptionDetail(e));
        }
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
            if (outputPath != null) new File(outputPath).delete();
        }
        call.resolve();
    }

    /* ── تحرير المسجّل إن دُمّر النشاط أثناء تسجيل جارٍ (يمنع حجز المايك) ── */
    @Override
    protected void handleOnDestroy() {
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
            if (outputPath != null) { try { new File(outputPath).delete(); } catch (Exception ignored) {} }
        }
        super.handleOnDestroy();
    }

    /* ── Helpers ── */

    private boolean sysPermGranted() {
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }

    private String permStateText() {
        if (sysPermGranted()) return "granted";
        PermissionState s = getPermissionState("microphone");
        return s == PermissionState.DENIED ? "denied" : "prompt";
    }

    private String buildExceptionDetail(Exception e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getName()).append(": ").append(e.getMessage());
        StackTraceElement[] st = e.getStackTrace();
        for (int i = 0; i < Math.min(st.length, 6); i++) {
            sb.append("\n  at ").append(st[i]);
        }
        Throwable cause = e.getCause();
        if (cause != null) {
            sb.append("\nCaused by: ").append(cause.getClass().getName())
              .append(": ").append(cause.getMessage());
        }
        return sb.toString();
    }
}
