package com.mike.lets;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.color.DynamicColors;
import com.google.common.util.concurrent.ListenableFuture;
import com.mike.lets.databinding.ActivityMainBinding;
import com.mike.lets.vision.Model;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

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
    private android.speech.tts.TextToSpeech tts;
    private android.app.AlertDialog popupDialog;
    private android.app.AlertDialog loadingDialog;
    private android.view.View popupView;
    private final com.mike.lets.textEntry.FastActionsManager fastActionsManager = new com.mike.lets.textEntry.FastActionsManager();

    private void showLoadingDialog(String title, String subtitle) {
        runOnUiThread(() -> {
            if (loadingDialog != null && loadingDialog.isShowing()) {
                loadingDialog.dismiss();
            }
            View view = getLayoutInflater().inflate(R.layout.popup_cargando_modelo, null);
            TextView tvTitle = view.findViewById(R.id.tv_loading_title);
            TextView tvSubtitle = view.findViewById(R.id.tv_loading_subtitle);
            if (title != null && tvTitle != null) tvTitle.setText(title);
            if (subtitle != null && tvSubtitle != null) tvSubtitle.setText(subtitle);

            loadingDialog = new android.app.AlertDialog.Builder(this)
                    .setView(view)
                    .setCancelable(false)
                    .create();

            if (loadingDialog.getWindow() != null) {
                loadingDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            }
            loadingDialog.show();
        });
    }

    private void hideLoadingDialog() {
        runOnUiThread(() -> {
            if (loadingDialog != null && loadingDialog.isShowing()) {
                loadingDialog.dismiss();
                loadingDialog = null;
            }
        });
    }

    private final ActivityResultLauncher<String> importModelLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    showLoadingDialog("Cargando modelo...", "Descomprimiendo e importando paquete .zip");
                    Executors.newSingleThreadExecutor().execute(() -> {
                        try {
                            processImportedPackage(uri);
                        } finally {
                            hideLoadingDialog();
                        }
                    });
                }
            }
    );

    private final android.content.BroadcastReceiver textGenerationReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            String message = intent.getStringExtra("message");
            if (presenter != null) {
                presenter.setLlmPrediction(message);
            }
        }
    };

    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};
    private static final int REQUEST_CODE_PERMISSIONS = 10;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);

        // Edge-to-edge support
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Apply insets to the main container
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, windowInsets) -> {
            androidx.core.graphics.Insets insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        // Initialize TTS
        tts = new android.speech.tts.TextToSpeech(this, status -> {
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts.setLanguage(new java.util.Locale("es", "ES"));
            }
        });

        // Initialize MVP
        Model model = new Model();
        presenter = new Presenter(this, model);

        try {
            presenter.initialize(this, getApplicationContext());
        } catch (Exception e) {
            Log.e("MainActivity", "Error initializing presenter", e);
        }

        // Set up the continue button in the calibration menu
        binding.calibrationLayout.buttonContinue.setOnClickListener(v -> {
            Log.d("MainActivity", "Continue button clicked");
            presenter.updateCalibration();
        });

        binding.calibrationLayout.btnSkipCalibration.setOnClickListener(v -> {
            Toast.makeText(this, "Calibración omitida (Depuración)", Toast.LENGTH_SHORT).show();
            binding.calibrationLayout.getRoot().setVisibility(View.GONE);
            binding.mainMenuLayout.getRoot().setVisibility(View.VISIBLE);
            presenter.setMode("Menu");
        });

        // Set up the main menu buttons
        binding.mainMenuLayout.panelTopLeft.setOnClickListener(v -> presenter.onGazeButtonClicked(6));
        binding.mainMenuLayout.panelTopRight.setOnClickListener(v -> presenter.onGazeButtonClicked(7));
        binding.mainMenuLayout.panelBottomLeft.setOnClickListener(v -> presenter.onGazeButtonClicked(1));
        binding.mainMenuLayout.panelBottomRight.setOnClickListener(v -> presenter.onGazeButtonClicked(2));
        binding.mainMenuLayout.btnCambiar.setOnClickListener(v -> presenter.onGazeButtonClicked(5)); // Wink -> Cambiar
        binding.mainMenuLayout.btnBorrar.setOnClickListener(v -> presenter.onGazeButtonClicked(3)); // Both closed -> Borrar

        binding.mainMenuLayout.btnAdjustLlm.setOnClickListener(v -> {
            binding.mainMenuLayout.getRoot().setVisibility(View.GONE);
            binding.settingsMenuLayout.getRoot().setVisibility(View.VISIBLE);
            presenter.setMode("Settings");
        });

        binding.settingsMenuLayout.btnBackSettings.setOnClickListener(v -> {
            binding.settingsMenuLayout.getRoot().setVisibility(View.GONE);
            binding.mainMenuLayout.getRoot().setVisibility(View.VISIBLE);
            presenter.setMode("Menu");
        });

        binding.settingsMenuLayout.btnImportModel.setOnClickListener(v -> importModelLauncher.launch("*/*"));
        
        binding.settingsMenuLayout.btnClearModel.setOnClickListener(v -> {
            showLoadingDialog("Restaurando modelo...", "Cargando modelo predeterminado");
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    UserDataManager userDataManager = (UserDataManager) getApplicationContext();
                    userDataManager.clearPackage();
                    presenter.reloadPackage();
                    runOnUiThread(() -> {
                        updatePackageUI();
                        Toast.makeText(this, "Paquete predeterminado restaurado", Toast.LENGTH_SHORT).show();
                    });
                } finally {
                    hideLoadingDialog();
                }
            });
        });

        binding.toolsMenuLayout.getRoot().setOnClickListener(v -> {
            binding.toolsMenuLayout.getRoot().setVisibility(View.GONE);
            binding.mainMenuLayout.getRoot().setVisibility(View.VISIBLE);
            presenter.setMode("Menu");
        });

        binding.comunicacionRapidaLayout.btnRapidaNecesidadesBasicas.setOnClickListener(v -> presenter.onGazeButtonClicked(6));
        binding.comunicacionRapidaLayout.btnRapidaVolverInicio.setOnClickListener(v -> presenter.onGazeButtonClicked(4));
        binding.comunicacionRapidaLayout.btnRapidaEmociones.setOnClickListener(v -> presenter.onGazeButtonClicked(7));
        binding.comunicacionRapidaLayout.btnRapidaControlAmbiental.setOnClickListener(v -> presenter.onGazeButtonClicked(1));
        binding.comunicacionRapidaLayout.btnRapidaActividadesSociales.setOnClickListener(v -> presenter.onGazeButtonClicked(5));
        binding.comunicacionRapidaLayout.btnRapidaNecesidadesMedicas.setOnClickListener(v -> presenter.onGazeButtonClicked(2));

        View subTl = findViewById(R.id.btn_sub_tl);
        View subTm = findViewById(R.id.btn_sub_tm);
        View subTr = findViewById(R.id.btn_sub_tr);
        View subBl = findViewById(R.id.btn_sub_bl);
        View subBm = findViewById(R.id.btn_sub_bm);
        View subBr = findViewById(R.id.btn_sub_br);
        if (subTl != null) subTl.setOnClickListener(v -> presenter.onGazeButtonClicked(6));
        if (subTm != null) subTm.setOnClickListener(v -> presenter.onGazeButtonClicked(4));
        if (subTr != null) subTr.setOnClickListener(v -> presenter.onGazeButtonClicked(7));
        if (subBl != null) subBl.setOnClickListener(v -> presenter.onGazeButtonClicked(1));
        if (subBm != null) subBm.setOnClickListener(v -> presenter.onGazeButtonClicked(5));
        if (subBr != null) subBr.setOnClickListener(v -> presenter.onGazeButtonClicked(2));

        // Initialize status text
        updatePackageUI();

        binding.mainMenuLayout.editContext.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                presenter.updateContext(s.toString());
            }
        });

        binding.mainMenuLayout.topBarLayout.btnAjustes.setOnClickListener(v -> {
            binding.mainMenuLayout.getRoot().setVisibility(View.GONE);
            binding.settingsMenuLayout.getRoot().setVisibility(View.VISIBLE);
            presenter.setMode("Settings");
        });
        binding.mainMenuLayout.topBarLayout.btnCalibracion.setOnClickListener(v -> openCalibration());
        binding.mainMenuLayout.topBarLayout.btnHome.setOnClickListener(v -> {
            binding.mainMenuLayout.getRoot().setVisibility(View.GONE);
            presenter.setMode("Dev");
        });

        // Start calibration automatically
        openCalibration();
        
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                .registerReceiver(textGenerationReceiver, new android.content.IntentFilter("textGenerationEvent"));
    }

    private Bitmap uiBitmap;
    private final Mat rotatedMat = new Mat();
    private byte[] rowDataBuffer;

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setTargetRotation(getWindowManager().getDefaultDisplay().getRotation())
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

                        int rotationDegrees = image.getImageInfo().getRotationDegrees();
                        Mat rgbaMat = imageToMat(image);

                        Mat processingMat;
                        // Rotate Mat based on rotation degrees to keep it upright (optimized without cloning)
                        if (rotationDegrees != 0) {
                            if (rotationDegrees == 90) {
                                org.opencv.core.Core.rotate(rgbaMat, rotatedMat, org.opencv.core.Core.ROTATE_90_CLOCKWISE);
                            } else if (rotationDegrees == 180) {
                                org.opencv.core.Core.rotate(rgbaMat, rotatedMat, org.opencv.core.Core.ROTATE_180);
                            } else if (rotationDegrees == 270) {
                                org.opencv.core.Core.rotate(rgbaMat, rotatedMat, org.opencv.core.Core.ROTATE_90_COUNTERCLOCKWISE);
                            }
                            rgbaMat.release(); // The original mat is no longer needed
                            processingMat = rotatedMat;
                        } else {
                            processingMat = rgbaMat;
                        }

                        // Mirroring for front camera to feel natural to the user
                        org.opencv.core.Core.flip(processingMat, processingMat, 1);

                        // Pass RGBA directly to the presenter (optimized)
                        presenter.onFrame(processingMat);
                        
                        // Update UI preview - reuse bitmap
                        if (uiBitmap == null || uiBitmap.getWidth() != processingMat.cols() || uiBitmap.getHeight() != processingMat.rows()) {
                            uiBitmap = Bitmap.createBitmap(processingMat.cols(), processingMat.rows(), Bitmap.Config.ARGB_8888);
                        }
                        Utils.matToBitmap(processingMat, uiBitmap);
                        
                        runOnUiThread(() -> {
                           binding.imageView.setImageBitmap(uiBitmap);
                        });

                        if (processingMat != rotatedMat) {
                            processingMat.release();
                        }
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
        int pixelStride = plane.getPixelStride(); 
        
        int width = image.getWidth();
        int height = image.getHeight();
        
        Mat mat = new Mat(height, width, CvType.CV_8UC4);
        
        if (rowStride == width * pixelStride) {
            int size = buffer.remaining();
            if (rowDataBuffer == null || rowDataBuffer.length < size) {
                rowDataBuffer = new byte[size];
            }
            buffer.get(rowDataBuffer, 0, size);
            mat.put(0, 0, rowDataBuffer, 0, size);
        } else {
            // Re-use row data buffer to reduce GC pressure
            if (rowDataBuffer == null || rowDataBuffer.length < rowStride) {
                rowDataBuffer = new byte[rowStride];
            }
            for (int i = 0; i < height; i++) {
                buffer.position(i * rowStride);
                buffer.get(rowDataBuffer, 0, Math.min(rowStride, buffer.remaining()));
                mat.put(i, 0, rowDataBuffer, 0, width * pixelStride);
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
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(textGenerationReceiver);
        cameraExecutor.shutdown();
        presenter.onDestroy();
    }

    @Override
    public void updateLiveData(AppLiveData appLiveData) {
        // Capture the current detection output reference to avoid issues with concurrent updates
        final com.mike.lets.vision.DetectionOutput currentOutput = appLiveData.DetectionOutput;
        
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
                        binding.calibrationLayout.imageView8,
                        binding.calibrationLayout.imageView9
                };
                for (int i = 0; i < views.length; i++) {
                    if (i < appLiveData.leftTemplates.length && appLiveData.leftTemplates[i] != null) {
                        views[i].setImageBitmap(appLiveData.leftTemplates[i]);
                    }
                }
            }

            if (appLiveData.calibrationState == 7) { // Finished
                binding.calibrationLayout.buttonContinue.setText("Finish");
                binding.calibrationLayout.buttonContinue.setOnClickListener(v -> {
                   Toast.makeText(this, "Finishing calibration", Toast.LENGTH_SHORT).show();
                   binding.calibrationLayout.getRoot().setVisibility(View.GONE);
                   binding.mainMenuLayout.getRoot().setVisibility(View.VISIBLE);
                   presenter.setMode("Menu");
                });
            } else {
                binding.calibrationLayout.buttonContinue.setText(appLiveData.calibrationState == -1 ? "Start" : "Continue");
                binding.calibrationLayout.buttonContinue.setOnClickListener(v -> {
                    Log.i("MainActivity", "Button Clicked: State=" + appLiveData.calibrationState);
                    presenter.updateCalibration();
                });
            }

            if (currentOutput != null) {
                // Update Gaze Type and Loss
                if (currentOutput.AnalyzedData != null) {
                    int gazeType = currentOutput.AnalyzedData.GazeType;
                    String type = currentOutput.AnalyzedData.getTypeString(gazeType);
                    binding.gazeTypeText.setText("Overall Gaze Type: " + type);
                    binding.lossText.setText(String.format("Overall Loss: %.2f", appLiveData.DetectionOutput.AnalyzedData.GazeProbability));

                    // Update Main Menu content
                    if (binding.mainMenuLayout.getRoot().getVisibility() == View.VISIBLE) {
                        String display = "Text: " + appLiveData.currentText;
                        if (appLiveData.translatedWord != null && !appLiveData.translatedWord.isEmpty()) {
                            display += " [" + appLiveData.translatedWord + "]";
                        }
                        if (appLiveData.translatedSentence != null && !appLiveData.translatedSentence.isEmpty()) {
                            display += "\n(Tr: " + appLiveData.translatedSentence + ")";
                        }
                        binding.mainMenuLayout.textInputDisplay.setText(display);
                        
                        binding.mainMenuLayout.llmDisplay.setText("LLM: " + appLiveData.llmResponse);
                        
                        String[] groups = {"ABCDEF", "GHIJKLM", "NOPQRST", "UVWXYZ"};
                        String[] words = {"NADA", "NADA", "NADA", "NADA"};
                        String brStatus = groups[3];
                        
                        if (appLiveData.predictionsList != null && !appLiveData.predictionsList.isEmpty()) {
                            // En modo palabras, usamos 3 por página para dejar BR para "MÁS"
                            int pageSize = appLiveData.isWordMode ? 3 : 4;
                            int start = appLiveData.predictionPage * pageSize;
                            
                            for (int i = 0; i < pageSize; i++) {
                                if (start + i < appLiveData.predictionsList.size()) {
                                    words[i] = appLiveData.predictionsList.get(start + i);
                                }
                            }
                            
                            if (appLiveData.isWordMode) {
                                int nextStart = start + 3;
                                if (nextStart < appLiveData.predictionsList.size()) {
                                    words[3] = "MAS PALABRAS";
                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < 3; i++) {
                                        if (nextStart + i < appLiveData.predictionsList.size()) {
                                            sb.append(appLiveData.predictionsList.get(nextStart + i)).append(" ");
                                        }
                                    }
                                    if (nextStart + 3 < appLiveData.predictionsList.size()) sb.append("...");
                                    brStatus = sb.toString().trim();
                                } else {
                                    words[3] = "VOLVER A LETRAS";
                                    brStatus = "FIN DE LISTA";
                                }
                            } else {
                                // En modo letras, mostrar adelanto si hay más
                                int nextStart = start + 4;
                                if (nextStart < appLiveData.predictionsList.size()) {
                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < 3; i++) {
                                        if (nextStart + i < appLiveData.predictionsList.size()) {
                                            sb.append(appLiveData.predictionsList.get(nextStart + i)).append(" ");
                                        }
                                    }
                                    if (nextStart + 3 < appLiveData.predictionsList.size()) sb.append("...");
                                    brStatus = sb.toString().trim();
                                }
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
                            binding.mainMenuLayout.statusBottomRight.setText(brStatus);
                            
                            binding.mainMenuLayout.btnBorrar.setText("COMPLETO");
                        } else {
                            // Letter Mode: Groups in large text, predictions in small text
                            binding.mainMenuLayout.textTopLeft.setText(groups[0]);
                            binding.mainMenuLayout.statusTopLeft.setText(words[0]);
                            
                            binding.mainMenuLayout.textTopRight.setText(groups[1]);
                            binding.mainMenuLayout.statusTopRight.setText(words[1]);
                            
                            binding.mainMenuLayout.textBottomLeft.setText(groups[2]);
                            binding.mainMenuLayout.statusBottomLeft.setText(words[2]);
                            
                            // Si hay predicciones, permitir que BR sea dinámico tmb
                            if (appLiveData.predictionsList != null && !appLiveData.predictionsList.isEmpty()) {
                                binding.mainMenuLayout.textBottomRight.setText(groups[3]);
                                binding.mainMenuLayout.statusBottomRight.setText(brStatus);
                            } else {
                                binding.mainMenuLayout.textBottomRight.setText(groups[3]);
                                binding.mainMenuLayout.statusBottomRight.setText("NADA");
                            }
                            
                            binding.mainMenuLayout.btnBorrar.setText("BORRAR");
                        }

                        // Highlight active panel
                        boolean isPopupShowing = (popupView != null && popupDialog != null && popupDialog.isShowing()) || "Popup".equals(presenter.getMode()) || "ComunicacionRapida".equals(presenter.getMode());
                        if (isPopupShowing) {
                            binding.mainMenuLayout.panelTopLeft.setBackgroundResource(R.drawable.panel_background);
                            binding.mainMenuLayout.panelTopRight.setBackgroundResource(R.drawable.panel_background);
                            binding.mainMenuLayout.panelBottomLeft.setBackgroundResource(R.drawable.panel_background);
                            binding.mainMenuLayout.panelBottomRight.setBackgroundResource(R.drawable.panel_background);
                            
                            binding.mainMenuLayout.btnCambiar.setBackgroundResource(R.drawable.panel_background);
                            binding.mainMenuLayout.btnBorrar.setBackgroundResource(R.drawable.panel_background);
                        } else {
                            binding.mainMenuLayout.panelTopLeft.setBackgroundResource(gazeType == 6 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                            binding.mainMenuLayout.panelTopRight.setBackgroundResource(gazeType == 7 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                            binding.mainMenuLayout.panelBottomLeft.setBackgroundResource(gazeType == 1 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                            binding.mainMenuLayout.panelBottomRight.setBackgroundResource(gazeType == 2 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                            
                            binding.mainMenuLayout.btnCambiar.setBackgroundResource(gazeType == 5 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                            binding.mainMenuLayout.btnBorrar.setBackgroundResource(gazeType == 3 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                        }
                    }
                    
                    // Highlight ComunicacionRapida overlay panels if active
                    if ("ComunicacionRapida".equals(presenter.getMode())) {
                        binding.comunicacionRapidaLayout.btnRapidaNecesidadesBasicas.setBackgroundResource(gazeType == 6 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                        binding.comunicacionRapidaLayout.btnRapidaVolverInicio.setBackgroundResource(gazeType == 4 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                        binding.comunicacionRapidaLayout.btnRapidaEmociones.setBackgroundResource(gazeType == 7 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                        binding.comunicacionRapidaLayout.btnRapidaControlAmbiental.setBackgroundResource(gazeType == 1 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                        binding.comunicacionRapidaLayout.btnRapidaActividadesSociales.setBackgroundResource(gazeType == 5 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                        binding.comunicacionRapidaLayout.btnRapidaNecesidadesMedicas.setBackgroundResource(gazeType == 2 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                    }

                    // Highlight ComunicacionSubcategoria overlay panels if active
                    if (presenter.getMode() != null && presenter.getMode().startsWith("Subcategoria_")) {
                        View subTlView = findViewById(R.id.btn_sub_tl);
                        View subTmView = findViewById(R.id.btn_sub_tm);
                        View subTrView = findViewById(R.id.btn_sub_tr);
                        View subBlView = findViewById(R.id.btn_sub_bl);
                        View subBmView = findViewById(R.id.btn_sub_bm);
                        View subBrView = findViewById(R.id.btn_sub_br);
                        if (subTlView != null) subTlView.setBackgroundResource(gazeType == 6 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                        if (subTmView != null) subTmView.setBackgroundResource(gazeType == 4 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                        if (subTrView != null) subTrView.setBackgroundResource(gazeType == 7 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                        if (subBlView != null) subBlView.setBackgroundResource(gazeType == 1 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                        if (subBmView != null) subBmView.setBackgroundResource(gazeType == 5 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                        if (subBrView != null) subBrView.setBackgroundResource(gazeType == 2 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                    }

                    // Highlight Popup buttons if visible
                    if (popupView != null && popupDialog != null && popupDialog.isShowing()) {
                        android.view.View btnComRapida = popupView.findViewById(R.id.btn_popup_comunicacion_rapida);
                        android.view.View btnHablar = popupView.findViewById(R.id.btn_popup_hablar);
                        android.view.View btnVolver = popupView.findViewById(R.id.btn_popup_volver);
                        android.view.View btnHerramientas = popupView.findViewById(R.id.btn_popup_herramientas);
                        if (btnComRapida != null) {
                            btnComRapida.setBackgroundResource(gazeType == 6 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                        }
                        if (btnHablar != null) {
                            btnHablar.setBackgroundResource(gazeType == 1 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                        }
                        if (btnVolver != null) {
                            btnVolver.setBackgroundResource(gazeType == 7 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                        }
                        if (btnHerramientas != null) {
                            btnHerramientas.setBackgroundResource(gazeType == 2 ? R.drawable.gaze_highlight_background : R.drawable.panel_background);
                        }
                    }
                }

                // Update Eye Image (using high-res mat at index 3 if available, else index 0)
                Mat eyeMat = null;
                if (currentOutput.testingMats != null) {
                    if (currentOutput.testingMats.length > 3 && currentOutput.testingMats[3] != null) {
                        eyeMat = currentOutput.testingMats[3];
                    } else if (currentOutput.testingMats[0] != null) {
                        eyeMat = currentOutput.testingMats[0];
                    }
                }

                if (eyeMat != null && !eyeMat.empty()) {
                    try {
                        Mat rgbaEye = new Mat();
                        if (eyeMat.channels() == 3) {
                            org.opencv.imgproc.Imgproc.cvtColor(eyeMat, rgbaEye, org.opencv.imgproc.Imgproc.COLOR_BGR2RGBA);
                        } else if (eyeMat.channels() == 1) {
                            org.opencv.imgproc.Imgproc.cvtColor(eyeMat, rgbaEye, org.opencv.imgproc.Imgproc.COLOR_GRAY2RGBA);
                        } else {
                            eyeMat.copyTo(rgbaEye);
                        }

                        if (!rgbaEye.empty()) {
                            Bitmap eyeBitmap = Bitmap.createBitmap(rgbaEye.cols(), rgbaEye.rows(), Bitmap.Config.ARGB_8888);
                            Utils.matToBitmap(rgbaEye, eyeBitmap);
                            binding.eyeImageView.setImageBitmap(eyeBitmap);
                            
                            if (binding.calibrationLayout.getRoot().getVisibility() == View.VISIBLE) {
                                binding.calibrationLayout.calibrationEyePreview.setImageBitmap(eyeBitmap);
                            }
                        }
                        rgbaEye.release();
                    } catch (Exception e) {
                        Log.e("MainActivity", "Error displaying eye preview", e);
                    }
                }
                
                // Release the testing mats now that the UI thread has finished drawing them
                if (currentOutput != null) {
                    currentOutput.release();
                }
            }
        });
    }

    @Override
    public void showCompletoPopup(String userText, String llmText) {
        runOnUiThread(() -> {
            if (popupDialog != null && popupDialog.isShowing()) return;

            popupView = getLayoutInflater().inflate(R.layout.popup_completo, null);
            android.widget.TextView contentTv = popupView.findViewById(R.id.popup_content_text);
            
            String fullText = "Texto: " + userText;
            if (llmText != null && !llmText.isEmpty()) {
                fullText += "\n\nLLM: " + llmText;
            }
            contentTv.setText(fullText);

            popupDialog = new android.app.AlertDialog.Builder(this)
                    .setView(popupView)
                    .setCancelable(false)
                    .create();
            
            if (popupDialog.getWindow() != null) {
                popupDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            }
            popupDialog.show();
            
            presenter.setMode("Popup");

            // Manual click handlers
            popupView.findViewById(R.id.btn_popup_volver).setOnClickListener(v -> closePopup());
            popupView.findViewById(R.id.btn_popup_hablar).setOnClickListener(v -> presenter.onGazeButtonClicked(1));
            popupView.findViewById(R.id.btn_popup_herramientas).setOnClickListener(v -> presenter.onGazeButtonClicked(2));
            popupView.findViewById(R.id.btn_popup_comunicacion_rapida).setOnClickListener(v -> presenter.onGazeButtonClicked(6));
        });
    }

    @Override
    public void openComunicacionRapidaMenu() {
        runOnUiThread(() -> {
            UserDataManager userDataManager = (UserDataManager) getApplicationContext();
            fastActionsManager.loadFastActions(this, userDataManager.getPackageFastActionsPath());

            com.mike.lets.textEntry.FastActionsManager.Category c1 = fastActionsManager.getCategory("NecesidadesBasicas");
            com.mike.lets.textEntry.FastActionsManager.Category c2 = fastActionsManager.getCategory("ControlAmbiental");
            com.mike.lets.textEntry.FastActionsManager.Category c3 = fastActionsManager.getCategory("ActividadesSociales");
            com.mike.lets.textEntry.FastActionsManager.Category c4 = fastActionsManager.getCategory("NecesidadesMedicas");
            com.mike.lets.textEntry.FastActionsManager.Category c5 = fastActionsManager.getCategory("Emociones");

            if (c1 != null && c1.buttonText != null) binding.comunicacionRapidaLayout.textRapida1.setText(c1.buttonText);
            if (c2 != null && c2.buttonText != null) binding.comunicacionRapidaLayout.textRapida4.setText(c2.buttonText);
            if (c3 != null && c3.buttonText != null) binding.comunicacionRapidaLayout.textRapida5.setText(c3.buttonText);
            if (c4 != null && c4.buttonText != null) binding.comunicacionRapidaLayout.textRapida6.setText(c4.buttonText);
            if (c5 != null && c5.buttonText != null) binding.comunicacionRapidaLayout.textRapida3.setText(c5.buttonText);

            binding.mainMenuLayout.getRoot().setVisibility(View.GONE);
            binding.toolsMenuLayout.getRoot().setVisibility(View.GONE);
            binding.settingsMenuLayout.getRoot().setVisibility(View.GONE);
            binding.calibrationLayout.getRoot().setVisibility(View.GONE);
            View subLayout = findViewById(R.id.comunicacionSubcategoriaLayout);
            if (subLayout != null) subLayout.setVisibility(View.GONE);
            binding.comunicacionRapidaLayout.getRoot().setVisibility(View.VISIBLE);
            presenter.setMode("ComunicacionRapida");
        });
    }

    @Override
    public void closeComunicacionRapidaMenu() {
        runOnUiThread(() -> {
            View subLayout = findViewById(R.id.comunicacionSubcategoriaLayout);
            if (subLayout != null) subLayout.setVisibility(View.GONE);
            binding.comunicacionRapidaLayout.getRoot().setVisibility(View.GONE);
            binding.mainMenuLayout.getRoot().setVisibility(View.VISIBLE);
            presenter.setMode("Menu");
        });
    }

    @Override
    public void openSubcategoriaMenu(String category) {
        runOnUiThread(() -> {
            UserDataManager userDataManager = (UserDataManager) getApplicationContext();
            fastActionsManager.loadFastActions(this, userDataManager.getPackageFastActionsPath());
            com.mike.lets.textEntry.FastActionsManager.Category cat = fastActionsManager.getCategory(category);

            String title = "SUBCATEGORÍA";
            String p1 = "", p2 = "", p3 = "", p4 = "", p5 = "";

            if (cat != null) {
                if (cat.title != null && !cat.title.isEmpty()) {
                    title = cat.title;
                }
                if (cat.phrases != null) {
                    if (cat.phrases.size() > 0) p1 = cat.phrases.get(0);
                    if (cat.phrases.size() > 1) p2 = cat.phrases.get(1);
                    if (cat.phrases.size() > 2) p3 = cat.phrases.get(2);
                    if (cat.phrases.size() > 3) p4 = cat.phrases.get(3);
                    if (cat.phrases.size() > 4) p5 = cat.phrases.get(4);
                }
            }

            android.widget.TextView tvTitle = findViewById(R.id.tv_subcategoria_titulo);
            android.widget.TextView textTl = findViewById(R.id.text_sub_tl);
            android.widget.TextView textBl = findViewById(R.id.text_sub_bl);
            android.widget.TextView textBm = findViewById(R.id.text_sub_bm);
            android.widget.TextView textBr = findViewById(R.id.text_sub_br);
            android.widget.TextView textTr = findViewById(R.id.text_sub_tr);
            android.widget.TextView textTm = findViewById(R.id.text_sub_tm);

            if (tvTitle != null) tvTitle.setText(title);
            if (textTl != null) textTl.setText(p1);
            if (textBl != null) textBl.setText(p2);
            if (textBm != null) textBm.setText(p3);
            if (textBr != null) textBr.setText(p4);
            if (textTr != null) textTr.setText(p5);
            if (textTm != null) textTm.setText("REGRESAR");

            binding.comunicacionRapidaLayout.getRoot().setVisibility(View.GONE);
            View subLayout = findViewById(R.id.comunicacionSubcategoriaLayout);
            if (subLayout != null) subLayout.setVisibility(View.VISIBLE);
            presenter.setMode("Subcategoria_" + category);
        });
    }

    @Override
    public void closeSubcategoriaMenu() {
        runOnUiThread(() -> {
            View subLayout = findViewById(R.id.comunicacionSubcategoriaLayout);
            if (subLayout != null) subLayout.setVisibility(View.GONE);
            binding.comunicacionRapidaLayout.getRoot().setVisibility(View.VISIBLE);
            presenter.setMode("ComunicacionRapida");
        });
    }

    @Override
    public void speakText(String text) {
        if (tts != null) {
            tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    @Override
    public void closePopup() {
        runOnUiThread(() -> {
            if (popupDialog != null) {
                popupDialog.dismiss();
            }
            popupView = null;
            presenter.setMode("Menu");
        });
    }

    @Override
    public void openSettings() {
    }

    @Override
    public void openToolsMenu() {
        runOnUiThread(() -> {
            binding.toolsMenuLayout.getRoot().setVisibility(View.VISIBLE);
            presenter.setMode("Tools");
        });
    }

    @Override
    public void openCalibration() {
        runOnUiThread(() -> {
            binding.calibrationLayout.getRoot().setVisibility(View.VISIBLE);
            presenter.setMode("Calibration");
        });
    }

    @Override
    public void clearContext() {
        runOnUiThread(() -> binding.mainMenuLayout.editContext.setText(""));
    }

    public native String stringFromJNI();

    private void updatePackageUI() {
        UserDataManager udm = (UserDataManager) getApplicationContext();
        String lang = udm.getPackageLanguage();
        String ver = udm.getPackageVersion();
        String desc = udm.getPackageDescription();
        String modelPath = udm.getPackageModelPath();

        String titleText = (lang != null && !lang.isEmpty() ? lang : "Spanish") +
                           (ver != null && !ver.isEmpty() ? " v" + ver : " v1.0");
        String descText = "Descripción: " + (desc != null && !desc.isEmpty() ? desc : "null");
        
        String modelName = "Predeterminado (gemma3-1b.gguf)";
        if (modelPath != null && !modelPath.isEmpty()) {
            File f = new File(modelPath);
            if (f.exists()) {
                long sizeMb = f.length() / (1024 * 1024);
                modelName = f.getName() + " (" + sizeMb + " MB)";
            } else {
                modelName = "NO ENCONTRADO (" + f.getName() + ")";
            }
        }

        binding.settingsMenuLayout.tvPackageTitle.setText(titleText);
        binding.settingsMenuLayout.tvPackageDescription.setText(descText);
        binding.settingsMenuLayout.tvModelStatus.setText("Modelo: " + modelName);
    }

    private void processImportedPackage(Uri uri) {
        try {
            String fileName = getFileName(uri);
            if (fileName == null || (!fileName.toLowerCase().endsWith(".zip") && !fileName.toLowerCase().endsWith(".rar"))) {
                runOnUiThread(() -> Toast.makeText(this, "Por favor selecciona un archivo .zip", Toast.LENGTH_SHORT).show());
                return;
            }

            File packageDir = new File(getFilesDir(), "active_package");
            deleteDirectory(packageDir);
            if (!packageDir.mkdirs()) {
                Log.e("MainActivity", "Failed to create package directory");
            }

            try (InputStream is = getContentResolver().openInputStream(uri);
                 ZipInputStream zis = new ZipInputStream(new java.io.BufferedInputStream(is))) {
                
                ZipEntry ze;
                byte[] buffer = new byte[1024 * 8];
                while ((ze = zis.getNextEntry()) != null) {
                    String zeName = ze.getName();
                    File targetFile = new File(packageDir, zeName);
                    if (!targetFile.getCanonicalPath().startsWith(packageDir.getCanonicalPath())) {
                        throw new SecurityException("Zip entry is outside target dir: " + zeName);
                    }

                    if (ze.isDirectory()) {
                        targetFile.mkdirs();
                    } else {
                        File parent = targetFile.getParentFile();
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs();
                        }
                        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                            int count;
                            while ((count = zis.read(buffer)) != -1) {
                                fos.write(buffer, 0, count);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            }

            File packageJsonFile = findFile(packageDir, "Package.json");
            if (packageJsonFile == null || !packageJsonFile.exists()) {
                runOnUiThread(() -> Toast.makeText(this, "Error: No se encontró el archivo Package.json en el .zip", Toast.LENGTH_LONG).show());
                return;
            }

            File baseDir = packageJsonFile.getParentFile();

            String jsonText = readFileToString(packageJsonFile).trim();
            JSONObject pkgObj = null;
            if (jsonText.startsWith("[")) {
                JSONArray arr = new JSONArray(jsonText);
                if (arr.length() > 0) {
                    pkgObj = arr.getJSONObject(0);
                }
            } else if (jsonText.startsWith("{")) {
                pkgObj = new JSONObject(jsonText);
            }

            if (pkgObj == null) {
                runOnUiThread(() -> Toast.makeText(this, "Error: Formato inválido en Package.json", Toast.LENGTH_LONG).show());
                return;
            }

            String language = pkgObj.optString("Language", "Spanish");
            String wordListFileName = pkgObj.optString("WordList", "");
            String modelFileName = pkgObj.optString("ModelFile", "");
            String fastActionsFileName = pkgObj.optString("FastActions", pkgObj.optString("FastActionsFile", "FastActions.json"));
            String description = pkgObj.optString("description", "null");
            String version = pkgObj.optString("version", "1.0");

            // Look for files in packageDir, attempting both simple name search and baseDir relative path
            File wordListFile = findFile(packageDir, new File(wordListFileName).getName());
            if (wordListFile == null) {
                wordListFile = new File(baseDir, wordListFileName);
            }

            File modelFile = findFile(packageDir, new File(modelFileName).getName());
            if (modelFile == null) {
                modelFile = new File(baseDir, modelFileName);
            }

            File fastActionsFile = findFile(packageDir, new File(fastActionsFileName).getName());
            if (fastActionsFile == null) {
                fastActionsFile = new File(baseDir, fastActionsFileName);
            }

            if (!wordListFile.exists()) {
                Log.w("MainActivity", "WordList file not found in package: " + wordListFileName);
            }
            if (!modelFile.exists()) {
                Log.w("MainActivity", "Model file not found in package: " + modelFileName);
                runOnUiThread(() -> Toast.makeText(this, "Advertencia: No se encontró el archivo del modelo (" + modelFileName + ") en el .zip", Toast.LENGTH_LONG).show());
            }

            UserDataManager userDataManager = (UserDataManager) getApplicationContext();
            userDataManager.setPackageLanguage(language);
            userDataManager.setPackageVersion(version);
            userDataManager.setPackageDescription(description);
            userDataManager.setPackageWordListPath(wordListFile.exists() ? wordListFile.getAbsolutePath() : "");
            userDataManager.setPackageModelPath(modelFile.exists() ? modelFile.getAbsolutePath() : "");
            userDataManager.setPackageFastActionsPath(fastActionsFile != null && fastActionsFile.exists() ? fastActionsFile.getAbsolutePath() : "");
            userDataManager.setLanguage(language);

            presenter.reloadPackage();

            runOnUiThread(() -> {
                updatePackageUI();
                Toast.makeText(this, "Paquete " + language + " v" + version + " cargado con éxito", Toast.LENGTH_SHORT).show();
            });

        } catch (Exception e) {
            Log.e("MainActivity", "Error processing package", e);
            runOnUiThread(() -> Toast.makeText(this, "Error al importar paquete: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private void deleteDirectory(File dir) {
        if (dir != null && dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectory(child);
                }
            }
        }
        if (dir != null) {
            dir.delete();
        }
    }

    private File findFile(File dir, String fileName) {
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equalsIgnoreCase(fileName)) {
                return f;
            } else if (f.isDirectory()) {
                File found = findFile(f, fileName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private String readFileToString(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index != -1) {
                        result = cursor.getString(index);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

}
