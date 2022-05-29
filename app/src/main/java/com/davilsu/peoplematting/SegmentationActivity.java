package com.davilsu.peoplematting;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.exifinterface.media.ExifInterface;
import androidx.preference.PreferenceManager;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class SegmentationActivity extends AppCompatActivity {

    private static final int INPUT_SIZE = 512;

    private static final Executor executor = Executors.newSingleThreadExecutor();

    private ImageView imageView;
    @Nullable
    private Bitmap bitmapAlpha;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_segmentation);

        setTitle(R.string.select_background);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        imageView = findViewById(R.id.image_view);

        Intent intent = getIntent();
        if (intent != null) {
            executor.execute(() -> {
                Bitmap bitmap = loadImageFromIntent(intent);
                if (bitmap != null) {
                    runSegmentationNetwork(bitmap);
                }
            });
        }
    }

    private void runSegmentationNetwork(Bitmap bitmap) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        int prefMode = Integer.parseInt(preferences.getString("powersave_mode", "0"));
        int modelIndex = Integer.parseInt(preferences.getString("segmentation_network", "1"));
        boolean useGpu = preferences.getBoolean("int8_quantization", false);
        boolean fp16Quantization = preferences.getBoolean("fp16_quantization", false);
        boolean int8Quantization = preferences.getBoolean("int8_quantization", false);

        Bitmap outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        MattingNetwork network = new MattingNetwork();
        if (!network.Init(getAssets(), prefMode)) {
            runOnUiThread(() -> Toast.makeText(this, "Device does not support big.LITTLE architecture", Toast.LENGTH_LONG).show());
        }
        int ret = network.Process(getAssets(), outputBitmap, modelIndex, fp16Quantization, int8Quantization, useGpu);
        if (ret < 0) {
            runOnUiThread(() -> Toast.makeText(this, "Inference Error", Toast.LENGTH_LONG).show());
            return;
        }
        double elapsedTime = ret / 100.0;
        runOnUiThread(() -> Toast.makeText(this, String.format("Inference time: %.2fms", elapsedTime), Toast.LENGTH_SHORT).show());

        runOnUiThread(() -> {
            bitmapAlpha = outputBitmap;
            ProgressBar progressbar = findViewById(R.id.progress_bar);
            progressbar.setVisibility(View.GONE);
            imageView.setImageBitmap(outputBitmap);
        });
    }

    private Bitmap loadImageFromIntent(Intent intent) {
        if (intent.hasExtra("bitmap")) {
            return intent.getParcelableExtra("bitmap");
        } else if (intent.getData() != null) {
            try {
                return decodeUri(intent.getData());
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                return null;
            }
        } else {
            return null;
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case R.id.share:
                sharePhoto();
                return true;
            case R.id.save:
                savePhoto();
                return true;
            case android.R.id.home:
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_segmentation, menu);
        return true;
    }

    public void sharePhoto() {
        // TODO
    }

    private OutputStream saveToGallery(String filename) throws FileNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);
            Uri imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            return resolver.openOutputStream(imageUri);
        } else {
            File imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            return new FileOutputStream(new File(imagesDir, filename));
        }
    }

    public void savePhoto() {
        if (bitmapAlpha == null) {
            return;
        }

        String filename = LocalDateTime.now().toString() + ".jpg";
        try (OutputStream outputStream = saveToGallery(filename)) {
            bitmapAlpha.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
        Toast.makeText(this, "Saved to " + filename, Toast.LENGTH_SHORT).show();
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