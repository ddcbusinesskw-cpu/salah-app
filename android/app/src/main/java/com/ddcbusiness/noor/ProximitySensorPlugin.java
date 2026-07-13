package com.ddcbusiness.noor;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.PowerManager;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "ProximitySensor")
public class ProximitySensorPlugin extends Plugin implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor proximitySensor;
    private boolean running = false;
    /* wake lock جزئي: يُبقي المعالج (وبالتالي بثّ الحساس) حيّاً أثناء جلسة
       متابع الصلاة حتى لو انطفأت الشاشة. بمهلة قصوى 3 ساعات كصمّام أمان. */
    private PowerManager.WakeLock wakeLock;
    private static final long WAKELOCK_TIMEOUT_MS = 3L * 60 * 60 * 1000;

    @Override
    public void load() {
        sensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
    }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("value", proximitySensor != null);
        call.resolve(ret);
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (proximitySensor == null) {
            call.reject("no-sensor");
            return;
        }
        if (!running) {
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_GAME);
            running = true;
            acquireWakeLock();
        }
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (running) {
            sensorManager.unregisterListener(this);
            running = false;
        }
        releaseWakeLock();
        call.resolve();
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noor:salahProx");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
    }

    /* ── إلغاء تسجيل المستمع إن دُمّر النشاط أثناء التشغيل (يمنع التسريب) ── */
    @Override
    protected void handleOnDestroy() {
        if (running) {
            try { sensorManager.unregisterListener(this); } catch (Exception ignored) {}
            running = false;
        }
        releaseWakeLock();
        super.handleOnDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_PROXIMITY) return;

        float value = event.values[0];
        float maxRange = proximitySensor.getMaximumRange();

        // Binary sensors (most phones): emit 0.0 when near, maxRange when far.
        // Continuous range sensors: emit a distance in cm up to maxRange.
        // In both cases "value < maxRange" correctly identifies the near state.
        // For continuous sensors we add a 3 cm hard ceiling so a hand at 4 cm
        // on a device with maxRange=10 cm is still considered far.
        boolean near = value < maxRange && value <= 3.0f;

        JSObject data = new JSObject();
        data.put("near", near);
        data.put("value", value);
        notifyListeners("proximity", data);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
