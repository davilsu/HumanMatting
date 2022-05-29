package com.davilsu.peoplematting;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;
import androidx.preference.PreferenceManager;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class SegmentationActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PICK_BACKGROUND = 1;

    private static final int INPUT_SIZE = 512;

    private static final Executor executor = Executors.newSingleThreadExecutor();

    private ImageView imageView;
    @Nullable
    private Bitmap bitmapAlpha;
    @Nullable
    private Bitmap bitmapBlended;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_segmentation);

        setTitle(R.string.select_background);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        imageView = findViewById(R.id.image_view);

        ((ImageView) findViewById(R.id.color_pick_transparency)).setOnClickListener(view -> blendAlpha());
        ((ImageView) findViewById(R.id.color_pick_black)).setOnClickListener(view -> blendColor(getColor(R.color.photo_black)));
        ((ImageView) findViewById(R.id.color_pick_white)).setOnClickListener(view -> blendColor(getColor(R.color.photo_white)));
        ((ImageView) findViewById(R.id.color_pick_red)).setOnClickListener(view -> blendColor(getColor(R.color.photo_red)));
        ((ImageView) findViewById(R.id.color_pick_green)).setOnClickListener(view -> blendColor(getColor(R.color.photo_green)));
        ((ImageView) findViewById(R.id.color_pick_blue)).setOnClickListener(view -> blendColor(getColor(R.color.photo_blue)));
        ((ImageView) findViewById(R.id.color_pick_image)).setOnClickListener(view -> blendImage());

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
        bitmap.recycle();
        outputBitmap.setHasAlpha(true);
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
        runOnUiThread(() -> Toast.makeText(this, getString(R.string.inference_cost_time) + String.format("%.2fms", elapsedTime), Toast.LENGTH_SHORT).show());

        // TODO workaround
        int[] buf = new int[outputBitmap.getWidth() * outputBitmap.getHeight()];
        outputBitmap.getPixels(buf, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        outputBitmap.setPixels(buf, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        runOnUiThread(() -> {
            bitmapAlpha = outputBitmap;
            bitmapBlended = outputBitmap;
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
        if (bitmapBlended == null) {
            return;
        }
        File tmpFile = new File(getExternalCacheDir(), "shared_photo.png");
        try (OutputStream outputStream = new FileOutputStream(tmpFile)) {
            bitmapBlended.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/jpeg");
        Uri photoURI = FileProvider.getUriForFile(this, getApplicationContext().getPackageName() + ".provider", tmpFile);
        intent.putExtra(Intent.EXTRA_STREAM, photoURI);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, getString(R.string.share_via)));
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
        if (bitmapBlended == null) {
            return;
        }

        String filename = LocalDateTime.now().toString() + ".jpg";
        try (OutputStream outputStream = saveToGallery(filename)) {
            bitmapBlended.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
        Toast.makeText(this, getString(R.string.saved_to) + filename, Toast.LENGTH_SHORT).show();
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

    public void blendAlpha() {
        bitmapBlended = bitmapAlpha;
        imageView.setImageBitmap(bitmapBlended);
    }

    public void blendColor(int color) {
        executor.execute(() -> {
            Bitmap overlay = Bitmap.createBitmap(bitmapAlpha.getWidth(), bitmapAlpha.getHeight(), bitmapAlpha.getConfig());
            Canvas canvas = new Canvas(overlay);
            canvas.drawColor(color);
            canvas.drawBitmap(bitmapAlpha, 0, 0, null);
            runOnUiThread(() -> {
                bitmapBlended = overlay;
                imageView.setImageBitmap(bitmapBlended);
            });
        });
    }

    public void blendImage() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_CODE_PICK_BACKGROUND);
    }

    public void doBlendImage(Uri image) {
        executor.execute(() -> {
            Bitmap bitmapBackground;
            try (InputStream in = getContentResolver().openInputStream(image)) {
                bitmapBackground = BitmapFactory.decodeStream(in);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            Bitmap overlay = Bitmap.createBitmap(bitmapAlpha.getWidth(), bitmapAlpha.getHeight(), bitmapAlpha.getConfig());
            Canvas canvas = new Canvas(overlay);
            canvas.drawBitmap(bitmapBackground, null, new Rect(0, 0, overlay.getWidth(), overlay.getHeight()), null);
            canvas.drawBitmap(bitmapAlpha, 0, 0, null);
            runOnUiThread(() -> {
                bitmapBlended = overlay;
                imageView.setImageBitmap(bitmapBlended);
            });
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_PICK_BACKGROUND:
                if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                    doBlendImage(data.getData());
                }
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
                break;
        }
    }
}