package com.davilsu.peoplematting;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.otaliastudios.cameraview.CameraListener;
import com.otaliastudios.cameraview.CameraView;
import com.otaliastudios.cameraview.PictureResult;
import com.otaliastudios.cameraview.controls.Facing;

import java.io.File;

public class CameraActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SELECT_PHOTO = 0;

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

        findViewById(R.id.shutter).setOnClickListener(v -> takePhoto());
        cameraView.addCameraListener(new CameraListener() {
            @Override
            public void onPictureTaken(@NonNull PictureResult result) {
                CameraActivity.this.onPictureTaken(result);
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_camera, menu);
        return true;
    }

    @SuppressLint("NonConstantResourceId")
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
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_CODE_SELECT_PHOTO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_CODE_SELECT_PHOTO) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Intent intent = new Intent(this, SegmentationActivity.class);
                intent.setDataAndType(data.getData(), data.getType());
                startActivity(intent);
                overridePendingTransition(0, 0);
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    public void takePhoto() {
        cameraView.takePicture();
    }

    public void onPictureTaken(PictureResult result) {
        File tmpFile = new File(getCacheDir(), "photo.jpg");
        result.toFile(tmpFile, file -> {
            Intent intent = new Intent(this, SegmentationActivity.class);
            intent.setData(Uri.fromFile(file));
            startActivity(intent);
            overridePendingTransition(0, 0);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        overridePendingTransition(0, 0);
    }
}