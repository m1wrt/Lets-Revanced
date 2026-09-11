package com.mike.lets;


import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.media.ToneGenerator;
import android.util.Log;

import com.mike.lets.vision.DetectionOutput;
import com.mike.lets.textEntry.TextEntryManager;

import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Capa de coordinaciÃƒÂ³n entre la UI y el motor de visiÃƒÂ³n.
 *
 * Esta clase:
 * - recibe cada frame de la cÃƒÂ¡mara
 * - llama a la detecciÃƒÂ³n de mirada
 * - gestiona la calibraciÃƒÂ³n
 * - decide quÃƒÂ© acciÃƒÂ³n ejecutar segÃƒÂºn la mirada detectada
 */
public class Presenter implements ContractInterface.Presenter {
    Context mContext;
    Context applicationContext;
    UserDataManager userDataManager;
    public boolean presenterBusy = false;
    public String mode = "Menu"; // Default to Menu
    private final ContractInterface.View mainView; // creating object of View Interface
    private final ContractInterface.Model model; // creating object of Model Interface
    private final TextEntryManager textEntryManager = new TextEntryManager();
    AppLiveData appliveData = new AppLiveData();
    ToneGenerator toneGenerator;
    private SoundPool soundPool;
    private int selectSoundId;
    private int lastGazeType = 0; // Para evitar repeticiones por frame
    private boolean captureRequested = false;
    private final String[] calibrationMessages = {"Look straight", "Look left and down", "Look right and down", "Look up", "Look left and up", "Look right and up"};
    
    // instantiating the objects of View and Model Interface
    public Presenter(ContractInterface.View mainView, ContractInterface.Model model) {
        this.mainView = mainView;
        this.model = model;
    }
    Mat prevMat;

    private Bitmap matToBitmap(Mat mat) {
        if (mat == null || mat.empty() || mat.cols() <= 0 || mat.rows() <= 0) return null;
        try {
            Bitmap bm = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(mat, bm);
            return bm;
        } catch (Exception e) {
            Log.e("Presenter", "Error in matToBitmap: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void initialize(Context mContext, Context applicationContext) throws IOException {

        Log.d("MVPPresenter", "Model Initialized");
        this.mContext = mContext;
        this.applicationContext = applicationContext;
        userDataManager = (UserDataManager) applicationContext;
        userDataManager.getSettings();

        model.initialize(mContext, applicationContext); // MVP model initialization
        textEntryManager.initialize(mContext, "");

        appliveData.calibrationInstruction = "Eye Calibration";

        toneGenerator = new ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100);

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(5)
                .setAudioAttributes(audioAttributes)
                .build();
        selectSoundId = soundPool.load(mContext, R.raw.select, 1);

        prevMat = new Mat();
    }

    @Override
    public void updateCalibration() {
        int calibrationState = userDataManager.getCalibrationState();
        Log.i("Presenter", "updateCalibration CLICKED. Current state: " + calibrationState);

        if (calibrationState == -1) { // Begin
            appliveData.calibrationInstruction = calibrationMessages[0];
            userDataManager.setCalibrationState(0);
            appliveData.calibrationState = 0;
            Log.i("Presenter", "Calibration started. Instruction: " + calibrationMessages[0]);
        } else if (calibrationState >= 0 && calibrationState < userDataManager.calibrationTemplateNum) {
            // Signal that we want to capture the current step in the next frame
            captureRequested = true;
            Log.i("Presenter", "Capture requested for state: " + calibrationState);
        } else if (calibrationState == userDataManager.calibrationTemplateNum) { // Restart
            appliveData.calibrationInstruction = "EYE CALIBRATION";
            userDataManager.setCalibrationState(-1);
            appliveData.calibrationState = -1;
        }
    }

    private void handleCalibrationCapture(DetectionOutput detectionOutput) {
        if (!captureRequested || detectionOutput == null || detectionOutput.testingMats == null) return;

        Mat[] eyeMats = detectionOutput.testingMats;
        int calibrationState = userDataManager.getCalibrationState();

        // Check if mats are valid (index 0 and 1 are processed eyes)
        if (eyeMats.length >= 2 && eyeMats[0] != null && eyeMats[1] != null && !eyeMats[0].empty() && !eyeMats[1].empty()) {
            Log.d("Presenter", "Capturing calibration frame for state: " + calibrationState);
            
            Bitmap leftBmp = matToBitmap(eyeMats[0]);
            Bitmap rightBmp = matToBitmap(eyeMats[1]);
            
            if (leftBmp != null && rightBmp != null) {
                userDataManager.setLeftCalibrationData(leftBmp, calibrationState);
                userDataManager.setRightCalibrationData(rightBmp, calibrationState);
                
                calibrationState += 1;
                captureRequested = false; // Capture successful
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100);

                if (calibrationState == userDataManager.calibrationTemplateNum) {
                    model.updateCalibrationTemplates();
                    appliveData.calibrationInstruction = "CALIBRATION FINISHED!";
                } else {
                    appliveData.calibrationInstruction = calibrationMessages[calibrationState];
                }
                
                userDataManager.setCalibrationState(calibrationState);
                appliveData.calibrationState = calibrationState;
                Log.i("Presenter", "Calibration advanced to state: " + calibrationState);
            } else {
                Log.e("Presenter", "Failed to create bitmaps for calibration capture");
            }
        } else {
            // Keep captureRequested = true so we try again in the next frame
            String failReason = "";
            if (eyeMats == null) failReason = "eyeMats is null";
            else if (eyeMats.length < 2) failReason = "eyeMats length < 2";
            else if (eyeMats[0] == null || eyeMats[0].empty()) failReason = "Left eye mat empty/null";
            else if (eyeMats[1] == null || eyeMats[1].empty()) failReason = "Right eye mat empty/null";
            
            Log.i("Presenter", "Waiting for valid frame to capture calibration point... Reason: " + failReason);
        }
    }

    @Override
    public boolean getPresenterState() {
        return presenterBusy;
    }

    @Override
    public void setMode(String value) {
        mode = value;
    }

    @Override
    public String getMode() { return mode; }

    @Override
    public void onGazeButtonClicked(int input) { // when the user clicks a gaze button
        if (soundPool != null) {
            soundPool.play(selectSoundId, 1, 1, 0, 0, 1);
        }
        Log.d("Presenter", "Button Pressed: " + input);
        if (Objects.equals(mode, "Menu")) {
            textEntryManager.manageUserInput(input, false);
        }
    }

    @Override
    public void updateContext(String context) {
        textEntryManager.setConversationContext(context);
    }

    @Override
    public void setLlmPrediction(String prediction) {
        textEntryManager.setLlmPrediction(prediction);
    }

    @Override
    public void onDestroy() {
        textEntryManager.release();
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
    }

    @Override
    public void onFrame(Mat rgbMat) {
        // LÃƒÂ³gica principal por frame.
        // 1) pasa el frame al modelo de detecciÃƒÂ³n
        // 2) interpreta la mirada encontrada
        // 3) actualiza la UI
        presenterBusy = true;
        prevMat = rgbMat;
        DetectionOutput detectionOutput = model.classifyGaze(rgbMat); // salida del modelo de mirada

        if (captureRequested) {
            Log.d("Presenter", "onFrame: capture is pending...");
        }
        
        // Handle any pending calibration captures immediately as the model result is ready
        handleCalibrationCapture(detectionOutput);

        if (detectionOutput != null && detectionOutput.AnalyzedData != null) { // when the input is valid
            int gazeType = detectionOutput.gestureOutput;
            
            // Solo procesar si la mirada ha cambiado desde el último frame (evita spam)
            if (gazeType != 0 && gazeType != lastGazeType) { 
                Log.d("IrisDetection", "Gesture Output (New): " + gazeType);
                if (soundPool != null) {
                    soundPool.play(selectSoundId, 1, 1, 0, 0, 1);
                }
                
                if (Objects.equals(mode, "Menu")) {
                    textEntryManager.manageUserInput(gazeType, true);
                }
            }
            lastGazeType = gazeType;
        }

        // setting the app live data for the fragment displays
        appliveData.setDetectionOutput(detectionOutput);
        appliveData.leftTemplates = userDataManager.getLeftCalibrationData(); // templates shown in calibration screen
        appliveData.rightTemplates = userDataManager.getRightCalibrationData();
        
        if (Objects.equals(mode, "Menu")) {
            appliveData.currentText = textEntryManager.getCurrentText();
            List<String> predictions = textEntryManager.getPredictions();
            appliveData.isWordMode = textEntryManager.wordModeUI;
            appliveData.predictionPage = textEntryManager.predictionPage;
            appliveData.predictionsList = predictions;

            if (textEntryManager.justSelectedWord) {
                mainView.clearContext();
                textEntryManager.justSelectedWord = false;
            }

            String llmResponse = textEntryManager.getLlmPrediction();
            if (llmResponse != null && !llmResponse.isEmpty()) {
                appliveData.llmResponse = llmResponse;
            } else if (!predictions.isEmpty()) {
                // Fallback to dictionary predictions if LLM is empty
                int pageSize = appliveData.isWordMode ? 3 : 4;
                int start = appliveData.predictionPage * pageSize;
                StringBuilder sb = new StringBuilder("Next: ");
                for (int i = 0; i < Math.min(predictions.size() - start, 5); i++) {
                    sb.append(predictions.get(start + i)).append(" ");
                }
                appliveData.llmResponse = sb.toString().trim();
            } else {
                appliveData.llmResponse = "";
            }
        }
        
        mainView.updateLiveData(appliveData); // display the gaze data and testing mats

        presenterBusy = false;
    }
}
