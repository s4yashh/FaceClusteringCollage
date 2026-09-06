package com.example.iykyk

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.example.iykyk.ui.theme.IykykTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.content.pm.PackageManager
import java.util.IdentityHashMap

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

private data class ProcessingResult(
    val faceCount: Int,
    val trackCount: Int,
    val validAppearanceCount: Int,
    val personClusters: List<PersonCluster>,
    val representativeShots: List<RepresentativeShot>
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
    var personClusters by remember {
        mutableStateOf<List<PersonCluster>>(emptyList())
    }
    var representativeShots by remember {
        mutableStateOf<List<RepresentativeShot>>(emptyList())
    }
    var statusMessage by remember {
        mutableStateOf<String?>(null)
    }
    var actionMessage by remember {
        mutableStateOf<String?>(null)
    }
    var showCollage by remember {
        mutableStateOf(false)
    }
    var pendingSaveBitmap by remember {
        mutableStateOf<Bitmap?>(null)
    }

    val personResults = remember(personClusters, representativeShots, selectedVideo) {
        val videoId = selectedVideo?.toString().orEmpty()
        val shotsByPerson = representativeShots
            .filter { it.videoId == videoId }
            .associateBy { it.personId }
        personClusters
            .mapNotNull { cluster ->
                shotsByPerson[cluster.personId]?.let { shot ->
                    PersonResult(
                        personId = cluster.personId,
                        representativeBitmap = shot.image,
                        appearanceCount = cluster.appearanceCount
                    )
                }
            }
            .sortedBy { it.personId }
    }
    var collageBitmap by remember {
        mutableStateOf<Bitmap?>(null)
    }

    val scope = rememberCoroutineScope()

    val savePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val bitmap = pendingSaveBitmap
        pendingSaveBitmap = null
        actionMessage = if (granted && bitmap != null) {
            if (saveCollageToGallery(context, bitmap) != null) {
                "Collage saved to Pictures"
            } else {
                "Could not save collage"
            }
        } else {
            "Storage permission is required to save the collage"
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        selectedVideo = uri
        progress = 0f
        frameCount = 0
        faceCount = 0
        trackCount = 0
        validAppearanceCount = 0
        personClusters = emptyList()
        representativeShots = emptyList()
        actionMessage = null
        showCollage = false
        collageBitmap = null
        pendingSaveBitmap = null
        statusMessage = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
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
                            personClusters = emptyList()
                            representativeShots = emptyList()
                            actionMessage = null
                            showCollage = false
                            collageBitmap = null
                            pendingSaveBitmap = null
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
                                    val allDetectedFaces = mutableListOf<DetectedFace>()
                                    val observationsByFace = IdentityHashMap<DetectedFace, FaceObservation>()
                                    val selectedImages = mutableSetOf<Bitmap>()
                                    try {
                                        val faceAnalyzer = FaceAnalyzer(context)
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
                                                allDetectedFaces += faces
                                                faces.forEach { face ->
                                                    observationsByFace[face] = FaceObservation(
                                                        timestampMs = timestamp,
                                                        face = face,
                                                        embedding = face.embedding,
                                                        frameIndex = index,
                                                        videoId = uri.toString(),
                                                        frameWidth = bitmap.width,
                                                        frameHeight = bitmap.height
                                                    )
                                                }

                                                if (faces.isNotEmpty()) {
                                                    Log.d(
                                                        "IYKYK_FACE",
                                                        "timestamp=${timestamp}ms faces=${faces.size} " +
                                                            faces.joinToString(separator = " | ") { face ->
                                                                "box=${face.boundingBox} yaw=${face.headYaw} " +
                                                                    "roll=${face.headRoll} sharpness=${face.sharpness}"
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
                                            statusMessage = "Tracking appearances..."
                                        }
                                        val tracks = FaceTracker().track(allDetectedFaces)
                                        withContext(Dispatchers.Main.immediate) {
                                            progress = 0.82f
                                            statusMessage = "Clustering identities..."
                                        }
                                        val people = IdentityClusterer().cluster(tracks)
                                        val personClusters = people.map { person ->
                                            PersonCluster(
                                                personId = person.id,
                                                observations = person.tracks
                                                    .flatMap { track -> track.faces }
                                                    .mapNotNull { face -> observationsByFace[face] },
                                                appearanceCount = person.appearanceCount
                                            )
                                        }

                                        withContext(Dispatchers.Main.immediate) {
                                            progress = 0.9f
                                            statusMessage = "Selecting representative shots..."
                                        }
                                        val representativeShots = RepresentativeShotSelector()
                                            .selectForVideo(personClusters, uri.toString())
                                        selectedImages += representativeShots.map { it.image }
                                        withContext(Dispatchers.Main.immediate) {
                                            progress = 1f
                                        }
                                        ProcessingResult(
                                            faceCount = allDetectedFaces.size,
                                            trackCount = tracks.size,
                                            validAppearanceCount = tracks.count { track ->
                                                track.faces.any { it.sharpness >= 0.15f }
                                            },
                                            personClusters = personClusters,
                                            representativeShots = representativeShots
                                        )
                                    } finally {
                                        detectedFrames.forEach { frame ->
                                            frame.faces.forEach { face ->
                                                if (face.tightCrop !== frame.bitmap && !face.tightCrop.isRecycled) {
                                                    face.tightCrop.recycle()
                                                }
                                                if (face.generousCrop !== frame.bitmap &&
                                                    face.generousCrop !in selectedImages &&
                                                    !face.generousCrop.isRecycled
                                                ) {
                                                    face.generousCrop.recycle()
                                                }
                                            }
                                            if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
                                        }
                                        frames.forEach { (_, bitmap) ->
                                            if (!bitmap.isRecycled) bitmap.recycle()
                                        }
                                    }
                                }

                                faceCount = result.faceCount
                                trackCount = result.trackCount
                                validAppearanceCount = result.validAppearanceCount
                                personClusters = result.personClusters
                                representativeShots = result.representativeShots
                                progress = 1f
                                statusMessage = "Processing complete"
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

            PersonResults(
                people = personResults
            )

            if (personResults.isNotEmpty()) {
                Button(
                    onClick = {
                        showCollage = true
                        actionMessage = "Creating collage..."
                        scope.launch(Dispatchers.Default) {
                            val generated = renderCollageBitmap(personResults)
                            withContext(Dispatchers.Main.immediate) {
                                collageBitmap = generated
                                actionMessage = "Collage ready"
                            }
                        }
                    },
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text("Create Collage")
                }
            }

            collageBitmap?.takeIf { showCollage }?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "IYKYK collage preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .padding(top = 16.dp)
                )
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingSaveBitmap = bitmap
                            savePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            actionMessage = if (saveCollageToGallery(context, bitmap) != null) {
                                "Collage saved to Pictures"
                            } else {
                                "Could not save collage"
                            }
                        }
                    }) {
                        Text("Save Collage")
                    }
                    Button(onClick = {
                        shareCollage(context, bitmap)
                    }) {
                        Text("Share Collage")
                    }
                }
                if (actionMessage != null) {
                    Text(
                        text = actionMessage.orEmpty(),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonResults(
    people: List<PersonResult>
) {
    Text(
        text = "Results",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
    )

    if (people.isEmpty()) {
        Text("No person-cluster results available")
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(people, key = { it.personId }) { person ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        bitmap = person.representativeBitmap.asImageBitmap(),
                        contentDescription = "Person ${person.personId} representative",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp)
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("Person ${person.personId}")
                        Text("${person.appearanceCount} appearances")
                    }
                }
            }
        }
    }
}
