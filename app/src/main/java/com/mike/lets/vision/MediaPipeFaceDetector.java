package com.mike.lets.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsula la detecciÃƒÂ³n facial con MediaPipe.
 *
 * Su trabajo es localizar la cara y los puntos de referencia de los ojos
 * para que luego el modelo pueda recortar cada ojo y clasificar la mirada.
 */
public class MediaPipeFaceDetector {

    private FaceLandmarker faceLandmarker;
    private final String TAG = "MediaPipeFaceDetector";

    // Data for eyes
    public List<PointF> leftEyeContour, rightEyeContour;
    public float rightEyeOpenProb, leftEyeOpenProb;

    // Indices for eyes in MediaPipe Face Mesh (468+ landmarks)
    private static final int[] LEFT_EYE_INDICES = {33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246};
    private static final int[] RIGHT_EYE_INDICES = {362, 382, 381, 380, 374, 373, 390, 249, 263, 466, 388, 387, 386, 385, 384, 398};

    /**
     * Carga el modelo de landmarks faciales de MediaPipe.
     */
    public void initialize(Context context) {
        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build();

        FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .build();

        try {
            faceLandmarker = FaceLandmarker.createFromOptions(context, options);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize FaceLandmarker: " + e.getMessage());
        }
    }

    /**
     * Analiza un frame bitmap y guarda los landmarks del ojo y su estado abierto/cerrado.
     */
    public void detect(Bitmap bitmap) {
        if (faceLandmarker == null) return;

        MPImage mpImage = new BitmapImageBuilder(bitmap).build();
        FaceLandmarkerResult result = faceLandmarker.detect(mpImage);

        analyze(result, bitmap.getWidth(), bitmap.getHeight());
    }

    private void analyze(FaceLandmarkerResult result, int width, int height) {
        if (result.faceLandmarks().isEmpty()) {
            leftEyeContour = null;
            rightEyeContour = null;
            return;
        }

        List<NormalizedLandmark> landmarks = result.faceLandmarks().get(0);

        leftEyeContour = new ArrayList<>();
        for (int index : LEFT_EYE_INDICES) {
            NormalizedLandmark landmark = landmarks.get(index);
            leftEyeContour.add(new PointF(landmark.x() * width, landmark.y() * height));
        }

        rightEyeContour = new ArrayList<>();
        for (int index : RIGHT_EYE_INDICES) {
            NormalizedLandmark landmark = landmarks.get(index);
            rightEyeContour.add(new PointF(landmark.x() * width, landmark.y() * height));
        }

        // Calculation of EAR (Eye Aspect Ratio) for open probability
        leftEyeOpenProb = calculateEAR(landmarks, 33, 160, 158, 133, 153, 144);
        rightEyeOpenProb = calculateEAR(landmarks, 263, 385, 387, 362, 380, 373);
    }

    private float calculateEAR(List<NormalizedLandmark> landmarks, int p1, int p2, int p3, int p4, int p5, int p6) {
        float distVertical1 = dist(landmarks.get(p2), landmarks.get(p6));
        float distVertical2 = dist(landmarks.get(p3), landmarks.get(p5));
        float distHorizontal = dist(landmarks.get(p1), landmarks.get(p4));
        float ear = (distVertical1 + distVertical2) / (2.0f * distHorizontal);
        
        // Normalize ear to a 0.0 - 1.0 range (heuristic)
        // Typically EAR > 0.2 is open
        return ear > 0.2f ? 1.0f : ear / 0.2f;
    }

    private float dist(NormalizedLandmark a, NormalizedLandmark b) {
        return (float) Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2));
    }
}

