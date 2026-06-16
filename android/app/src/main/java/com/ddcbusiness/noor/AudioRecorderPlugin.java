package com.ddcbusiness.noor;

import android.Manifest;
import android.media.MediaRecorder;
import android.util.Base64;
import com.getcapacitor.JSObject;
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

    @PluginMethod
    public void start(PluginCall call) {
        if (!hasPermission("microphone")) {
            requestPermissionForAlias("microphone", call, "micPermCallback");
            return;
        }
        doStart(call);
    }

    @PermissionCallback
    private void micPermCallback(PluginCall call) {
        if (hasPermission("microphone")) {
            doStart(call);
        } else {
            call.reject("PERMISSION_DENIED");
        }
    }

    private void doStart(PluginCall call) {
        try {
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) {}
                recorder.release();
                recorder = null;
            }
            outputPath = getContext().getCacheDir().getAbsolutePath() + "/tasmee_rec.aac";
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setOutputFile(outputPath);
            recorder.prepare();
            recorder.start();
            call.resolve();
        } catch (Exception e) {
            recorder = null;
            call.reject("START_ERROR: " + e.getMessage());
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
            String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            JSObject result = new JSObject();
            result.put("recordDataBase64", b64);
            result.put("mimeType", "audio/aac");
            call.resolve(result);
        } catch (Exception e) {
            call.reject("STOP_ERROR: " + e.getMessage());
        }
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            recorder.release();
            recorder = null;
            if (outputPath != null) new File(outputPath).delete();
        }
        call.resolve();
    }
}
