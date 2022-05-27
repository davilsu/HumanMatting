package com.davilsu.peoplematting;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.AdapterView;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.exifinterface.media.ExifInterface;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {
    private AppCompatImageView imgView;
    private SwitchCompat sw_INT8, sw_GPU;

    private static final int INPUT_SIZE = 512;
    private static final int SELECT_PHOTO = 0;
    private static final int TAKE_PHOTO = 1;
    private static final int REQUEST_CAMERA = 1;

    private final MattingNetwork network = new MattingNetwork();
    private final AtomicInteger modelIndex = new AtomicInteger(0);
    private final AtomicInteger perfMode = new AtomicInteger(2);
    private final AtomicBoolean enableFP16 = new AtomicBoolean(true);
    private Bitmap inputImage;

    @SuppressLint({"DefaultLocale", "NonConstantResourceId", "QueryPermissionsNeeded"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Request Permission
        int permission = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, MainActivity.REQUEST_CAMERA);
        }

        // Only enable big cluster by default
        network.Init(getAssets(), perfMode.get());

        imgView = findViewById(R.id.imageView);
        sw_INT8 = findViewById(R.id.switch_enable_int8);
        sw_GPU = findViewById(R.id.switch_enable_gpu);

        RadioGroup rg_core = findViewById(R.id.radioGroup_perf);
        rg_core.setOnCheckedChangeListener((group, checkedId) -> {
            switch (checkedId) {
                case R.id.radio_all:
                    perfMode.set(0);
                    break;
                case R.id.radio_little:
                    perfMode.set(1);
                    break;
                case R.id.radio_big:
                    perfMode.set(2);
                    break;
            }
            if (!network.Init(getAssets(), perfMode.get())) {
                final String err_str = "Device does not support big.LITTLE architecture";
                Toast.makeText(getApplicationContext(), err_str, Toast.LENGTH_LONG).show();
            }
        });

        AppCompatSpinner sp_modelSelect = findViewById(R.id.spinner_model_select);
        sp_modelSelect.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int pos, long id) {
                modelIndex.set(pos);
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        });

        AppCompatButton bt_readPhoto = findViewById(R.id.button_read_photo);
        bt_readPhoto.setOnClickListener(arg0 -> {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            startActivityForResult(intent, SELECT_PHOTO);
        });

        AppCompatButton bt_takePhoto = findViewById(R.id.button_take_photo);
        bt_takePhoto.setOnClickListener(arg0 -> {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            startActivityForResult(intent, TAKE_PHOTO);
        });

        AppCompatButton bt_infer = findViewById(R.id.button_inference);
        bt_infer.setOnLongClickListener(v -> {
            enableFP16.set(!enableFP16.get());
            if (enableFP16.get())
                getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            else
                getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            Toast.makeText(getApplicationContext(), "Enable FP16: " + enableFP16, Toast.LENGTH_SHORT).show();
            return true;
        });
        bt_infer.setOnClickListener(arg0 -> {
            if (inputImage == null)
                return;
            final boolean enableINT8 = sw_INT8.isChecked();
            final boolean enableGPU = sw_GPU.isChecked();
            Bitmap srcImage = inputImage.copy(Bitmap.Config.ARGB_8888, true);
            if (network.isNetworkChange(modelIndex.get(), enableFP16.get(), enableINT8)) {
                Toast.makeText(getApplicationContext(), "Model config is changed, reloading model...", Toast.LENGTH_SHORT).show();
            }
            int ret = network.Process(getAssets(), srcImage, modelIndex.get(), enableFP16.get(), enableINT8, enableGPU);
            if (ret < 0) {
                Toast.makeText(getApplicationContext(), "Detect error", Toast.LENGTH_SHORT).show();
            } else {
                final double elapsed_time = ret / 100.0;
                Toast.makeText(getApplicationContext(), String.format("Inference time:\t%.2fms", elapsed_time), Toast.LENGTH_SHORT).show();
            }
            imgView.setImageBitmap(srcImage);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || null == data) {
            return;
        }
        switch (requestCode) {
            case TAKE_PHOTO:
                try {
                    inputImage = (Bitmap) data.getExtras().get("data");
                    imgView.setImageBitmap(inputImage);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            case SELECT_PHOTO:
                Uri selectedImage = data.getData();
                try {
                    inputImage = decodeUri(selectedImage);
                    imgView.setImageBitmap(inputImage);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            default:
                break;
        }
    }

    private Bitmap decodeUri(Uri selectedImage) throws FileNotFoundException {
        // Decode image size
        BitmapFactory.Options options1 = new BitmapFactory.Options();
        options1.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(getContentResolver().openInputStream(selectedImage), null, options1);

        // Find the correct scale value. It should be the power of 2.
        int minHW = Integer.min(options1.outWidth, options1.outHeight);
        int scale = 1;
        while (minHW / 2 >= INPUT_SIZE) {
            minHW /= 2;
            scale *= 2;
        }

        // Decode with inSampleSize
        BitmapFactory.Options options2 = new BitmapFactory.Options();
        options2.inSampleSize = scale;
        Bitmap bitmap = BitmapFactory.decodeStream(getContentResolver().openInputStream(selectedImage), null, options2);

        // Rotate according to EXIF
        int rotate = 0;
        try {
            ExifInterface exif = new ExifInterface(getContentResolver().openInputStream(selectedImage));
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    rotate = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    rotate = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    rotate = 270;
                    break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(rotate);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }
}
