package com.crismotion.shakelight;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Servicio en primer plano que escucha el acelerometro.
 * Cuando detecta una agitada fuerte, prende o apaga la linterna.
 */
public class ShakeService extends Service implements SensorEventListener {

    private static final String CHANNEL_ID = "shake_light";
    private static final int NOTIF_ID = 1;

    // Ajustes de deteccion
    // Sensibilidad: 0 = baja, 1 = media, 2 = alta
    // Fuerza minima de cada sacudida (m/s2 por encima de la gravedad) y cantidad de sacudidas
    private static final float[] THRESHOLDS = {24f, 19f, 15f};
    private static final int[] PEAKS = {4, 4, 3};
    private static final int MAX_PEAKS = 4;
    private static final long WINDOW_MS = 1000;         // las sacudidas tienen que pasar dentro de este tiempo
    private static final long MIN_PEAK_GAP_MS = 90;     // separacion minima entre picos
    private static final long COOLDOWN_MS = 1000;       // espera minima despues de cada cambio
    private static final float CALM_THRESHOLD = 4f;     // por debajo de esto el celu se considera quieto
    private static final long CALM_MS = 600;            // tiempo quieto necesario para volver a escuchar
    private static final int SAMPLING_US = 20_000;      // 50 lecturas por segundo
    private static final int MAX_LATENCY_US = 250_000;  // despierta al procesador cada 0,25 s como mucho

    private SensorManager sensorManager;
    private CameraManager cameraManager;
    private String torchCameraId;
    private boolean torchOn = false;

    private final long[] peaks = new long[MAX_PEAKS];
    private int peakCount = 0;
    private long lastPeak = 0;
    private long lastToggle = 0;
    private long lastMotion = 0;
    private boolean armed = true;

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(torchCameraId)) torchOn = enabled;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startInForeground();

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        torchCameraId = findTorchCamera();
        if (torchCameraId != null) {
            cameraManager.registerTorchCallback(torchCallback, new Handler(Looper.getMainLooper()));
        }

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        // Modo ahorro (como Motorola): el chip de sensores del celu junta las lecturas
        // mientras el procesador duerme y lo despierta solo un par de veces por segundo.
        // No se usa wake lock, asi que el celu puede dormir normalmente.
        Sensor wakeAccel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true);
        boolean lowPower = false;
        if (wakeAccel != null && wakeAccel.getFifoMaxEventCount() > 0) {
            lowPower = sensorManager.registerListener(this, wakeAccel,
                    SAMPLING_US, MAX_LATENCY_US);
        }

        if (!lowPower) {
            // Respaldo: acelerometro comun, sin wake lock. Gasta casi nada,
            // pero en algunos celus solo responde con la pantalla prendida.
            Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accel != null) {
                sensorManager.registerListener(this, accel, SAMPLING_US, MAX_LATENCY_US);
            }
        }

        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(MainActivity.KEY_LOW_POWER, lowPower).apply();
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Linterna al agitar", NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Linterna al agitar activa")
                .setContentText("Agitá el celu para prender o apagar la luz")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(open)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private String findTorchCamera() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            }
        } catch (CameraAccessException ignored) {
        }
        return null;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        double force = Math.sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH;
        // Usa el momento real de cada lectura (llegan en tandas)
        long now = e.timestamp / 1_000_000L;

        if (Math.abs(force) > CALM_THRESHOLD) lastMotion = now;

        // Despues de cada cambio, espera a que el celu quede quieto un momento.
        // Asi una misma agitada no prende y apaga la luz de una.
        if (!armed) {
            if (now - lastToggle > COOLDOWN_MS && now - lastMotion > CALM_MS) {
                armed = true;
                peakCount = 0;
            }
            return;
        }

        if (Math.abs(force) < THRESHOLDS[2]) return;
        int level = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getInt(MainActivity.KEY_SENSITIVITY, 1);
        if (level < 0 || level > 2) level = 1;
        if (Math.abs(force) < THRESHOLDS[level]) return;
        if (now - lastPeak < MIN_PEAK_GAP_MS) return;
        lastPeak = now;

        // Descarta picos viejos fuera de la ventana
        if (peakCount > 0 && now - peaks[0] > WINDOW_MS) peakCount = 0;
        peaks[peakCount++] = now;

        if (peakCount >= PEAKS[level] || peakCount >= MAX_PEAKS) {
            peakCount = 0;
            lastToggle = now;
            armed = false;
            toggleTorch();
        }
    }

    private void toggleTorch() {
        if (torchCameraId == null) return;
        try {
            boolean newState = !torchOn;
            cameraManager.setTorchMode(torchCameraId, newState);
            torchOn = newState;
        } catch (Exception ignored) {
            // La camara esta en uso por otra app
            return;
        }
        vibrate();
    }

    private void vibrate() {
        try {
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) {
            // Si no puede vibrar, sigue funcionando igual
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (cameraManager != null) cameraManager.unregisterTorchCallback(torchCallback);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
