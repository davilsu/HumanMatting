package com.davilsu.peoplematting;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

import com.otaliastudios.cameraview.CameraView;

public class CameraActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        CameraView camera = findViewById(R.id.camera);
        camera.setLifecycleOwner(this);
    }
}