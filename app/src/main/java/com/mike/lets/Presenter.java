package com.mike.lets;


import android.content.Context;
import android.graphics.Bitmap;
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
    private int lastGazeType = 0; // Para evitar repeticiones por frame
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

        model.initialize(mContext, applicationContext); // MVP model initialization
        textEntryManager.initialize(mContext, "");

        appliveData.calibrationInstruction = "Eye Calibration";

        toneGenerator = new ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100);

        prevMat = new Mat();
    }

    @Override
    public void updateCalibration() {
        Log.d("Presenter", "updateCalibration called. Current state: " + userDataManager.getCalibrationState());
        // Ejecuta el proceso de calibraciÃƒÂ³n del usuario.
        // Se pide mirar a distintas direcciones y se toman fotos del ojo para generar plantillas.
        String[] calibrationMessages = {"Look straight", "Look left and down", "Look right and down", "Look up", "Look left and up", "Look right and up"};

        int calibrationState = userDataManager.getCalibrationState();

        if (calibrationState == -1) { // begin calibration
            // audioManager.speakText(calibrationMessages[0]); // Stubbed
            appliveData.calibrationInstruction = calibrationMessages[0];
            calibrationState = 0;

        } else if (calibrationState == userDataManager.calibrationTemplateNum) { // restart calibration
            appliveData.calibrationInstruction = "EYE CALIBRATION";
            calibrationState = -1;

        } else if (calibrationState >= 0) { // during calibration
            if (appliveData.DetectionOutput != null && appliveData.DetectionOutput.testingMats != null) {
                Mat[] eyeMats = appliveData.DetectionOutput.testingMats;
                
                // Detailed debug info
                Log.d("Calibration", "Mats length: " + eyeMats.length);
                for(int i=0; i<eyeMats.length; i++) {
                    Log.d("Calibration", "Mat " + i + " is " + (eyeMats[i] == null ? "null" : (eyeMats[i].empty() ? "empty" : "valid")));
                }

                if (eyeMats.length >= 2 && eyeMats[0] != null && eyeMats[1] != null && !eyeMats[0].empty() && !eyeMats[1].empty()) { // the images are collectible
                    Log.d("CalibrationInterface", "Recorded successfully. State: " + calibrationState);
                    
                    Bitmap leftBmp = matToBitmap(eyeMats[0]);
                    Bitmap rightBmp = matToBitmap(eyeMats[1]);
                    
                    if (leftBmp != null && rightBmp != null) {
                        userDataManager.setLeftCalibrationData(leftBmp, calibrationState); // collect left frame
                        userDataManager.setRightCalibrationData(rightBmp, calibrationState); // collect right frame
                        calibrationState += 1;
                        
                        Log.d("Calibration", "Saved frame " + (calibrationState-1));

                        if (calibrationState == userDataManager.calibrationTemplateNum) { // finished calibration
                            model.updateCalibrationTemplates();
                            appliveData.calibrationInstruction = "CALIBRATION FINISHED!";
                            Log.d("CalibrationInterface", "Finished calibration");
                        } else { // continue
                            // audioManager.speakText(calibrationMessages[calibrationState]); // Stubbed
                            appliveData.calibrationInstruction = calibrationMessages[calibrationState];
                        }
                    } else {
                        Log.e("Calibration", "Capture failed: Bitmaps were null");
                        appliveData.calibrationInstruction = "RETRY: " + calibrationMessages[calibrationState];
                    }
                } else {
                    Log.d("Calibration", "Detection failed, please try again (mats might be empty or null)");
                }
            } else {
                Log.d("Calibration", "Detection failed, no DetectionOutput yet");
            }
        }
        userDataManager.setCalibrationState(calibrationState);
        appliveData.calibrationState = calibrationState;
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
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_PIP, 150);
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

        if (detectionOutput != null && detectionOutput.AnalyzedData != null) { // when the input is valid
            int gazeType = detectionOutput.gestureOutput;
            
            // Solo procesar si la mirada ha cambiado desde el último frame (evita spam)
            if (gazeType != 0 && gazeType != lastGazeType) { 
                Log.d("IrisDetection", "Gesture Output (New): " + gazeType);
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_PIP, 150);
                
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
