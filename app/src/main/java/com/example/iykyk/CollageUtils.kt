package com.example.iykyk

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

data class PersonResult(
    val personId: Int,
    val representativeBitmap: Bitmap,
    val appearanceCount: Int
)

fun renderCollageBitmap(people: List<PersonResult>, width: Int = 1080): Bitmap {
    val columns = 2
    val tile = width / columns
    val rows = (people.size + columns - 1) / columns
    val headerHeight = 180
    val height = headerHeight + tile * rows

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val backgroundPaint = Paint().apply {
        shader = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            Color.parseColor("#1A1A2E"),
            Color.parseColor("#16213E"),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

    val titlePaint = Paint().apply {
        color = Color.WHITE
        textSize = 64f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    canvas.drawText("IYKYK", width / 2f, 110f, titlePaint)

    people.forEachIndexed { index, person ->
        val column = index % columns
        val row = index / columns
        val left = column * tile + 16
        val top = headerHeight + row * tile + 16
        val right = (column + 1) * tile - 16
        val bottom = headerHeight + (row + 1) * tile - 16
        val rect = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        val path = Path().apply {
            addRoundRect(rect, 32f, 32f, Path.Direction.CW)
        }

        canvas.save()
        canvas.clipPath(path)
        val source = Rect(
            0,
            0,
            person.representativeBitmap.width,
            person.representativeBitmap.height
        )
        canvas.drawBitmap(person.representativeBitmap, source, rect, null)
        canvas.restore()

        val badgeRect = RectF(rect.right - 150, rect.bottom - 80, rect.right - 16, rect.bottom - 16)
        val badgePaint = Paint().apply { color = Color.argb(180, 0, 0, 0) }
        canvas.drawRoundRect(badgeRect, 20f, 20f, badgePaint)

        val badgeTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText(
            "${person.appearanceCount}x",
            badgeRect.centerX(),
            badgeRect.centerY() + 14,
            badgeTextPaint
        )
    }

    return bitmap
}

fun saveCollageToGallery(context: Context, bitmap: Bitmap): Uri? {
    val filename = "iykyk_collage_${System.currentTimeMillis()}.jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }
    }

    val uri = context.contentResolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values
    ) ?: return null

    context.contentResolver.openOutputStream(uri)?.use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
    }
    return uri
}

fun shareCollage(context: Context, bitmap: Bitmap) {
    val cacheDir = File(context.cacheDir, "collages").apply { mkdirs() }
    val file = File(cacheDir, "share_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
    }

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share collage"))
}
