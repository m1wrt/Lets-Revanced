package com.mike.lets;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;
import com.mike.lets.databinding.ActivityMainBinding;
import com.mike.lets.vision.Model;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements ContractInterface.View {

    static {
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "Unable to load OpenCV!");
        } else {
            Log.d("OpenCV", "OpenCV loaded successfully.");
        }
        System.loadLibrary("lets");
    }

    private ActivityMainBinding binding;
    private ContractInterface.Presenter presenter;
    private ExecutorService cameraExecutor;

    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};
    private static final int REQUEST_CODE_PERMISSIONS = 10;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        // Initialize MVP
        Model model = new Model();
        presenter = new Presenter(this, model);

        try {
            presenter.initialize(this, getApplicationContext());
        } catch (Exception e) {
            Log.e("MainActivity", "Error initializing presenter", e);
        }

        // Set up the continue button in the calibration menu
        // ViewBinding generates a binding for included layouts if they have an ID
        binding.calibrationLayout.buttonContinue.setOnClickListener(v -> {
            presenter.updateCalibration();
        });

        // Set up the main menu buttons
        binding.mainMenuLayout.panelTopLeft.setOnClickListener(v -> presenter.onGazeButtonClicked(6));
        binding.mainMenuLayout.panelTopRight.setOnClickListener(v -> presenter.onGazeButtonClicked(7));
        binding.mainMenuLayout.panelBottomLeft.setOnClickListener(v -> presenter.onGazeButtonClicked(1));
        binding.mainMenuLayout.panelBottomRight.setOnClickListener(v -> presenter.onGazeButtonClicked(2));
        binding.mainMenuLayout.btnCambiar.setOnClickListener(v -> presenter.onGazeButtonClicked(5));
        binding.mainMenuLayout.btnBorrar.setOnClickListener(v -> presenter.onGazeButtonClicked(3));
        
        binding.mainMenuLayout.topBarLayout.btnAjustes.setOnClickListener(v -> presenter.setMode("Settings"));
        binding.mainMenuLayout.topBarLayout.btnCalibracion.setOnClickListener(v -> openCalibration());
        binding.mainMenuLayout.topBarLayout.btnHome.setOnClickListener(v -> {
            binding.mainMenuLayout.getRoot().setVisibility(View.GONE);
            presenter.setMode("Dev");
        });

        // Start calibration automatically
        openCalibration();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
                    @Override
                    public void analyze(@NonNull ImageProxy image) {
                        if (presenter.getPresenterState()) {
                            image.close();
                            return;
                        }

                        Mat rgbaMat = imageToMat(image);
                        // Convert to BGR for the model as it expects BGR in classifyGaze
                        // Wait, classifyGaze does: Imgproc.cvtColor(rgbMat, rgbaMat, Imgproc.COLOR_BGR2RGBA);
                        // So it expects BGR.
                        Mat bgrMat = new Mat();
                        org.opencv.imgproc.Imgproc.cvtColor(rgbaMat, bgrMat, org.opencv.imgproc.Imgproc.COLOR_RGBA2BGR);

                        presenter.onFrame(bgrMat);
                        
                        // Update UI preview
                        runOnUiThread(() -> {
                           Bitmap bitmap = Bitmap.createBitmap(rgbaMat.cols(), rgbaMat.rows(), Bitmap.Config.ARGB_8888);
                           Utils.matToBitmap(rgbaMat, bitmap);
                           binding.imageView.setImageBitmap(bitmap);
                        });

                        image.close();
                    }
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e("MainActivity", "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private Mat imageToMat(ImageProxy image) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride(); // should be 4 for RGBA_8888
        
        int width = image.getWidth();
        int height = image.getHeight();
        
        Mat mat = new Mat(height, width, CvType.CV_8UC4);
        
        if (rowStride == width * pixelStride) {
            // No padding, can copy directly
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            mat.put(0, 0, bytes);
        } else {
            // Has padding, copy row by row
            byte[] rowData = new byte[rowStride];
            for (int i = 0; i < height; i++) {
                buffer.position(i * rowStride);
                buffer.get(rowData, 0, Math.min(rowStride, buffer.remaining()));
                mat.put(i, 0, rowData, 0, width * pixelStride);
            }
        }
        return mat;
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        presenter.onDestroy();
    }

    @Override
    public void updateLiveData(AppLiveData appLiveData) {
        runOnUiThread(() -> {
            if (appLiveData.calibrationInstruction != null) {
                binding.sampleText.setText(appLiveData.calibrationInstruction);
                binding.calibrationLayout.calibrationInstruction.setText(appLiveData.calibrationInstruction);
            }

            if (appLiveData.leftTemplates != null) {
                ImageView[] views = {
                        binding.calibrationLayout.imageView3,
                        binding.calibrationLayout.imageView4,
                        binding.calibrationLayout.imageView5,
                        binding.calibrationLayout.imageView6,
                        binding.calibrationLayout.imageView7,
                        binding.calibrationLayout.imageView8
                };
                for (int i = 0; i < views.length; i++) {
                    if (i < appLiveData.leftTemplates.length && appLiveData.leftTemplates[i] != null) {
                        views[i].setImageBitmap(appLiveData.leftTemplates[i]);
                    }
                }
            }

            if (appLiveData.calibrationState == 6) { // Finished
                binding.calibrationLayout.buttonContinue.setText("Finish");
                binding.calibrationLayout.buttonContinue.setOnClickListener(v -> {
                   binding.calibrationLayout.getRoot().setVisibility(View.GONE);
                   binding.mainMenuLayout.getRoot().setVisibility(View.VISIBLE);
                   presenter.setMode("Menu");
                });
            } else {
                binding.calibrationLayout.buttonContinue.setText("Continue");
                binding.calibrationLayout.buttonContinue.setOnClickListener(v -> {
                    presenter.updateCalibration();
                });
            }

            if (appLiveData.DetectionOutput != null) {
                // Update Gaze Type and Loss
                if (appLiveData.DetectionOutput.AnalyzedData != null) {
                    int gazeType = appLiveData.DetectionOutput.AnalyzedData.GazeType;
                    String type = appLiveData.DetectionOutput.AnalyzedData.getTypeString(gazeType);
                    binding.gazeTypeText.setText("Overall Gaze Type: " + type);
                    binding.lossText.setText(String.format("Overall Loss: %.2f", appLiveData.DetectionOutput.AnalyzedData.GazeProbability));

                    // Update Main Menu content
                    if (binding.mainMenuLayout.getRoot().getVisibility() == View.VISIBLE) {
                        binding.mainMenuLayout.textInputDisplay.setText("Text: " + appLiveData.currentText);
                        binding.mainMenuLayout.llmDisplay.setText("LLM: " + appLiveData.llmResponse);
                        
                        String[] groups = {"ABCDEF", "GHIJKLM", "NOPQRST", "UVWXYZ"};
                        String[] words = {"NADA", "NADA", "NADA", "NADA"};
                        
                        if (appLiveData.predictionsList != null && !appLiveData.predictionsList.isEmpty()) {
                            for (int i = 0; i < Math.min(4, appLiveData.predictionsList.size()); i++) {
                                words[i] = appLiveData.predictionsList.get(i);
                            }
                        }

                        if (appLiveData.isWordMode) {
                            // Word Mode: Predictions in large text, groups in small text
                            binding.mainMenuLayout.textTopLeft.setText(words[0]);
                            binding.mainMenuLayout.statusTopLeft.setText(groups[0]);
                            
                            binding.mainMenuLayout.textTopRight.setText(words[1]);
                            binding.mainMenuLayout.statusTopRight.setText(groups[1]);
                            
                            binding.mainMenuLayout.textBottomLeft.setText(words[2]);
                            binding.mainMenuLayout.statusBottomLeft.setText(groups[2]);
                            
                            binding.mainMenuLayout.textBottomRight.setText(words[3]);
                            binding.mainMenuLayout.statusBottomRight.setText(groups[3]);
                            
                            binding.mainMenuLayout.btnBorrar.setText("COMPLETO");
                        } else {
                            // Letter Mode: Groups in large text, predictions in small text
                            binding.mainMenuLayout.textTopLeft.setText(groups[0]);
                            binding.mainMenuLayout.statusTopLeft.setText(words[0]);
                            
                            binding.mainMenuLayout.textTopRight.setText(groups[1]);
                            binding.mainMenuLayout.statusTopRight.setText(words[1]);
                            
                            binding.mainMenuLayout.textBottomLeft.setText(groups[2]);
                            binding.mainMenuLayout.statusBottomLeft.setText(words[2]);
                            
                            binding.mainMenuLayout.textBottomRight.setText(groups[3]);
                            binding.mainMenuLayout.statusBottomRight.setText(words[3]);
                            
                            binding.mainMenuLayout.btnBorrar.setText("BORRAR");
                        }

                        // Highlight active panel
                        binding.mainMenuLayout.panelTopLeft.setBackgroundResource(gazeType == 6 ? R.drawable.llm_area_background : R.drawable.panel_background);
                        binding.mainMenuLayout.panelTopRight.setBackgroundResource(gazeType == 7 ? R.drawable.llm_area_background : R.drawable.panel_background);
                        binding.mainMenuLayout.panelBottomLeft.setBackgroundResource(gazeType == 1 ? R.drawable.llm_area_background : R.drawable.panel_background);
                        binding.mainMenuLayout.panelBottomRight.setBackgroundResource(gazeType == 2 ? R.drawable.llm_area_background : R.drawable.panel_background);
                        
                        binding.mainMenuLayout.btnCambiar.setBackgroundResource(gazeType == 5 ? R.drawable.llm_area_background : R.drawable.panel_background);
                        binding.mainMenuLayout.btnBorrar.setBackgroundResource(gazeType == 3 ? R.drawable.llm_area_background : R.drawable.panel_background);
                    }
                }

                // Update Eye Image (using high-res mat at index 3 if available, else index 0)
                Mat eyeMat = null;
                if (appLiveData.DetectionOutput.testingMats != null) {
                    if (appLiveData.DetectionOutput.testingMats.length > 3 && appLiveData.DetectionOutput.testingMats[3] != null) {
                        eyeMat = appLiveData.DetectionOutput.testingMats[3];
                    } else if (appLiveData.DetectionOutput.testingMats[0] != null) {
                        eyeMat = appLiveData.DetectionOutput.testingMats[0];
                    }
                }

                if (eyeMat != null && !eyeMat.empty()) {
                    Mat rgbaEye = new Mat();
                    if (eyeMat.channels() == 3) {
                        org.opencv.imgproc.Imgproc.cvtColor(eyeMat, rgbaEye, org.opencv.imgproc.Imgproc.COLOR_BGR2RGBA);
                    } else {
                        eyeMat.copyTo(rgbaEye);
                    }

                    Bitmap eyeBitmap = Bitmap.createBitmap(rgbaEye.cols(), rgbaEye.rows(), Bitmap.Config.ARGB_8888);
                    Utils.matToBitmap(rgbaEye, eyeBitmap);
                    binding.eyeImageView.setImageBitmap(eyeBitmap);
                    
                    // Also update the preview in the calibration menu if it's visible
                    if (binding.calibrationLayout.getRoot().getVisibility() == View.VISIBLE) {
                        binding.calibrationLayout.calibrationEyePreview.setImageBitmap(eyeBitmap);
                    }
                }
            }
        });
    }

    @Override
    public void openSettings() {
    }

    @Override
    public void openCalibration() {
        runOnUiThread(() -> {
            binding.calibrationLayout.getRoot().setVisibility(View.VISIBLE);
            presenter.setMode("Calibration");
        });
    }

    public native String stringFromJNI();
}
