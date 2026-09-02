# Implementation Plan - Eye Gaze Detection Integration

Integrate the eye gaze detection vision pipeline into the `com.mike.lets` package, ensuring it follows the architecture and rules defined in `AGENTS.md`.

## User Review Required

> [!IMPORTANT]
> Several classes referenced in the original `Presenter.java` (e.g., `AudioManager`, `TextEntryManager`, `SocialMediaManager`) are missing from the project source. I will stub or remove these dependencies to focus on the **core vision backend** as requested. The primary goal is to reproduce the gaze detection, calibration, and output flow.

> [!WARNING]
> I will update the `MainActivity` to use the `Presenter` and `Model` logic. This will replace the current boilerplate code.

## Proposed Changes

### Build Configuration

#### [MODIFY] [libs.versions.toml](file:///C:/Users/mikel/AndroidStudioProjects/Lets2/gradle/libs.versions.toml)
Add versions and library definitions for:
- OpenCV (Android)
- MediaPipe (Face Landmarker)
- CameraX
- DataStore (Preferences + RxJava)
- RxJava 2 & RxAndroid

#### [MODIFY] [app/build.gradle.kts](file:///C:/Users/mikel/AndroidStudioProjects/Lets2/app/build.gradle.kts)
- Apply dependencies from `libs.versions.toml`.
- Configure `buildFeatures` (viewBinding).
- Ensure `compileOptions` and `kotlinOptions` are set for Java 11+.

---

### Core Vision Backend (com.mike.lets)

#### [MODIFY] [ContractInterface.java](file:///C:/Users/mikel/AndroidStudioProjects/Lets2/app/src/main/java/com/mike/lets/ContractInterface.java)
- Remove missing dependencies (`ClinicalData`).
- Simplify interfaces to focus on the vision pipeline.

#### [NEW] [Presenter.java](file:///C:/Users/mikel/AndroidStudioProjects/Lets2/app/src/main/java/com/mike/lets/Presenter.java)
- Copy from `com.demo.opencv.Presenter.java`.
- Update package to `com.mike.lets`.
- Remove references to missing managers (`AudioManager`, `TextEntryManager`, etc.).
- Focus on `onFrame` logic: model classification -> gaze interpretation -> UI update.

#### [MODIFY] [UserDataManager.java](file:///C:/Users/mikel/AndroidStudioProjects/Lets2/app/src/main/java/com/mike/lets/UserDataManager.java)
- Ensure it handles calibration bitmap persistence correctly.
- Fix any package-related import issues.

#### [MODIFY] [Model.java](file:///C:/Users/mikel/AndroidStudioProjects/Lets2/app/src/main/java/com/mike/lets/vision/Model.java)
- Ensure it uses `MediaPipeFaceDetector`.
- Verify OpenCV initialization.

#### [MODIFY] [AppLiveData.java](file:///C:/Users/mikel/AndroidStudioProjects/Lets2/app/src/main/java/com/mike/lets/AppLiveData.java)
- Fix package imports.
- Remove missing data types.

---

### UI Integration

#### [MODIFY] [MainActivity.java](file:///C:/Users/mikel/AndroidStudioProjects/Lets2/app/src/main/java/com/mike/lets/MainActivity.java)
- Implement `ContractInterface.View`.
- Initialize `Presenter` and `Model`.
- Set up CameraX to feed frames into `Presenter.onFrame`.
- Handle UI updates from `AppLiveData`.
- Implement navigation to calibration (if layouts are ready).

## Verification Plan

### Automated Tests
- Run `gradle build` to ensure all imports and dependencies are correct.

### Manual Verification
1. Deploy the app to a device.
2. Verify that the camera opens and shows a preview.
3. Verify that face/eye detection logs appear (if debugging enabled).
4. Test the calibration flow (Look straight, left, etc.) and ensure bitmaps are saved.
5. Verify that gaze detection works after calibration.
