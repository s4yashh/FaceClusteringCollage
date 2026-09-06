package com.example.iykyk

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.iykyk.ui.theme.IykykTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            IykykTheme {
                VideoPickerScreen()
            }
        }
    }
}

private data class FrameFaces(
    val timestampMs: Long,
    val bitmap: Bitmap,
    val faces: List<DetectedFace>
)

@Composable
fun VideoPickerScreen() {

    val context = LocalContext.current

    var selectedVideo by remember {
        mutableStateOf<Uri?>(null)
    }

    var progress by remember {
        mutableFloatStateOf(0f)
    }

    var isProcessing by remember {
        mutableStateOf(false)
    }

    var frameCount by remember {
        mutableIntStateOf(0)
    }
    var faceCount by remember {
        mutableIntStateOf(0)
    }
    var trackCount by remember {
        mutableIntStateOf(0)
    }
    var validAppearanceCount by remember {
        mutableIntStateOf(0)
    }
    var statusMessage by remember {
        mutableStateOf<String?>(null)
    }

    val scope = rememberCoroutineScope()

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        selectedVideo = uri
        progress = 0f
        frameCount = 0
        faceCount = 0
        trackCount = 0
        validAppearanceCount = 0
        statusMessage = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            text = "IYKYK",
            style = MaterialTheme.typography.headlineLarge
        )

        Text(
            text = "Video-based unique-person collage",
            modifier = Modifier.padding(
                top = 8.dp,
                bottom = 24.dp
            )
        )

        Button(
            onClick = {
                videoPicker.launch("video/*")
            },
            enabled = !isProcessing
        ) {
            Text("Select Video")
        }

        if (selectedVideo != null) {

            Text(
                text = "✓ Video selected",
                modifier = Modifier.padding(top = 20.dp)
            )

            Button(
                onClick = {

                    val uri = selectedVideo

                    if (uri != null) {

                        scope.launch {

                            isProcessing = true
                            progress = 0f
                            frameCount = 0
                            faceCount = 0
                            trackCount = 0
                            validAppearanceCount = 0
                            statusMessage = null

                            try {
                                val extractor = FrameExtractor(context)
                                val frames = extractor.extractFrames(
                                    videoUri = uri,
                                    intervalMs = 180L
                                ) { extractionProgress ->
                                    withContext(Dispatchers.Main.immediate) {
                                        progress = extractionProgress * 0.4f
                                    }
                                }

                                frameCount = frames.size
                                statusMessage = "Detecting faces..."

                                val result = withContext(Dispatchers.Default) {
                                    val detectedFrames = mutableListOf<FrameFaces>()
                                    try {
                                        val faceAnalyzer = FaceAnalyzer()
                                        try {
                                            frames.forEachIndexed { index, (timestamp, bitmap) ->
                                                val faces = try {
                                                    faceAnalyzer.detectFaces(timestamp, bitmap)
                                                } catch (error: Exception) {
                                                    Log.d(
                                                        "IYKYK_FACE",
                                                        "Skipping frame at ${timestamp}ms after ML Kit failure: " +
                                                            "${error.message}"
                                                    )
                                                    emptyList()
                                                }
                                                detectedFrames += FrameFaces(timestamp, bitmap, faces)

                                                if (faces.isNotEmpty()) {
                                                    Log.d(
                                                        "IYKYK_FACE",
                                                        "timestamp=${timestamp}ms faces=${faces.size} " +
                                                            faces.joinToString(separator = " | ") { face ->
                                                                "box=${face.boundingBox} " +
                                                                    "yaw=${face.headYaw} roll=${face.headRoll} " +
                                                                    "leftEye=${face.leftEyeOpenProbability} " +
                                                                    "rightEye=${face.rightEyeOpenProbability} " +
                                                                    "smile=${face.smileProbability} " +
                                                                    "sharpness=${face.sharpness} " +
                                                                    "tight=${face.tightCrop.width}x${face.tightCrop.height} " +
                                                                    "generous=${face.generousCrop.width}x${face.generousCrop.height}"
                                                            }
                                                    )
                                                }
                                                withContext(Dispatchers.Main.immediate) {
                                                    progress = 0.4f +
                                                        ((index + 1).toFloat() / frames.size.coerceAtLeast(1)) * 0.3f
                                                }
                                            }
                                        } finally {
                                            faceAnalyzer.close()
                                        }

                                        withContext(Dispatchers.Main.immediate) {
                                            statusMessage = "Generating face embeddings..."
                                        }
                                        val embedder = MobileFaceNetEmbedder(context)
                                        val observationsByFrame = mutableListOf<List<FaceObservation>>()
                                        try {
                                            val totalDetections = detectedFrames.sumOf { it.faces.size }
                                            var completedEmbeddings = 0
                                            detectedFrames.forEachIndexed { frameIndex, frame ->
                                                val observations = mutableListOf<FaceObservation>()
                                                for (face in frame.faces) {
                                                    try {
                                                        val embedding = embedder.embed(face.tightCrop)
                                                        observations += FaceObservation(
                                                            timestampMs = frame.timestampMs,
                                                            face = face,
                                                            embedding = embedding,
                                                            frameIndex = frameIndex
                                                        )
                                                    } catch (error: Exception) {
                                                        Log.d(
                                                            "IYKYK_EMBEDDING",
                                                            "Skipping embedding at ${frame.timestampMs}ms: " +
                                                                "${error.message}"
                                                        )
                                                        null
                                                    } finally {
                                                        completedEmbeddings++
                                                        withContext(Dispatchers.Main.immediate) {
                                                                progress = 0.7f +
                                                                    (completedEmbeddings.toFloat() /
                                                                        totalDetections.coerceAtLeast(1)) * 0.2f
                                                        }
                                                    }
                                                }
                                                observationsByFrame += observations
                                            }
                                        } finally {
                                            embedder.close()
                                        }

                                        withContext(Dispatchers.Main.immediate) {
                                            statusMessage = "Tracking appearances..."
                                        }
                                        val tracker = AppearanceTracker()
                                        observationsByFrame.forEachIndexed { index, observations ->
                                            val timestamp = detectedFrames[index].timestampMs
                                            tracker.processFrame(timestamp, observations)
                                            withContext(Dispatchers.Main.immediate) {
                                                progress = 0.9f +
                                                    ((index + 1).toFloat() /
                                                        observationsByFrame.size.coerceAtLeast(1)) * 0.1f
                                            }
                                        }
                                        tracker.finish()
                                        Triple(
                                            detectedFrames.sumOf { it.faces.size },
                                            tracker.completedTracks.size,
                                            tracker.completedTracks.count { it.isValidAppearance }
                                        )
                                    } finally {
                                        detectedFrames.forEach { frame ->
                                            frame.faces.forEach { face ->
                                                if (face.tightCrop !== frame.bitmap && !face.tightCrop.isRecycled) {
                                                    face.tightCrop.recycle()
                                                }
                                                if (face.generousCrop !== frame.bitmap && !face.generousCrop.isRecycled) {
                                                    face.generousCrop.recycle()
                                                }
                                            }
                                            if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
                                        }
                                    }
                                }

                                faceCount = result.first
                                trackCount = result.second
                                validAppearanceCount = result.third
                                progress = 1f
                                statusMessage = "Appearance tracking complete"
                            } catch (error: Exception) {
                                Log.d(
                                    "IYKYK_FACE",
                                    "Video processing failed: ${error.message}"
                                )
                                statusMessage = if (
                                    error is java.io.FileNotFoundException ||
                                        error.message?.contains("mobilefacenet.tflite") == true
                                ) {
                                    "Add mobilefacenet.tflite to app/src/main/assets"
                                } else {
                                    "Could not process this video"
                                }
                            } finally {
                                isProcessing = false
                            }
                        }
                    }
                },
                enabled = !isProcessing,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text(
                    if (isProcessing)
                        "Processing..."
                    else
                        "Process Video"
                )
            }
        }

        if (isProcessing) {

            Spacer(modifier = Modifier.height(24.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "${(progress * 100).toInt()}%",
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (statusMessage != null) {
            Text(
                text = statusMessage.orEmpty(),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (!isProcessing && frameCount > 0) {
            Text(
                text = "Frames: $frameCount",
                modifier = Modifier.padding(top = 24.dp)
            )
            Text(text = "Face detections: $faceCount")
            Text(text = "Appearance tracks: $trackCount")
            Text(text = "Valid appearances: $validAppearanceCount")
        }
    }
}
