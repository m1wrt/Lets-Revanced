package com.mike.lets;

import android.graphics.Bitmap;
import com.mike.lets.vision.DetectionOutput;

/** FORCE REWRITE WITHOUT BOM */
public class AppLiveData {
    public DetectionOutput DetectionOutput; // detection output from model

    // calibration interface
    public Bitmap[] leftTemplates;
    public Bitmap[] rightTemplates;
    public int calibrationState = -1;
    public String calibrationInstruction;
    public boolean isRecording;
    public String currentText = "";
    public String llmResponse = "";

    void setDetectionOutput(DetectionOutput detectionOutput) { this.DetectionOutput = detectionOutput; }
}
