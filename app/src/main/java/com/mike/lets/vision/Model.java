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
 * Motor principal de deteccion de mirada:
 *
 * Flujo general:
 * 1) Detecta cara y puntos de los ojos con MediaPipe.
 * 2) Recorta la zona del ojo para cada frame.
 * 3) Compara esa ROI con plantillas calibradas.
 * 4) Combina resultados de ambos ojos para producir una mirada final.
 * 5) Devuelve un DetectionOutput con el tipo de mirada y la acciÃƒÂ³n asociada.
 */
public class Model implements ContractInterface.Model {
    Context mContext, ApplicationContext;
    UserDataManager userDataManager;
    /** Clasificador de cada ojo en base a plantillas de calibracion */
    private final EyeDetection detector = new EyeDetection();
    /** Detecta rostro y landmarks de los ojos. */
    private final MediaPipeFaceDetector faceDetector = new MediaPipeFaceDetector();
    /** Resultado actual de la deteccion de mirada del frame */
    DetectionOutput detectionOutput = new DetectionOutput();
    private ArrayList<String> prevInputs;
    int gazeNum = 8; // number of types of gaze inputs
    int currentGaze = -1;
    int length = 0; /** Karkaf the best <3 */
    int[] gazeCount;
    static int IMAGE_WIDTH = 44, IMAGE_HEIGHT = 18;
    Integer[] tags = new Integer[]{0, 1, 2, 3, 6, 7}; // matches the calibration order
    Point[] corners = new Point[4]; // left, top, right, down
    double[] leftTemplateError, rightTemplateError;
    //.
    @Override
    public void initialize(Context context, Context applicationContext) throws IOException {
        // Inicializa el modelo de vision y las plantillas usadas para comparar la mirada.
        mContext = context;
        ApplicationContext = applicationContext;
        userDataManager = (UserDataManager) applicationContext;

        leftTemplateError = new double[userDataManager.calibrationTemplateNum];
        rightTemplateError = new double[userDataManager.calibrationTemplateNum];

        if (!OpenCVLoader.initDebug()) { // check and initialize OpenCV
            Log.d("OpenCVDebug", "cannot init debug");
        } else {
            Log.d("OpenCVDebug", "success");
        }
        updateCalibrationTemplates();
        prevInputs = new ArrayList<>();
        gazeCount = new int[gazeNum]; // change based on how many detections there are

        faceDetector.initialize(context); // initialize MediaPipe Face Landmarker
    }

    @Override
    public void updateCalibrationTemplates() {
        detector.updateCalibrationTemplates(ApplicationContext,true);
        detector.updateCalibrationTemplates(ApplicationContext, false);
    }

    private boolean gazingLeft(GazeData gaze) {
        return gaze.GazeType == 1 || gaze.GazeType == 6;
    }

    private boolean gazingRight(GazeData gaze) {
        return gaze.GazeType == 2 || gaze.GazeType == 7;
    }

    private static final int DWELL_THRESHOLD = 6; // Frames to confirm the first selection
    private static final int REPEAT_INTERVAL = 18; // Frames between repeated selections if gaze is held

    @Override
    public void analyzeGazeOutput() {
        // Combina los resultados de ambos ojos y decide cuál es la mirada final.
        if (!detectionOutput.LeftData.Success && !detectionOutput.RightData.Success) {
            detectionOutput.AnalyzedData = detectionOutput.LeftData;
        } else if (detectionOutput.LeftData.Success && !detectionOutput.RightData.Success) {
            detectionOutput.AnalyzedData = detectionOutput.LeftData;
        } else if (!detectionOutput.LeftData.Success) {
            detectionOutput.AnalyzedData = detectionOutput.RightData;
        } else {
            GazeData leftGazeData = detectionOutput.LeftData;
            GazeData rightGazeData = detectionOutput.RightData;

            if (leftGazeData.GazeType == 5 && rightGazeData.GazeType == 5) {
                detectionOutput.AnalyzedData = rightGazeData;
            } else if (gazingLeft(leftGazeData) && gazingLeft(rightGazeData)) {
                detectionOutput.AnalyzedData = leftGazeData;
            } else if (gazingRight(leftGazeData) && gazingRight(rightGazeData)) {
                detectionOutput.AnalyzedData = rightGazeData;
            } else {
                int index = -1;
                double minError = 1000000f;
                for (int i = 0; i < userDataManager.calibrationTemplateNum; i++) {
                    double error = leftTemplateError[i] + rightTemplateError[i];
                    if (error < minError) {
                        index = i;
                        minError = error;
                    }
                }
                // Sensitivity threshold for combined MSE
                boolean success = minError <= (detector.sensitivity * 2.5); 
                detectionOutput.setEyeData(2, success, tags[index], 1, (float)minError);
            }
        }

        detectionOutput.gestureOutput = 0;
        if (detectionOutput.AnalyzedData.Success) {
            int type = detectionOutput.AnalyzedData.GazeType;
            if (type != 0) { // Active gaze (not straight)
                if (type == currentGaze) {
                    length += 1;
                    // Trigger on dwell threshold OR on repeat intervals
                    boolean isFirstTrigger = (length == DWELL_THRESHOLD);
                    boolean isRepeatTrigger = (length > DWELL_THRESHOLD && (length - DWELL_THRESHOLD) % REPEAT_INTERVAL == 0);

                    if (isFirstTrigger || isRepeatTrigger) {
                        String gazeTypeStr = detectionOutput.AnalyzedData.getTypeString(currentGaze);
                        if (prevInputs.size() > 25) {
                            prevInputs.clear();
                        }
                        prevInputs.add(gazeTypeStr);
                        detectionOutput.prevInputs = prevInputs;
                        detectionOutput.gestureOutput = type;
                        Log.d("Model", "Gaze Selection Triggered: " + type + " (" + gazeTypeStr + ") at length " + length);
                    }
                } else {
                    currentGaze = type;
                    length = 0;
                }
            } else {
                // Straight gaze resets everything for responsiveness
                currentGaze = 0;
                length = 0;
            }
        } else {
            // Reset if confidence is low
            length = 0;
        }
    }

    /**
     * Genera un rectÃƒÂ¡ngulo alrededor del ojo usando los landmarks del ojo detectado.
     * Ese rectÃƒÂ¡ngulo se usa como ROI para extraer la imagen del ojo y compararla con plantillas.
     */
    private Rect getBoundingBox(List<PointF> points, Mat mat) {

        int INF = 100000;
        int maxX = -1, maxY = -1, minX = INF, minY = INF;
        int maxXIdx = -1, maxYIdx = -1, minXIdx = -1, minYIdx = -1;

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

        // slightly enlarging eye ROI
        maxX += 3;
        maxY += 3;
        minX -= 3;
        minY -= 3;
        // to fix resize issue
        float yRatio = IMAGE_HEIGHT / (float) (maxY - minY);
        float xRatio = IMAGE_WIDTH / (float) (maxX - minX);

        if (maxXIdx == -1 || maxYIdx == -1 || minXIdx == -1 || minYIdx == -1) {
            return null;
        }

        // getting all 4 corners of the eye (in relation to the ROI)
        corners[0] = new Point((maxX - points.get(minXIdx).x) * xRatio, (maxY - points.get(minXIdx).y) * yRatio);
        corners[1] = new Point((maxX - points.get(minYIdx).x) * xRatio, (maxY - points.get(minYIdx).y) * yRatio);
        corners[2] = new Point((maxX - points.get(maxXIdx).x) * xRatio, (maxY - points.get(maxXIdx).y) * yRatio);
        corners[3] = new Point((maxX - points.get(maxYIdx).x) * xRatio, (maxY - points.get(maxYIdx).y) * yRatio);

        Log.d("CornerDetection", corners[0].x + " " + corners[1].x + " " + corners[2].x + " " + corners[3].x);
        Log.d("CornerDetection", "Max: " + minX + " " + minY + " " + maxX + " " + maxY);
        Rect boundingBox;
        if (minX >= 0 && minY >= 0 && maxX < mat.cols() && maxY < mat.rows() && minX < maxX && minY < maxY) { // contour is valid
            boundingBox = new Rect(new Point(minX, minY), new Point(maxX, maxY));
            return boundingBox;
        } else {
            return null;
        }
    }

    /**
     * Normaliza la posiciÃƒÂ³n del iris respecto a las esquinas del ojo.
     * Esto permite comparar la direcciÃƒÂ³n de la mirada de forma mÃƒÂ¡s estable.
     */
    private Point normalizeIrisCenter(Point irisCenter) {

        double normalizedX = (irisCenter.x - corners[0].x) / (corners[2].x - corners[0].x); // coordenada X normalizada
        double normalizedY = (irisCenter.y - corners[1].y) / (corners[3].y - corners[1].y); // coordenada Y normalizada
        return new Point(normalizedX, normalizedY);
    }

    /**
     * Obtiene el centro del iris y lo normaliza como NIC (Normalized Iris Center).
     * Sirve para anÃƒÂ¡lisis extra de direcciÃƒÂ³n de mirada y debugging visual.
     */
    private Point getIrisCenter(Mat eye) {
        Point normalized = new Point();
        if (eye != null) {
            Point irisCenter = detector.irisDetection(eye);
            Mat irisMat = detector.finalMat;
            for (int i = 0; i < 4; i++) { // testing
                Imgproc.circle(irisMat, corners[i], 2, new Scalar(255,255,255));
            }
            normalized = normalizeIrisCenter(irisCenter);
            detectionOutput.testingMats[2] = detector.opening;
        } else {
            detectionOutput.testingMats[2] = new Mat();
        }
        return normalized;
    }

    @Override
    public DetectionOutput classifyGaze(Mat rgbMat) { @OptIn(markerClass = ExperimentalGetImage.class)
        // Punto de entrada principal para cada frame de cÃƒÂ¡mara.
        // Devuelve un DetectionOutput con la mirada clasificada para ese frame.

        Mat leftEye = null, rightEye = null;
        Bitmap bmp;

        // Convierte el frame OpenCV a Bitmap para que MediaPipe pueda analizarlo.
        Mat rgbaMat = new Mat(rgbMat.rows(), rgbMat.cols(), CvType.CV_8UC4);
        Imgproc.cvtColor(rgbMat, rgbaMat, Imgproc.COLOR_BGR2RGBA);
        bmp = Bitmap.createBitmap(rgbaMat.cols(), rgbaMat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(rgbaMat, bmp);
        faceDetector.detect(bmp);

        // initialization
        detectionOutput.initialize(4);
        
        if (faceDetector.leftEyeContour == null && faceDetector.rightEyeContour == null) { // no eye detection
            return detectionOutput;
        
        } else if (faceDetector.leftEyeOpenProb <= 0.1 && faceDetector.rightEyeOpenProb <= 0.1) { // check if eyes are closed
            detectionOutput.setEyeData(0, true, 5, 1, faceDetector.leftEyeOpenProb);
            detectionOutput.setEyeData(1, true, 5, 1, faceDetector.rightEyeOpenProb);
        } else {
            if (faceDetector.leftEyeContour != null) { // left eye available

                List<PointF> leftEyePoints = faceDetector.leftEyeContour;
                Rect leftEyeBound = getBoundingBox(leftEyePoints, rgbMat);

                //Rect leftEyeBound = getSurroundBox(faceDetector.leftEyePos, rgbMat);
                if (leftEyeBound != null) {
                  //  Log.d("MVPModel", "Rect Dimensions: " + leftEyeBound.x + ' ' + leftEyeBound.y + ' ' + leftEyeBound.height + ' ' + leftEyeBound.width);
                    leftEye = new Mat(rgbMat, leftEyeBound);
                    
                    // Store high-res eye for UI display
                    Mat highResEye = new Mat();
                    leftEye.copyTo(highResEye);
                    detectionOutput.testingMats[3] = highResEye;

                    Log.d("MVPModel", "Image Dimensions: " + leftEye.cols() + " " + leftEye.rows());

                    // image processing
                    Imgproc.resize(leftEye, leftEye, new Size(IMAGE_WIDTH, IMAGE_HEIGHT), Imgproc.INTER_LINEAR);
                    Imgproc.cvtColor(leftEye, leftEye, Imgproc.COLOR_RGB2GRAY);
                    //Imgproc.equalizeHist(leftEye, leftEye);

                    if (userDataManager.checkCalibrationFiles()) { // true = calibration complete
                        leftTemplateError = detector.runEyeModel(detectionOutput, leftEye, 0);
                    }
                }
            }
            if (faceDetector.rightEyeContour != null) { // right eye available

                List<PointF> rightEyePoints = faceDetector.rightEyeContour;
                Rect rightEyeBound = getBoundingBox(rightEyePoints, rgbMat);

                //Rect rightEyeBound = getSurroundBox(faceDetector.rightEyePos, rgbMat);
                if (rightEyeBound != null) {
                  //  Log.d("MVPModel", "Rect Dimensions: " + rightEyeBound.x + ' ' + rightEyeBound.y + ' ' + rightEyeBound.height + ' ' + rightEyeBound.width);
                    rightEye = new Mat(rgbMat, rightEyeBound);

                    // image processing
                    Imgproc.resize(rightEye, rightEye, new Size(IMAGE_WIDTH, IMAGE_HEIGHT), Imgproc.INTER_LINEAR);
                    Imgproc.cvtColor(rightEye, rightEye, Imgproc.COLOR_RGB2GRAY);
                    //Imgproc.equalizeHist(rightEye, rightEye);

                    if (userDataManager.checkCalibrationFiles()) { // if there are calibration images
                        rightTemplateError = detector.runEyeModel(detectionOutput, rightEye, 1);
                    }
                }
            }
        }

        //testing data
        detectionOutput.testingMats[0] = leftEye;
        detectionOutput.testingMats[1] = rightEye;
        analyzeGazeOutput(); // analyze the output before returning to the presenter

        // NIC detection
        detectionOutput.leftNIC = getIrisCenter(leftEye);
        Log.d("IrisDetection", "Normalized x = " + detectionOutput.leftNIC.x + ", y = " + detectionOutput.leftNIC.y);
        return detectionOutput;
    }
}

