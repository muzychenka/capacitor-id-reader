package dev.idreader.capacitor;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.Image;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Camera and OCR remain on device. No frames or OCR results are logged or saved. */
public class MrzScannerActivity extends ComponentActivity {
    static final String EXTRA_LINES = "mrz_lines";
    private static final int CAMERA_REQUEST = 61;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private PreviewView previewView;
    private String previousKey;
    private int matchingFrames;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        previewView = new PreviewView(this);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        TextView hint = new TextView(this);
        String prompt = getIntent().getStringExtra("prompt");
        hint.setText(prompt == null ? "Point the camera at the machine-readable lines on your document" : prompt);
        hint.setTextColor(Color.WHITE);
        hint.setBackgroundColor(0x99000000);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(24, 24, 24, 24);
        FrameLayout.LayoutParams hintLayout = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(hint, hintLayout);
        Button cancel = new Button(this);
        String cancelLabel = getIntent().getStringExtra("cancelLabel");
        if (cancelLabel == null) cancel.setText(android.R.string.cancel); else cancel.setText(cancelLabel);
        cancel.setOnClickListener(view -> finish());
        FrameLayout.LayoutParams cancelLayout = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cancelLayout.bottomMargin = 32;
        root.addView(cancel, cancelLayout);
        setContentView(root);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{ Manifest.permission.CAMERA }, CAMERA_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else fail("CAMERA_PERMISSION_DENIED");
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(executor, this::analyze);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception error) { fail("CAMERA_UNAVAILABLE"); }
        }, ContextCompat.getMainExecutor(this));
    }

    @androidx.camera.core.ExperimentalGetImage
    private void analyze(ImageProxy proxy) {
        if (finished.get()) { proxy.close(); return; }
        Image image = proxy.getImage();
        if (image == null) { proxy.close(); return; }
        InputImage input = InputImage.fromMediaImage(image, proxy.getImageInfo().getRotationDegrees());
        com.google.android.gms.tasks.Task<Text> processing;
        try { processing = recognizer.process(input); }
        catch (RuntimeException error) {
            proxy.close();
            runOnUiThread(() -> fail("MRZ_OCR_FAILED"));
            return;
        }
        processing.addOnSuccessListener(text -> {
            if (finished.get()) return;
            List<String> lines = new ArrayList<>();
            for (Text.TextBlock block : text.getTextBlocks()) {
                for (Text.Line line : block.getLines()) lines.add(line.getText());
            }
            String[] mrz = MrzLines.find(lines);
            if (mrz == null) { previousKey = null; matchingFrames = 0; return; }
            String key = mrz[0] + "\n" + mrz[1];
            matchingFrames = key.equals(previousKey) ? matchingFrames + 1 : 1;
            previousKey = key;
            if (matchingFrames >= 2 && finished.compareAndSet(false, true)) {
                Intent result = new Intent();
                result.putExtra(EXTRA_LINES, mrz);
                setResult(RESULT_OK, result);
                finish();
            }
        }).addOnFailureListener(error -> fail("MRZ_OCR_FAILED"))
          .addOnCompleteListener(task -> proxy.close());
    }

    private void fail(String code) {
        if (!finished.compareAndSet(false, true)) return;
        Intent result = new Intent();
        result.putExtra("error_code", code);
        setResult(RESULT_CANCELED, result);
        finish();
    }

    @Override public void finish() {
        finished.set(true);
        super.finish();
    }

    @Override protected void onDestroy() {
        finished.set(true);
        executor.shutdown();
        recognizer.close();
        super.onDestroy();
    }
}
