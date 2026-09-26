package com.mike.lets.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;

import com.mike.lets.ContractInterface;
import com.mike.lets.UserDataManager;
import com.mike.lets.vision.DetectionOutput;
import com.mike.lets.vision.EyeDetection;
import com.mike.lets.vision.GazeData;
import com.mike.lets.vision.MediaPipeFaceDetector;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


/**
 * Motor principal de detección de mirada:
 *
 * Flujo general:
 * 1) Detecta cara y puntos de los ojos con MediaPipe.
 * 2) Recorta la zona del ojo para cada frame.
 * 3) Compara esa ROI con plantillas calibradas.
 * 4) Combina resultados de ambos ojos para producir una mirada final.
 * 5) Devuelve un DetectionOutput con el tipo de mirada y la acción asociada.
 */
public class Model implements ContractInterface.Model {
    // Contextos para acceder a recursos y servicios del siste----ma Android
    Context mContext, ApplicationContext;
    
    // Administrador de datos de usuario (almacena calibración, sensibilidad y preferencias)
    UserDataManager userDataManager;
    
    /** Clasificador de cada ojo en base a plantillas de calibración */
    private final EyeDetection detector = new EyeDetection();
    
    /** Detecta rostro y landmarks (puntos de referencia) de los ojos con MediaPipe */
    private final MediaPipeFaceDetector faceDetector = new MediaPipeFaceDetector();
    
    /** Resultado actual de la detección de mirada del frame */
    DetectionOutput detectionOutput = new DetectionOutput();
    
    // Historial de las últimas entradas de mirada confirmadas (máximo 25 entradas)
    private ArrayList<String> prevInputs;
    
    // Número total de tipos de entradas/clasificaciones de mirada soportadas (0 a 7)
    int gazeNum = 8;
    
    // ID del tipo de mirada que se está manteniendo en el frame actual (-1 = no inicializado)
    int currentGaze = -1;
    
    // Contador de frames consecutivos en los que se ha mantenido la mirada activa actual
    int length = 0;
    
    // Arreglo auxiliar para conteo de ocurrencias de cada tipo de mirada
    int[] gazeCount;
    
    // Dimensiones estandarizadas (44x18 píxeles) a las que se ajustan las imágenes de los ojos (ROI)
    static int IMAGE_WIDTH = 44, IMAGE_HEIGHT = 18;
    
    // Mapeo entre el índice de las plantillas calibradas y el ID del tipo de mirada
    Integer[] tags = new Integer[]{0, 1, 2, 3, 6, 7, 5}; 
    
    // Puntos de las 4 esquinas del ojo recortado: 0=izquierda, 1=arriba, 2=derecha, 3=abajo
    Point[] corners = new Point[4]; 
    
    // Arreglos que guardan los errores cuadráticos medios (MSE) al comparar la ROI actual con las plantillas
    double[] leftTemplateError, rightTemplateError;
    
    // Bitmap reutilizable para conversión de imagen de OpenCV a MediaPipe sin saturar la memoria
    private Bitmap reusableBitmap;
    
    // --- LÓGICA Y TIEMPOS DE COOLDOWN PARA GUIÑO / PARPADEO ---
    // Contador de frames consecutivos con detección de guiño activo
    private int winkLength = 0;
    
    // COOLDOWN DE GUIÑO: Requiere 2 frames consecutivos (~66 ms a 30 FPS) manteniendo el guiño para confirmarlo
    private static final int WINK_DWELL_THRESHOLD = 2; 
    
    // Umbral de puntuación de MediaPipe Blendshapes (50%) para determinar si un ojo está cerrado
    private static final float WINK_SCORE_THRESHOLD = 0.50f;

    // --- TIEMPOS Y COOLDOWNS GENERALES DE MIRADA ---
    // COOLDOWN DE ACTIVACIÓN INICIAL (DWELL): Requiere sostener la mirada 6 frames consecutivos (~200 ms a 30 FPS)
    private static final int DWELL_THRESHOLD = 6; 
    
    // COOLDOWN DE REPETICIÓN (REPEAT INTERVAL): Si se mantiene la mirada fija, spamea/repite la acción cada 18 frames (~600 ms a 30 FPS)
    private static final int REPEAT_INTERVAL = 18; 

    @Override
    public void initialize(Context context, Context applicationContext) throws IOException {
        // Asigna el contexto de la actividad/pantalla
        mContext = context;
        // Asigna el contexto global de la aplicación
        ApplicationContext = applicationContext;
        // Asigna el administrador de datos compartidos de la app
        userDataManager = (UserDataManager) applicationContext;

        // Asigna espacio en memoria para los errores de plantillas según el número de plantillas del usuario
        leftTemplateError = new double[userDataManager.calibrationTemplateNum];
        rightTemplateError = new double[userDataManager.calibrationTemplateNum];

        // Inicializa la librería nativa de OpenCV
        if (!OpenCVLoader.initDebug()) { 
            Log.d("OpenCVDebug", "cannot init debug");
        } else {
            Log.d("OpenCVDebug", "success");
        }
        
        // Carga desde disco las plantillas de calibración guardadas
        updateCalibrationTemplates();
        
        // Inicializa la lista del historial de entradas
        prevInputs = new ArrayList<>();
        
        // Inicializa el arreglo de conteo de miradas
        gazeCount = new int[gazeNum]; 

        // Inicializa el detector facial de MediaPipe
        faceDetector.initialize(context); 
        
        // Sincroniza la sensibilidad de detección desde los ajustes del usuario (convertido de escala 0-1000 a decimal)
        detector.sensitivity = userDataManager.getSensitivity() / 1000.0f;
    }

    @Override
    public void updateCalibrationTemplates() {
        // Carga/actualiza plantillas de calibración para el ojo izquierdo
        detector.updateCalibrationTemplates(ApplicationContext, true);
        // Carga/actualiza plantillas de calibración para el ojo derecho
        detector.updateCalibrationTemplates(ApplicationContext, false);
    }

    // Retorna true si la mirada evaluada corresponde a dirección izquierda (Tipo 1 o Tipo 6)
    private boolean gazingLeft(GazeData gaze) {
        return gaze.GazeType == 1 || gaze.GazeType == 6;
    }

    // Retorna true si la mirada evaluada corresponde a dirección derecha (Tipo 2 o Tipo 7)
    private boolean gazingRight(GazeData gaze) {
        return gaze.GazeType == 2 || gaze.GazeType == 7;
    }

    @Override
    public void analyzeGazeOutput() {
        // Analiza y fusiona la salida de ambos ojos para la variable miembro actual
        analyzeFrameGaze(this.detectionOutput);
    }

    private void analyzeFrameGaze(DetectionOutput output) {
        // Validación de seguridad: si no existen datos procesados de algún ojo, cancela el análisis
        if (output.LeftData == null || output.RightData == null) return;
        
        // Evaluación y combinación de resultados de ambos ojos
        if (!output.LeftData.Success && !output.RightData.Success) {
            // Si ambos fallaron, usa el izquierdo por defecto
            output.AnalyzedData = output.LeftData;
        } else if (output.LeftData.Success && !output.RightData.Success) {
            // Si solo el izquierdo fue exitoso, usa el izquierdo
            output.AnalyzedData = output.LeftData;
        } else if (!output.LeftData.Success) {
            // Si solo el derecho fue exitoso, usa el derecho
            output.AnalyzedData = output.RightData;
        } else {
            // Si ambos ojos tuvieron detección exitosa, evalúa coincidencia
            GazeData leftGazeData = output.LeftData;
            GazeData rightGazeData = output.RightData;

            if (leftGazeData.GazeType == 5 && rightGazeData.GazeType == 5) {
                // Si ambos ojos detectaron guiño/cambio (Tipo 5), selecciona el ojo derecho
                output.AnalyzedData = rightGazeData;
            } else if (gazingLeft(leftGazeData) && gazingLeft(rightGazeData)) {
                // Si ambos ojos coinciden en mirar a la izquierda, selecciona el ojo izquierdo
                output.AnalyzedData = leftGazeData;
            } else if (gazingRight(leftGazeData) && gazingRight(rightGazeData)) {
                // Si ambos ojos coinciden en mirar a la derecha, selecciona el ojo derecho
                output.AnalyzedData = rightGazeData;
            } else {
                // Si los ojos no coinciden en clasificación directa, busca la plantilla con menor error cuadrático combinado
                int minIndex = -1;
                double minError = 1000000f;
                for (int i = 0; i < userDataManager.calibrationTemplateNum; i++) {
                    double error = leftTemplateError[i] + rightTemplateError[i];
                    if (error < minError) {
                        minIndex = i;
                        minError = error;
                    }
                }
                // Compara el error mínimo contra el umbral de sensibilidad de detección permitido
                boolean success = minError <= (detector.sensitivity * 2.5); 
                // Asigna la mirada combinada resultante
                output.setEyeData(2, success, tags[minIndex], 1, (float)minError);
            }
        }

        // Reinicia la salida del gesto producido en este frame
        output.gestureOutput = 0;
        
        // Si hay una mirada analizada válida y confiable:
        if (output.AnalyzedData != null && output.AnalyzedData.Success) {
            int type = output.AnalyzedData.GazeType;
            
            if (type != 0) { 
                // Mirada o gesto activo (distinto de mirada al centro 0)
                if (type == currentGaze) {
                    // Si se mantiene la misma dirección del frame anterior, incrementa el contador de frames
                    length += 1;
                    
                    // Condición 1: Primera activación (ocurre exactamente a los DWELL_THRESHOLD = 6 frames, ~200 ms)
                    boolean isFirstTrigger = (length == DWELL_THRESHOLD);
                    
                    // Condición 2: Repetición continua (ocurre cada REPEAT_INTERVAL = 18 frames, ~600 ms tras el primer trigger)
                    boolean isRepeatTrigger = (length > DWELL_THRESHOLD && (length - DWELL_THRESHOLD) % REPEAT_INTERVAL == 0);

                    // Si se cumple la activación inicial o la repetición por cooldown:
                    if (isFirstTrigger || isRepeatTrigger) {
                        String gazeTypeStr = output.AnalyzedData.getTypeString(currentGaze);
                        // Mantiene el tamaño del historial controlado (máximo 25 elementos)
                        if (prevInputs.size() > 25) {
                            prevInputs.clear();
                        }
                        // Añade el nombre del tipo de mirada al historial de entradas
                        prevInputs.add(gazeTypeStr);
                        output.prevInputs = prevInputs;
                        // Emite el tipo de gesto para que la UI realice la acción
                        output.gestureOutput = type;
                        Log.d("Model", "Gaze Selection Triggered: " + type + " (" + gazeTypeStr + ") at length " + length);
                    }
                } else {
                    // Si el usuario cambió la dirección de mirada, cambia el objetivo y reinicia el contador de frames
                    currentGaze = type;
                    length = 0;
                }
            } else {
                // Mirada fija al frente/centro (Tipo 0) -> Reinicia contadores para máxima respuesta
                currentGaze = 0;
                length = 0;
            }
        } else {
            // Si la detección no fue exitosa o perdió confianza -> Reinicia el contador de permanencia
            length = 0;
        }
    }

    /**
     * Calcula la caja delimitadora (Bounding Box) alrededor del ojo utilizando sus landmarks faciales.
     * Retorna una región de interés (ROI) para recortar la imagen del ojo.
     */
    private Rect getBoundingBox(List<PointF> points, Mat mat) {

        int INF = 100000;
        int maxX = -1, maxY = -1, minX = INF, minY = INF;
        int maxXIdx = -1, maxYIdx = -1, minXIdx = -1, minYIdx = -1;

        // Recorre los puntos del ojo para hallar los límites extremos (mínimo y máximo X, Y)
        for (int i = 0; i < points.size(); i++) {
            if ((int) points.get(i).x > maxX) {
                maxX = (int) points.get(i).x;
                maxXIdx = i;
            }
            if ((int) points.get(i).y > maxY) {
                maxY = (int) points.get(i).y;
                maxYIdx = i;
            }
            if ((int) points.get(i).x < minX) {
                minX = (int) points.get(i).x;
                minXIdx = i;
            }
            if ((int) points.get(i).y < minY) {
                minY = (int) points.get(i).y;
                minYIdx = i;
            }
        }

        // Añade un pequeño margen de 3 píxeles alrededor del ojo
        maxX += 3;
        maxY += 3;
        minX -= 3;
        minY -= 3;
        
        // Calcula factores de escala hacia las dimensiones destino (44x18)
        float yRatio = IMAGE_HEIGHT / (float) (maxY - minY);
        float xRatio = IMAGE_WIDTH / (float) (maxX - minX);

        if (maxXIdx == -1 || maxYIdx == -1 || minXIdx == -1 || minYIdx == -1) {
            return null;
        }

        // Calcula la posición relativa de los 4 extremos del ojo (esquinas) escalados
        corners[0] = new Point((maxX - points.get(minXIdx).x) * xRatio, (maxY - points.get(minXIdx).y) * yRatio);
        corners[1] = new Point((maxX - points.get(minYIdx).x) * xRatio, (maxY - points.get(minYIdx).y) * yRatio);
        corners[2] = new Point((maxX - points.get(maxXIdx).x) * xRatio, (maxY - points.get(maxXIdx).y) * yRatio);
        corners[3] = new Point((maxX - points.get(maxYIdx).x) * xRatio, (maxY - points.get(maxYIdx).y) * yRatio);

        Rect boundingBox;
        // Valida que el rectángulo esté completamente dentro de los bordes de la imagen
        if (minX >= 0 && minY >= 0 && maxX < mat.cols() && maxY < mat.rows() && minX < maxX && minY < maxY) { 
            boundingBox = new Rect(new Point(minX, minY), new Point(maxX, maxY));
            return boundingBox;
        } else {
            return null;
        }
    }

    /**
     * Normaliza las coordenadas del centro del iris en relación con las 4 esquinas del ojo.
     * Retorna un punto donde X e Y están expresados proporcionalmente (0.0 a 1.0).
     */
    private Point normalizeIrisCenter(Point irisCenter) {

        double normalizedX = (irisCenter.x - corners[0].x) / (corners[2].x - corners[0].x); 
        double normalizedY = (irisCenter.y - corners[1].y) / (corners[3].y - corners[1].y); 
        return new Point(normalizedX, normalizedY);
    }

    /**
     * Calcula la posición del iris dentro del ojo recortado y obtiene su versión normalizada (NIC).
     * Dibuja marcas en las esquinas para inspección de depuración.
     */
    private Point getIrisCenter(Mat eye, DetectionOutput output) {
        Point normalized = new Point();
        if (eye != null && !eye.empty()) {
            // Localiza el centro del iris mediante segmentación en EyeDetection
            Point irisCenter = detector.irisDetection(eye);
            Mat irisMat = detector.finalMat;
            if (irisMat != null) {
                // Dibuja puntos blancos de referencia en las 4 esquinas
                for (int i = 0; i < 4; i++) { 
                    Imgproc.circle(irisMat, corners[i], 2, new Scalar(255,255,255));
                }
            }
            // Normaliza el centro del iris
            normalized = normalizeIrisCenter(irisCenter);
            output.testingMats[2] = detector.opening;
        } else {
            output.testingMats[2] = new Mat();
        }
        return normalized;
    }

    @Override
    public DetectionOutput classifyGaze(Mat rgbaMat) { @OptIn(markerClass = ExperimentalGetImage.class)
        // PUNTO DE ENTRADA PRINCIPAL: Recibe un frame en tiempo real desde la cámara (RGBA Mat).
        
        Mat leftEye = null, rightEye = null;

        // Instancia un nuevo objeto de salida independiente para este frame
        DetectionOutput frameOutput = new DetectionOutput();
        frameOutput.initialize(4);
        
        // Gestiona el bitmap reutilizable para evitar allocation masivo de memoria por cada frame
        if (reusableBitmap == null || reusableBitmap.getWidth() != rgbaMat.cols() || reusableBitmap.getHeight() != rgbaMat.rows()) {
            if (reusableBitmap != null) reusableBitmap.recycle();
            reusableBitmap = Bitmap.createBitmap(rgbaMat.cols(), rgbaMat.rows(), Bitmap.Config.ARGB_8888);
        }
        
        // Convierte la matriz OpenCV a Bitmap
        Utils.matToBitmap(rgbaMat, reusableBitmap);
        
        // Ejecuta la detección facial y cálculo de landmarks con MediaPipe
        faceDetector.detect(reusableBitmap);

        // Extrae las puntuaciones de parpadeo/guiño obtenidas por MediaPipe Blendshapes
        float leftBlink = faceDetector.leftEyeBlinkScore;
        float rightBlink = faceDetector.rightEyeBlinkScore;

        // Determina si cada ojo individual está cerrado o abierto
        boolean leftClosed = leftBlink > WINK_SCORE_THRESHOLD;
        boolean rightClosed = rightBlink > WINK_SCORE_THRESHOLD;
        boolean leftOpen = leftBlink < 0.30f;
        boolean rightOpen = rightBlink < 0.30f;

        // EVALUACIÓN DE GUIÑO VS PARPADEO COMPLETO
        if ((leftClosed && rightOpen) || (rightClosed && leftOpen)) { 
            // Si un ojo está cerrado y el otro está abierto -> Guiño (Tipo 5: Cambiar)
            winkLength++;
            // Verifica el tiempo de permanencia del guiño (2 frames = ~66 ms)
            if (winkLength >= WINK_DWELL_THRESHOLD) {
                frameOutput.setEyeData(0, true, 5, 1, leftBlink);
                frameOutput.setEyeData(1, true, 5, 1, rightBlink);
            }
        } else if (leftClosed && rightClosed) { 
            // Si ambos ojos están cerrados -> Parpadeo natural (Reinicia contador e ignora)
            winkLength = 0;
            frameOutput.setEyeData(0, false, 0, 0, 0);
            frameOutput.setEyeData(1, false, 0, 0, 0);
        } else {
            // Ojos abiertos normalmente -> Proceder con clasificación por comparación de plantillas
            winkLength = 0;
            
            // PROCESAMIENTO DEL OJO IZQUIERDO
            if (faceDetector.leftEyeContour != null) { 
                List<PointF> leftEyePoints = faceDetector.leftEyeContour;
                Rect leftEyeBound = getBoundingBox(leftEyePoints, rgbaMat);

                if (leftEyeBound != null) {
                    // Recorta la ROI del ojo izquierdo de la imagen original
                    leftEye = new Mat(rgbaMat, leftEyeBound);
                    
                    Mat highResEye = new Mat();
                    leftEye.copyTo(highResEye);
                    frameOutput.testingMats[3] = highResEye;

                    // Convierte la ROI a escala de grises
                    Mat grayEye = new Mat();
                    Imgproc.cvtColor(leftEye, grayEye, Imgproc.COLOR_RGBA2GRAY);
                    
                    // Si hay plantillas calibradas guardadas, calcula el error de coincidencia
                    if (userDataManager.checkCalibrationFiles()) {
                        leftTemplateError = detector.runEyeModel(frameOutput, grayEye, 0);
                    }
                    
                    frameOutput.testingMats[0] = grayEye;
                }
            }
            
            // PROCESAMIENTO DEL OJO DERECHO
            if (faceDetector.rightEyeContour != null) { 
                List<PointF> rightEyePoints = faceDetector.rightEyeContour;
                Rect rightEyeBound = getBoundingBox(rightEyePoints, rgbaMat);

                if (rightEyeBound != null) {
                    // Recorta la ROI del ojo derecho de la imagen original
                    rightEye = new Mat(rgbaMat, rightEyeBound);
                    
                    // Convierte la ROI a escala de grises
                    Mat grayEye = new Mat();
                    Imgproc.cvtColor(rightEye, grayEye, Imgproc.COLOR_RGBA2GRAY);

                    // Si hay plantillas calibradas guardadas, calcula el error de coincidencia
                    if (userDataManager.checkCalibrationFiles()) {
                        rightTemplateError = detector.runEyeModel(frameOutput, grayEye, 1);
                    }
                    
                    frameOutput.testingMats[1] = grayEye;
                }
            }
        }

        // Calcula el centro normalizado del iris (NIC)
        frameOutput.leftNIC = getIrisCenter(leftEye, frameOutput);
        
        // Fusiona resultados, evalúa tiempos de permanencia y produce la mirada/gesto final
        analyzeFrameGaze(frameOutput);
        
        // Libera la memoria nativa de OpenCV asignada a las matrices de los ojos
        if (leftEye != null) leftEye.release();
        if (rightEye != null) rightEye.release();
        
        // Retorna el resultado del análisis para el frame actual
        return frameOutput;
    }

}
