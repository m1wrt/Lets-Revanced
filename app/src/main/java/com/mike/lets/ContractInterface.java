package com.mike.lets;
import android.content.Context;
/** FORCE REWRITE WITHOUT BOM */
import com.mike.lets.vision.DetectionOutput;

import org.json.JSONException;
import org.opencv.core.Mat;

import java.io.IOException;

/**
 * Contratos entre capas de la app.
 *
 * Esta interfaz define cÃ³mo se comunica:
 * - View (UI)
 * - Model (detecciÃ³n de mirada)
 * - Presenter (coordina todo)
 */
public interface ContractInterface {
    interface View {
        void updateLiveData(AppLiveData appLiveData);
        void openSettings();
        void openCalibration();
    }

    interface Model {
        void analyzeGazeOutput(); // analiza la mirada resultante de ambos ojos
        void initialize(Context context, Context applicationContext) throws IOException; // inicializa OpenCV, MediaPipe y plantillas
        void updateCalibrationTemplates(); // recarga las plantillas calibradas
        DetectionOutput classifyGaze(Mat rgbMat); // devuelve el resultado de mirada para un frame
    }

    interface Presenter {
        void onDestroy(); // limpia el ciclo de vida de la actividad
        void onFrame(Mat rgbMat); // procesa un frame y decide la acciÃ³n correspondiente
        void initialize(Context context, Context applicationContext) throws IOException, JSONException; // crea la conexiÃ³n entre UI y modelo
        void updateCalibration(); // gestiona la calibraciÃ³n por pasos
        boolean getPresenterState();
        void setMode(String value); // cambia el modo: texto, cÃ¡mara, social, etc.
        String getMode();
        void onGazeButtonClicked(int input); // acciÃ³n cuando el usuario pulsa una opciÃ³n de mirada
    }
}
