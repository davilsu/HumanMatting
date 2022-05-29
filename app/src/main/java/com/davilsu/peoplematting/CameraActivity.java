package com.davilsu.peoplematting;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.otaliastudios.cameraview.CameraView;
import com.otaliastudios.cameraview.controls.Facing;

public class CameraActivity extends AppCompatActivity {

    private CameraView cameraView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        setTitle(R.string.app_name);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        setSupportActionBar(findViewById(R.id.toolbar));

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        cameraView = findViewById(R.id.camera);
        cameraView.setFacing(Facing.valueOf(preferences.getString("default_camera_face", "FRONT")));
        cameraView.setLifecycleOwner(this);

        ((ImageView) findViewById(R.id.shutter)).setOnClickListener(v -> takePhoto());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_camera, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case R.id.switch_camera:
                switchCamera();
                return true;
            case R.id.preferences:
                openPreferences();
                return true;
            case R.id.pick_file:
                pickFile();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void switchCamera() {
        switch (cameraView.getFacing()) {
            case FRONT:
                cameraView.setFacing(Facing.BACK);
                break;
            case BACK:
                cameraView.setFacing(Facing.FRONT);
                break;
        }
    }

    public void openPreferences() {
        startActivity(new Intent(this, PreferencesActivity.class));
        overridePendingTransition(0, 0);
    }

    public void pickFile() {
        // TODO
    }

    public void takePhoto() {
        // TODO
        startActivity(new Intent(this, SegmentationActivity.class));
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        overridePendingTransition(0, 0);
    }
}