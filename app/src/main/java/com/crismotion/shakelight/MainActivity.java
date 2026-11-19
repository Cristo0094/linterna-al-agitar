package com.crismotion.shakelight;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {

    static final String PREFS = "shake_prefs";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_LOW_POWER = "low_power";
    static final String KEY_SENSITIVITY = "sensitivity";

    private TextView modeText;

    @Override
    protected void onResume() {
        super.onResume();
        refreshMode();
    }

    private void refreshMode() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (modeText == null) return;
        if (!prefs.getBoolean(KEY_ENABLED, false)) {
            modeText.setText("");
        } else if (prefs.getBoolean(KEY_LOW_POWER, false)) {
            modeText.setText("\nModo ahorro: funciona con la pantalla apagada y casi no gasta batería.");
        } else {
            modeText.setText("\nTu celu no tiene el sensor de bajo consumo: puede que solo "
                    + "funcione con la pantalla prendida.");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Linterna al agitar");
        title.setTextSize(26);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("\nAgitá el celu fuerte un par de veces para prender la linterna. "
                + "Agitalo de nuevo para apagarla.\n");
        info.setTextSize(16);
        root.addView(info);

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Switch toggle = new Switch(this);
        toggle.setText("Activado");
        toggle.setTextSize(20);
        toggle.setChecked(prefs.getBoolean(KEY_ENABLED, false));
        toggle.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(KEY_ENABLED, on).apply();
            Intent svc = new Intent(this, ShakeService.class);
            if (on) startForegroundService(svc);
            else stopService(svc);
            modeText.postDelayed(this::refreshMode, 500);
        });
        root.addView(toggle);

        TextView sensLabel = new TextView(this);
        sensLabel.setText("\nSensibilidad (si se prende sola al caminar, bajala):");
        sensLabel.setTextSize(16);
        root.addView(sensLabel);

        android.widget.RadioGroup sens = new android.widget.RadioGroup(this);
        sens.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Baja", "Media", "Alta"};
        for (int i = 0; i < 3; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(100 + i);
            rb.setText(names[i]);
            rb.setTextSize(16);
            sens.addView(rb);
        }
        sens.check(100 + prefs.getInt(KEY_SENSITIVITY, 1));
        sens.setOnCheckedChangeListener((g, id) ->
                prefs.edit().putInt(KEY_SENSITIVITY, id - 100).apply());
        root.addView(sens);

        modeText = new TextView(this);
        modeText.setTextSize(14);
        root.addView(modeText);

        TextView battery = new TextView(this);
        battery.setText("\nPara que realme no la cierre en segundo plano, tocá el botón y "
                + "en Batería elegí \"Permitir actividad en segundo plano\".\n");
        battery.setTextSize(14);
        root.addView(battery);

        Button settings = new Button(this);
        settings.setText("Abrir ajustes de la app");
        settings.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        root.addView(settings);

        setContentView(root);

        if (toggle.isChecked()) startForegroundService(new Intent(this, ShakeService.class));
    }
}
