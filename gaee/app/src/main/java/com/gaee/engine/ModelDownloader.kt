package com.gaee.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progressPercent: Int, val fileName: String) : DownloadState()
    object Complete : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelDownloader(private val context: Context) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val modelUrl = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx"
    private val vocabUrl = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt"

    val modelFile = File(context.filesDir, "minilm_quantized.onnx")
    val vocabFile = File(context.filesDir, "vocab.txt")

    val filesExist: Boolean get() = modelFile.exists() && vocabFile.exists()

    fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    suspend fun download() = withContext(Dispatchers.IO) {
        try {
            if (!vocabFile.exists()) {
                _state.value = DownloadState.Downloading(0, "vocab.txt")
                downloadFile(vocabUrl, vocabFile)
            }
            if (!modelFile.exists()) {
                _state.value = DownloadState.Downloading(10, "minilm_quantized.onnx (~23 MB)")
                downloadFileWithProgress(modelUrl, modelFile) { pct ->
                    _state.value = DownloadState.Downloading(10 + (pct * 0.9).toInt(), "minilm_quantized.onnx")
                }
            }
            _state.value = DownloadState.Complete
        } catch (e: Exception) {
            modelFile.delete()
            vocabFile.delete()
            _state.value = DownloadState.Error(e.message ?: "Download failed")
        }
    }

    private fun downloadFile(url: String, dest: File) {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        response.body?.byteStream()?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun downloadFileWithProgress(url: String, dest: File, onProgress: (Int) -> Unit) {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        val body = response.body ?: throw Exception("Empty response")
        val total = body.contentLength()
        var downloaded = 0L
        val buf = ByteArray(8192)

        body.byteStream().use { input ->
            dest.outputStream().use { output ->
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    downloaded += read
                    if (total > 0) onProgress(((downloaded * 100) / total).toInt())
                }
            }
        }
    }
}
