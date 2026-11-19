package com.newtermux.features

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.newtermux.compose.NewTermuxComposeTheme
import com.termux.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.max

/**
 * Full screen image viewer for `ACTION_VIEW` intents with an image mime type.
 *
 * This is the target of the image intent filter that [com.termux.app.api.file.FileReceiverActivity]
 * used to receive: viewing an image now renders it instead of copying it into `~/downloads` and
 * launching `termux-file-editor`. Sharing an image (`ACTION_SEND`) still goes to the file receiver,
 * since "share to Termux" is expected to save the file for the shell.
 *
 * Images are decoded off the main thread with a downsampled [BitmapFactory.Options.inSampleSize]
 * (minSdk 21 has no `ImageDecoder`), honouring the EXIF orientation, and can be pinch zoomed,
 * panned and double tapped to reset.
 */
class ImageViewerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        setContent {
            NewTermuxComposeTheme(this) {
                ImageViewerScreen(activity = this, uri = uri)
            }
        }
    }
}

/** Largest decoded edge, in pixels, before [BitmapFactory.Options.inSampleSize] kicks in. */
private const val MAX_DIMENSION = 2048

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 8f
private const val DOUBLE_TAP_SCALE = 2.5f

private sealed class ViewerState {
    object Idle : ViewerState()
    object Loading : ViewerState()
    class Ready(val bitmap: Bitmap, val name: String) : ViewerState()
    class Failed(val message: String) : ViewerState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageViewerScreen(activity: Activity, initialUri: Uri?) {
    val context = LocalContext.current
    var uri by rememberSaveable { mutableStateOf(initialUri) }
    var state by remember(uri) {
        mutableStateOf<ViewerState>(if (uri == null) ViewerState.Idle else ViewerState.Loading)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        if (picked != null) uri = picked
    }

    LaunchedEffect(uri) {
        val currentUri = uri
        if (currentUri != null) {
            state = withContext(Dispatchers.IO) { loadImage(context, currentUri) }
        }
    }

    BackHandler { activity.finish() }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = (state as? ViewerState.Ready)?.name
                            ?: context.getString(R.string.label_image_viewer),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { activity.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { pickImage.launch("image/*") }) {
                        Text("Open")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when (val current = state) {
                is ViewerState.Loading -> CircularProgressIndicator()
                is ViewerState.Idle -> ImagePrompt(
                    message = "No image selected",
                    buttonText = "Choose image",
                    onChoose = { pickImage.launch("image/*") },
                )
                is ViewerState.Failed -> ImagePrompt(
                    message = "Cannot open image\n\n${current.message}",
                    buttonText = "Choose another image",
                    onChoose = { pickImage.launch("image/*") },
                    isError = true,
                )
                is ViewerState.Ready -> ZoomableImage(current.bitmap)
            }
        }
    }
}

@Composable
private fun ImagePrompt(
    message: String,
    buttonText: String,
    onChoose: () -> Unit,
    isError: Boolean = false,
) {
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onChoose) {
            Text(buttonText)
        }
    }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    var scale by remember(bitmap) { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()
        val containerCenter = Offset(containerWidth / 2f, containerHeight / 2f)

        fun clampOffset(value: Offset, currentScale: Float): Offset {
            val maxX = ((currentScale - 1f) * containerWidth / 2f).coerceAtLeast(0f)
            val maxY = ((currentScale - 1f) * containerHeight / 2f).coerceAtLeast(0f)
            return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
        }

        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .pointerInput(bitmap) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val previousScale = scale
                        val newScale = (previousScale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        val zoomChange = newScale / previousScale
                        val centroidFromCenter = centroid - containerCenter
                        val newOffset = (offset + pan) * zoomChange +
                            centroidFromCenter * (1f - zoomChange)
                        scale = newScale
                        offset = clampOffset(newOffset, newScale)
                    }
                }
                .pointerInput(bitmap) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > MIN_SCALE) {
                                scale = MIN_SCALE
                                offset = Offset.Zero
                            } else {
                                scale = DOUBLE_TAP_SCALE
                                offset = Offset.Zero
                            }
                        },
                    )
                },
        )
    }
}

private fun loadImage(context: Context, uri: Uri): ViewerState {
    return try {
        val bitmap = decodeBitmap(context, uri)
            ?: return ViewerState.Failed("Unsupported or empty image file.")
        val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "image"
        ViewerState.Ready(bitmap, name)
    } catch (_: OutOfMemoryError) {
        ViewerState.Failed("Image is too large to load.")
    } catch (e: Exception) {
        ViewerState.Failed(e.message ?: e.javaClass.simpleName)
    }
}

private fun decodeBitmap(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openInputStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DIMENSION) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = openInputStream(context, uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null

    return rotateForExif(context, uri, decoded)
}

/**
 * `termux-open` passes a `content://<applicationId>.files/...` uri backed by our own exported
 * provider, whose `openFile` refuses callers unless the user enabled `allow-external-apps`. A
 * same-app read does not need that round trip: the uri path *is* the file path.
 */
private fun localShareFile(context: Context, uri: Uri): File? {
    if (uri.authority != context.packageName + ".files") return null
    val path = uri.path ?: return null
    val file = File(path)
    return if (file.isFile && file.canRead()) file else null
}

private fun openInputStream(context: Context, uri: Uri): InputStream? {
    localShareFile(context, uri)?.let { file ->
        return try {
            FileInputStream(file)
        } catch (_: IOException) {
            null
        }
    }
    return context.contentResolver.openInputStream(uri)
}

private fun rotateForExif(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val degrees = try {
        val exif = localShareFile(context, uri)
            ?.let { ExifInterface(it.absolutePath) }
            ?: context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor)
            }
        when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }

    if (degrees == 0) return bitmap

    return try {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } catch (_: Exception) {
        bitmap
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    localShareFile(context, uri)?.let { return it.name }
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null

    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: Exception) {
        null
    }
}
