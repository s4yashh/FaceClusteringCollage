package com.example.iykyk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FrameExtractor(
    private val context: Context
) {

    suspend fun extractFrames(
        videoUri: Uri,
        intervalMs: Long = 180L,
        onProgress: suspend (Float) -> Unit
    ): List<Pair<Long, Bitmap>> = withContext(Dispatchers.IO) {

        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<Pair<Long, Bitmap>>()

        try {
            retriever.setDataSource(context, videoUri)

            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?.let { ((it % 360) + 360) % 360 }
                ?: 0

            val durationMs = retriever
                .extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )
                ?.toLongOrNull()
                ?: 0L

            var currentTimeMs = 0L

            while (currentTimeMs < durationMs) {

                val bitmap = try {
                    retriever.getFrameAtTime(
                        currentTimeMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                } catch (_: Exception) {
                    // A corrupt or unsupported frame should not stop the video.
                    null
                }

                if (bitmap != null) {
                    frames.add(currentTimeMs to rotateIfNeeded(bitmap, rotation))
                }

                onProgress(
                    if (durationMs > 0)
                        currentTimeMs.toFloat() / durationMs
                    else
                        0f
                )

                currentTimeMs += intervalMs
            }

            onProgress(1f)

            return@withContext frames

        } finally {
            retriever.release()
        }
    }

    private fun rotateIfNeeded(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val orientedBitmap = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
        if (orientedBitmap !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return orientedBitmap
    }
}
