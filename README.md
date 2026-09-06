# IYKYK

IYKYK is an on-device Android app that processes portrait videos, detects faces, tracks continuous appearances, groups appearances by identity, selects one representative shot per person, and renders a shareable collage.

## Build

1. Open the project in Android Studio.
2. Use JDK 11 and an Android SDK with API 37 installed.
3. Place the supplied model at `app/src/main/assets/mobilefacenet.tflite`.
4. Run `./gradlew assembleDebug` or build the `app` debug configuration.

The model is intentionally ignored by Git because it is supplied separately. The assets directory is preserved with `.gitkeep`.

## Model

The app uses the supplied float32 MobileFaceNet TensorFlow Lite model through `FaceEmbedder`. The model accepts two identical `112 x 112` RGB crops per inference because the supplied model has input shape `[2, 112, 112, 3]` and output shape `[2, 192]`; the first output row is L2-normalized and used for tracking and identity grouping.

## Processing Pipeline

```text
video
  -> sampled frames (180 ms)
  -> ML Kit face detection and attributes
  -> MobileFaceNet embeddings
  -> frame-to-frame appearance tracking
  -> identity clustering
  -> representative-shot selection
  -> collage preview, save, and share
```

Processing runs away from the main thread. Progress covers extraction, face analysis, tracking, clustering, and representative-shot selection.

## Thresholds

- Face tracking IoU: `0.20`
- Face tracking cosine similarity: `0.45`
- Track timeout: `500 ms`
- Minimum track sharpness: `0.15`
- Identity clustering cosine threshold: `0.50`
- Representative-shot minimum sharpness: `0.20`

Identity clustering compares a track against the strongest matching track already assigned to a person and prevents assignments for overlapping appearances.

## Testing

For each supplied sample video:

1. Select the video.
2. Press `Process Video`.
3. Confirm progress and appearance counts.
4. Press `Create Collage`.
5. Verify every detected person appears once with the displayed appearance count.
6. Test `Save Collage` and `Share Collage`.

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk` after running `./gradlew assembleDebug`. The sample videos and demo recording are not included in this repository.
