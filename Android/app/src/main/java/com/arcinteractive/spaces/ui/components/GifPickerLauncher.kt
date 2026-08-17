package com.arcinteractive.spaces.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.arcinteractive.spaces.BuildConfig
import com.giphy.sdk.core.models.Media
import com.giphy.sdk.ui.GPHContentType
import com.giphy.sdk.ui.GPHSettings
import com.giphy.sdk.ui.themes.GPHTheme
import com.giphy.sdk.ui.views.GiphyDialogFragment
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GifPickerSelection(
    val gifBytes: ByteArray,
    val previewBytes: ByteArray,
    val mimeType: String = "image/gif"
)

@Composable
fun rememberGifPickerLauncher(
    onGifSelected: (GifPickerSelection) -> Unit,
    onError: (String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    return remember(context, onGifSelected, onError) {
        {
            if (BuildConfig.GIF_SDK_API_KEY.isBlank()) {
                onError("GIFs are not configured on this device yet.")
                return@remember
            }

            val activity = context.findFragmentActivity()
            if (activity == null) {
                onError("Unable to open GIF browser.")
                return@remember
            }

            val settings = GPHSettings().apply {
                theme = GPHTheme.Automatic
                mediaTypeConfig = arrayOf(GPHContentType.gif)
                selectedContentType = GPHContentType.gif
                showConfirmationScreen = false
                autoCloseOnMediaSelect = true
            }

            val dialog = GiphyDialogFragment.Companion.newInstance(settings)
            var didSelectGif = false
            dialog.gifSelectionListener = object : GiphyDialogFragment.GifSelectionListener {
                override fun onGifSelected(
                    media: Media,
                    searchTerm: String?,
                    selectedContentType: GPHContentType
                ) {
                    didSelectGif = true
                    val resolvedSearchTerm = searchTerm?.ifBlank { "trending" } ?: "trending"
                    Log.d("GifSend", "GIF selected source=giphy mediaId=${media.id} searchTerm=$resolvedSearchTerm")
                    scope.launch {
                        try {
                            val resolvedUrl = media.bestGifUrl()
                            if (resolvedUrl.isNullOrBlank()) {
                                Log.e("GifSend", "GIF selection failed source=giphy mediaId=${media.id} reason=missing_gif_url")
                                onError("Unable to load the selected GIF.")
                                return@launch
                            }

                            Log.d("GifSend", "Preparing upload source=giphy mediaId=${media.id}")
                            val gifBytes = downloadGifBytes(resolvedUrl)
                            val previewBytes = gifBytes
                            onGifSelected(
                                GifPickerSelection(
                                    gifBytes = gifBytes,
                                    previewBytes = previewBytes,
                                    mimeType = "image/gif"
                                )
                            )
                            Log.d("GifSend", "Message visible locally source=giphy mediaId=${media.id}")
                        } catch (error: Throwable) {
                            Log.e("GifSend", "GIF download failed source=giphy mediaId=${media.id}", error)
                            onError(error.localizedMessage ?: "Unable to load the selected GIF.")
                        }
                    }
                }

                override fun onDismissed(selectedContentType: GPHContentType) {
                    if (!didSelectGif) {
                        Log.d("GifSend", "GIF picker dismissed without selection")
                    }
                }

                override fun didSearchTerm(term: String) {
                    Log.d("GifSend", "GIF search term=$term")
                }
            }

            try {
                dialog.show(activity.supportFragmentManager, "spaces_gif_picker")
            } catch (error: Throwable) {
                Log.e("GifSend", "Unable to open GIF picker", error)
                onError(error.localizedMessage ?: "Unable to open GIF picker.")
            }
        }
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

private fun Media.bestGifUrl(): String? {
    val images = images ?: return null
    return images.original?.gifUrl
        ?: images.downsizedMedium?.gifUrl
        ?: images.fixedWidth?.gifUrl
        ?: images.preview?.gifUrl
}

private suspend fun downloadGifBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 15_000
        requestMethod = "GET"
        doInput = true
    }

    try {
        val statusCode = connection.responseCode
        if (statusCode !in 200..299) {
            throw IllegalStateException("Unable to download the selected GIF.")
        }

        connection.inputStream.use { input ->
            input.readBytes()
        }
    } finally {
        connection.disconnect()
    }
}
