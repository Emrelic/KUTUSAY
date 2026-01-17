package com.emrelic.kutusay.domain.ocr

import android.graphics.Bitmap
import android.net.Uri
import android.content.Context
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR servisi - Cloud Vision varsa onu kullanir, yoksa ML Kit'e fallback yapar
 */
@Singleton
class TextRecognitionService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudVisionService: CloudVisionService
) {
    companion object {
        private const val TAG = "TextRecognitionService"
    }

    private val mlKitRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Cloud Vision API kullanilabilir mi?
     */
    fun isCloudVisionAvailable(): Boolean = cloudVisionService.isAvailable()

    suspend fun recognizeText(imageUri: Uri): String {
        // Cloud Vision varsa onu kullan (daha iyi sonuc verir)
        if (cloudVisionService.isAvailable()) {
            return try {
                Log.d(TAG, "Using Cloud Vision API")
                cloudVisionService.recognizeText(imageUri)
            } catch (e: Exception) {
                Log.e(TAG, "Cloud Vision failed, falling back to ML Kit", e)
                recognizeWithMlKit(imageUri)
            }
        }

        // ML Kit kullan
        Log.d(TAG, "Using ML Kit (Cloud Vision not configured)")
        return recognizeWithMlKit(imageUri)
    }

    suspend fun recognizeText(bitmap: Bitmap): String {
        // Cloud Vision varsa onu kullan
        if (cloudVisionService.isAvailable()) {
            return try {
                Log.d(TAG, "Using Cloud Vision API")
                cloudVisionService.recognizeText(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Cloud Vision failed, falling back to ML Kit", e)
                recognizeWithMlKit(bitmap)
            }
        }

        // ML Kit kullan
        Log.d(TAG, "Using ML Kit (Cloud Vision not configured)")
        return recognizeWithMlKit(bitmap)
    }

    private suspend fun recognizeWithMlKit(imageUri: Uri): String {
        return suspendCancellableCoroutine { continuation ->
            try {
                val inputImage = InputImage.fromFilePath(context, imageUri)

                mlKitRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        continuation.resume(visionText.text)
                    }
                    .addOnFailureListener { e ->
                        continuation.resumeWithException(e)
                    }
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }

    private suspend fun recognizeWithMlKit(bitmap: Bitmap): String {
        return suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            mlKitRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    continuation.resume(visionText.text)
                }
                .addOnFailureListener { e ->
                    continuation.resumeWithException(e)
                }
        }
    }

    fun close() {
        mlKitRecognizer.close()
    }
}
