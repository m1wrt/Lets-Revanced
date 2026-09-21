package com.mike.lets.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;
import java.util.ArrayList;

import com.mike.lets.UserDataManager;

/**
 * Clasifica la mirada de cada ojo comparando la ROI del ojo con plantillas calibradas.
 *
 * Es la capa que transforma una imagen de ojo en un tipo de mirada: izquierda, derecha,
 * arriba, abajo, cerrada, etc.
 */
public class EyeDetection {
    UserDataManager userDataManager;
    public Mat finalMat, opening;
    Integer[] tags = new Integer[]{0, 1, 2, 3, 6, 7}; // matches the calibration order to the gaze data order
    Mat[] leftTemplates, rightTemplates;
    public float thresholdValue = 18f;
    public float sensitivity = 0.015f;
    static int IMAGE_WIDTH = 40, IMAGE_HEIGHT = 14;
    
    private final Mat diffMat = new Mat();
    private final Mat norB = new Mat();
    
    /**
     * Carga las imÃƒÂ¡genes de calibraciÃƒÂ³n del usuario para cada ojo.
     * Estas plantillas sirven como referencia para comparar la mirada actual.
     */
    public void updateCalibrationTemplates(Context applicationContext, boolean left) {
        userDataManager = (UserDataManager) applicationContext;
        Mat[] templates = new Mat[userDataManager.calibrationTemplateNum];
        Bitmap[] bm;
        if (left) {
            bm = userDataManager.getLeftCalibrationData();
        } else {
            bm = userDataManager.getRightCalibrationData();
        }
        if (bm == null) { // calibration incomplete
            Log.d("CalibrationInterface", "Calibration Missing");
            return;
        }
        for (int i = 0; i < userDataManager.calibrationTemplateNum; i++) {
            if (bm[i] == null) {
                Log.d("CalibrationInterface", "Skipped calibration number = " + i);
                continue; // one frame incomplete, skip
            }
            Log.d("CalibrationInterface", "Recorded = " + i);
            Mat temp = new Mat();
            Utils.bitmapToMat(bm[i], temp);
            Imgproc.cvtColor(temp, temp, Imgproc.COLOR_BGR2GRAY);
            Imgproc.resize(temp, temp, new Size(IMAGE_WIDTH, IMAGE_HEIGHT), Imgproc.INTER_AREA);
            
            // Pre-convert to 32F and normalize for faster MSE calculation
            Mat template32F = new Mat();
            temp.convertTo(template32F, CvType.CV_32F, 1.0/255.0);
            templates[i] = template32F;
            temp.release();
        }
        if (left) {
            leftTemplates = templates;
        } else {
            rightTemplates = templates;
        }
    }

    private double mse(Mat template32F, Mat current32F, int h, int w) {
        Core.subtract(template32F, current32F, diffMat);
        // We use Core.norm or Core.sumElems for MSE
        // MSE = sum(diff^2) / N
        // In OpenCV, we can use norm with NORM_L2SQR
        double normSq = Core.norm(diffMat, Core.NORM_L2SQR);
        return normSq / (h * w);
    }

    /**
     * Compara el ojo actual con todas las plantillas calibradas y devuelve la mejor coincidencia.
     *
     * @param detectionOutput resultado global del frame
     * @param eyeROI imagen del ojo ya recortada y normalizada (esperada en escala de grises)
     * @param type 0 = ojo izquierdo, 1 = ojo derecho
     */
    public double[] runEyeModel(DetectionOutput detectionOutput, Mat eyeROI, int type) {
        Mat[] compareTemplates;
        double[] templateError = new double[userDataManager.calibrationTemplateNum];

        // Prepare current eye ROI for MSE comparison
        Mat tensorMat = new Mat();
        if (eyeROI.channels() > 1) {
            Imgproc.cvtColor(eyeROI, tensorMat, Imgproc.COLOR_GRAY2BGR); // This looks wrong in original code, it should be gray
            // Re-checking original: eyeROI.convertTo(eyeROI, CvType.CV_8UC4); ... Imgproc.resize ...
            // Our eyeROI here is already GRAY from Model.java
        }
        
        Imgproc.resize(eyeROI, tensorMat, new Size(IMAGE_WIDTH, IMAGE_HEIGHT), Imgproc.INTER_AREA);
        
        if (type == 0) {
            compareTemplates = leftTemplates;
            detectionOutput.testingMats[0] = tensorMat.clone();
        } else {
            compareTemplates = rightTemplates;
            detectionOutput.testingMats[1] = tensorMat.clone();
        }

        // Convert current eye to 32F once
        tensorMat.convertTo(norB, CvType.CV_32F, 1.0/255.0);
        
        // MSE
        double minError = 10000000;
        int index = 0;
        for (int i = 0; i < userDataManager.calibrationTemplateNum; i++) {
            if (compareTemplates[i] == null) continue;
            
            double sum = mse(compareTemplates[i], norB, IMAGE_HEIGHT, IMAGE_WIDTH);
            templateError[i] = sum;
            if (sum < minError) {
                minError = sum;
                index = i;
            }
        }
        
        Boolean success = minError <= sensitivity;
        detectionOutput.setEyeData(type, success, tags[index], 1, (float)minError);

        tensorMat.release();
        return templateError;
    }

    /**
     * Detecta el iris dentro de la ROI del ojo usando threshold y contornos.
     * El centro del iris se usa para calcular NIC (Normalized Iris Center).
     */
    public Point irisDetection(Mat ROI) {
        Mat gray = new Mat();
        if (ROI.channels() > 1) {
            Imgproc.cvtColor(ROI, gray, Imgproc.COLOR_BGR2GRAY);
        } else {
            ROI.copyTo(gray);
        }
        
        Mat eroded = new Mat();
        Mat threshold = new Mat();
        opening = new Mat();
        Mat hierarchy = new Mat();
        Point irisCenter = new Point();

        Mat erode_kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
        ArrayList<MatOfPoint> contours = new ArrayList<>();

        // image processing
        Imgproc.blur(gray, eroded, new Size(3,3));
        Imgproc.threshold(eroded, threshold, thresholdValue, 255, Imgproc.THRESH_BINARY);
        Imgproc.blur(threshold, opening, new Size(3,3));

        // contour detection
        Core.bitwise_not(opening, opening);
        if (opening.channels() > 1) {
            Imgproc.cvtColor(opening, opening, Imgproc.COLOR_BGR2GRAY);
        }
        Imgproc.findContours(opening, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);
        double maxVal = -1;
        int maxValIdx = -1;
        for (int i = 0; i < contours.size(); i++) {
            double contourArea = Imgproc.contourArea(contours.get(i));
            if (maxVal < contourArea) {
                maxVal = contourArea;
                maxValIdx = i;
            }
        }
        finalMat = new Mat();
        Mat draw;
        if (maxValIdx != -1) {
            MatOfPoint maxContour = contours.get(maxValIdx);
            Moments moments = Imgproc.moments(maxContour);
            if (moments.get_m00() != 0) {
                irisCenter.x = moments.get_m10() / moments.get_m00();
                irisCenter.y = moments.get_m01() / moments.get_m00();
            }
            //Imgproc.drawContours(draw, contours, maxValIdx, new Scalar(255,255,255), 2);
            draw = new Mat(ROI.rows(), ROI.cols(), CvType.CV_8UC3);
            draw.setTo(new Scalar(0,0,0));
            Imgproc.circle(draw, new Point(irisCenter.x,irisCenter.y), 2, new Scalar(255,255,255));
            draw.copyTo(finalMat);
            draw.release();
        }
        
        gray.release();
        eroded.release();
        threshold.release();
        hierarchy.release();
        
        return irisCenter;
    }
}
