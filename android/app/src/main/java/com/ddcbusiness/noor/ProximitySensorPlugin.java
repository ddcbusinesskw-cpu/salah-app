package com.ddcbusiness.noor;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
        }
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (running) {
            sensorManager.unregisterListener(this);
            running = false;
        }
        call.resolve();
    }

    /* ── إلغاء تسجيل المستمع إن دُمّر النشاط أثناء التشغيل (يمنع التسريب) ── */
    @Override
    protected void handleOnDestroy() {
        if (running) {
            try { sensorManager.unregisterListener(this); } catch (Exception ignored) {}
            running = false;
        }
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
