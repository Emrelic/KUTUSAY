package com.emrelic.kutusay.domain.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.emrelic.kutusay.BuildConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud Vision API hata tipleri
 */
sealed class CloudVisionException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ApiKeyNotConfigured : CloudVisionException("Cloud Vision API key not configured")
    class NetworkError(cause: Throwable) : CloudVisionException("Network error: ${cause.message}", cause)
    class ApiError(val code: Int, val body: String) : CloudVisionException("API error $code: $body")
    class ParseError(cause: Throwable) : CloudVisionException("Failed to parse response: ${cause.message}", cause)
    class ImageLoadError(cause: Throwable) : CloudVisionException("Failed to load image: ${cause.message}", cause)
}

/**
 * Google Cloud Vision API ile OCR servisi
 * Koordinat bazli satir gruplandirma ile tablo okuma destegi
 */
@Singleton
class CloudVisionService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CloudVisionService"
        private const val VISION_API_URL = "https://vision.googleapis.com/v1/images:annotate"
        private const val MAX_IMAGE_SIZE = 1024
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 1000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun isAvailable(): Boolean {
        return BuildConfig.CLOUD_VISION_API_KEY.isNotEmpty()
    }

    suspend fun recognizeText(imageUri: Uri): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            throw CloudVisionException.ApiKeyNotConfigured()
        }

        val bitmap = try {
            loadAndResizeBitmap(imageUri)
        } catch (e: Exception) {
            throw CloudVisionException.ImageLoadError(e)
        }

        try {
            recognizeTextFromBitmapWithRetry(bitmap)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    suspend fun recognizeText(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            throw CloudVisionException.ApiKeyNotConfigured()
        }

        val resizedBitmap = resizeBitmap(bitmap)
        try {
            recognizeTextFromBitmapWithRetry(resizedBitmap)
        } finally {
            // Sadece yeniden boyutlandirildiysa recycle et
            if (resizedBitmap !== bitmap && !resizedBitmap.isRecycled) {
                resizedBitmap.recycle()
            }
        }
    }

    /**
     * Detayli kelime bilgisi ile OCR sonucu
     * InvoiceTableExtractor ile kullanilmak uzere koordinatli kelime listesi dondurur
     */
    data class DetailedOcrResult(
        val words: List<InvoiceTableExtractor.DetectedWord>,
        val rawText: String,
        val bitmap: Bitmap
    )

    /**
     * Koordinatli detayli OCR sonucu dondur
     * Tablo cikarimi icin kullanilir
     */
    suspend fun recognizeTextDetailed(imageUri: Uri): DetailedOcrResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            throw CloudVisionException.ApiKeyNotConfigured()
        }

        val bitmap = try {
            loadAndResizeBitmap(imageUri)
        } catch (e: Exception) {
            throw CloudVisionException.ImageLoadError(e)
        }

        recognizeTextDetailedFromBitmapWithRetry(bitmap)
        // NOT: Bitmap burada recycle edilmiyor cunku DetailedOcrResult icinde kullaniliyor
    }

    suspend fun recognizeTextDetailed(bitmap: Bitmap): DetailedOcrResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            throw CloudVisionException.ApiKeyNotConfigured()
        }

        val resizedBitmap = resizeBitmap(bitmap)
        recognizeTextDetailedFromBitmapWithRetry(resizedBitmap)
    }

    /**
     * Retry mekanizmasi ile OCR
     */
    private suspend fun recognizeTextFromBitmapWithRetry(bitmap: Bitmap): String {
        var lastException: Exception? = null
        repeat(MAX_RETRY_COUNT) { attempt ->
            try {
                return recognizeTextFromBitmap(bitmap)
            } catch (e: IOException) {
                lastException = e
                Log.w(TAG, "Network error on attempt ${attempt + 1}/$MAX_RETRY_COUNT: ${e.message}")
                if (attempt < MAX_RETRY_COUNT - 1) {
                    kotlinx.coroutines.delay(RETRY_DELAY_MS * (attempt + 1))
                }
            } catch (e: CloudVisionException.ApiError) {
                // 5xx hatalari icin retry yap, 4xx icin yapma
                if (e.code in 500..599) {
                    lastException = e
                    Log.w(TAG, "Server error on attempt ${attempt + 1}/$MAX_RETRY_COUNT: ${e.message}")
                    if (attempt < MAX_RETRY_COUNT - 1) {
                        kotlinx.coroutines.delay(RETRY_DELAY_MS * (attempt + 1))
                    }
                } else {
                    throw e
                }
            }
        }
        throw CloudVisionException.NetworkError(lastException ?: Exception("Unknown error after $MAX_RETRY_COUNT retries"))
    }

    /**
     * Retry mekanizmasi ile detayli OCR
     */
    private suspend fun recognizeTextDetailedFromBitmapWithRetry(bitmap: Bitmap): DetailedOcrResult {
        var lastException: Exception? = null
        repeat(MAX_RETRY_COUNT) { attempt ->
            try {
                return recognizeTextDetailedFromBitmap(bitmap)
            } catch (e: IOException) {
                lastException = e
                Log.w(TAG, "Network error on attempt ${attempt + 1}/$MAX_RETRY_COUNT: ${e.message}")
                if (attempt < MAX_RETRY_COUNT - 1) {
                    kotlinx.coroutines.delay(RETRY_DELAY_MS * (attempt + 1))
                }
            } catch (e: CloudVisionException.ApiError) {
                if (e.code in 500..599) {
                    lastException = e
                    Log.w(TAG, "Server error on attempt ${attempt + 1}/$MAX_RETRY_COUNT: ${e.message}")
                    if (attempt < MAX_RETRY_COUNT - 1) {
                        kotlinx.coroutines.delay(RETRY_DELAY_MS * (attempt + 1))
                    }
                } else {
                    throw e
                }
            }
        }
        throw CloudVisionException.NetworkError(lastException ?: Exception("Unknown error after $MAX_RETRY_COUNT retries"))
    }

    /**
     * Bitmap'ten detayli OCR sonucu cikar
     */
    private suspend fun recognizeTextDetailedFromBitmap(bitmap: Bitmap): DetailedOcrResult = withContext(Dispatchers.IO) {
        val base64Image = bitmapToBase64(bitmap)

        val requestBody = VisionRequest(
            requests = listOf(
                AnnotateImageRequest(
                    image = Image(content = base64Image),
                    features = listOf(
                        Feature(type = "DOCUMENT_TEXT_DETECTION", maxResults = 1)
                    )
                )
            )
        )

        val jsonBody = gson.toJson(requestBody)
        Log.d(TAG, "Detailed OCR request body size: ${jsonBody.length} chars")

        val request = Request.Builder()
            .url("$VISION_API_URL?key=${BuildConfig.CLOUD_VISION_API_KEY}")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e(TAG, "API error: ${response.code} - $errorBody")
            throw CloudVisionException.ApiError(response.code, errorBody)
        }

        val responseBody = response.body?.string()
            ?: throw CloudVisionException.ParseError(Exception("Empty response"))
        Log.d(TAG, "Detailed OCR response received, parsing words...")

        val visionResponse = gson.fromJson(responseBody, VisionResponse::class.java)

        // Kelime listesini cikar
        val textAnnotations = visionResponse.responses?.firstOrNull()?.textAnnotations
        val words = mutableListOf<InvoiceTableExtractor.DetectedWord>()

        if (!textAnnotations.isNullOrEmpty()) {
            // Ilk eleman tum metni icerir, atla
            // Geri kalanlar kelime kelime
            for (i in 1 until textAnnotations.size) {
                val annotation = textAnnotations[i]
                val vertices = annotation.boundingPoly?.vertices

                if (vertices != null && vertices.size >= 4 && !annotation.description.isNullOrBlank()) {
                    val minX = vertices.mapNotNull { it.x }.minOrNull() ?: 0
                    val minY = vertices.mapNotNull { it.y }.minOrNull() ?: 0
                    val maxX = vertices.mapNotNull { it.x }.maxOrNull() ?: 0
                    val maxY = vertices.mapNotNull { it.y }.maxOrNull() ?: 0

                    words.add(
                        InvoiceTableExtractor.DetectedWord(
                            text = annotation.description,
                            minX = minX,
                            minY = minY,
                            maxX = maxX,
                            maxY = maxY,
                            centerX = (minX + maxX) / 2,
                            centerY = (minY + maxY) / 2,
                            isHandwritten = false, // Renk tespiti sonra yapilacak
                            confidence = 1.0f
                        )
                    )
                }
            }
        }

        Log.d(TAG, "Extracted ${words.size} words with coordinates")

        // Raw text
        val rawText = visionResponse.responses?.firstOrNull()?.fullTextAnnotation?.text
            ?: textAnnotations?.firstOrNull()?.description
            ?: ""

        DetailedOcrResult(
            words = words,
            rawText = rawText,
            bitmap = bitmap
        )
    }

    /**
     * Koordinat bazli satir gruplandirma ile metin tanima
     * Bu yontem tablo satirlarini dogru sirada okur
     */
    private suspend fun recognizeTextFromBitmap(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val base64Image = bitmapToBase64(bitmap)

        val requestBody = VisionRequest(
            requests = listOf(
                AnnotateImageRequest(
                    image = Image(content = base64Image),
                    features = listOf(
                        Feature(type = "DOCUMENT_TEXT_DETECTION", maxResults = 1)
                    )
                )
            )
        )

        val jsonBody = gson.toJson(requestBody)
        Log.d(TAG, "Request body size: ${jsonBody.length} chars")

        val request = Request.Builder()
            .url("$VISION_API_URL?key=${BuildConfig.CLOUD_VISION_API_KEY}")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e(TAG, "API error: ${response.code} - $errorBody")
            throw CloudVisionException.ApiError(response.code, errorBody)
        }

        val responseBody = response.body?.string()
            ?: throw CloudVisionException.ParseError(Exception("Empty response"))
        Log.d(TAG, "Response received, parsing...")

        val visionResponse = try {
            gson.fromJson(responseBody, VisionResponse::class.java)
        } catch (e: Exception) {
            throw CloudVisionException.ParseError(e)
        }

        // Oncelik 1: fullTextAnnotation - Google'in okuma sirasi algoritmasi
        val fullText = visionResponse.responses?.firstOrNull()?.fullTextAnnotation?.text
        if (!fullText.isNullOrBlank()) {
            Log.d(TAG, "DOCUMENT_TEXT_DETECTION result: ${fullText.take(300)}...")
            return@withContext fullText
        }

        // Oncelik 2: textAnnotations ilk eleman (tum metin)
        val textAnnotations = visionResponse.responses?.firstOrNull()?.textAnnotations
        if (!textAnnotations.isNullOrEmpty()) {
            val text = textAnnotations.first().description ?: ""
            if (text.isNotBlank()) {
                Log.d(TAG, "TEXT_ANNOTATION result: ${text.take(300)}...")
                return@withContext text
            }
        }

        Log.w(TAG, "No text found in image")
        ""
    }

    private fun loadAndResizeBitmap(uri: Uri): Bitmap {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot open image URI")

        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        return resizeBitmap(bitmap)
    }

    private fun resizeBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= MAX_IMAGE_SIZE && height <= MAX_IMAGE_SIZE) {
            return bitmap
        }

        val scale = MAX_IMAGE_SIZE.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        Log.d(TAG, "Resizing image from ${width}x${height} to ${newWidth}x${newHeight}")
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val bytes = outputStream.toByteArray()
        Log.d(TAG, "Image size: ${bytes.size / 1024} KB")
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // Request/Response data classes
    data class VisionRequest(
        val requests: List<AnnotateImageRequest>
    )

    data class AnnotateImageRequest(
        val image: Image,
        val features: List<Feature>
    )

    data class Image(
        val content: String
    )

    data class Feature(
        val type: String,
        val maxResults: Int = 1
    )

    data class VisionResponse(
        val responses: List<AnnotateImageResponse>?
    )

    data class AnnotateImageResponse(
        val textAnnotations: List<TextAnnotation>?,
        val fullTextAnnotation: FullTextAnnotation?
    )

    data class TextAnnotation(
        val description: String?,
        val locale: String?,
        val boundingPoly: BoundingPoly?
    )

    data class BoundingPoly(
        val vertices: List<Vertex>?
    )

    data class Vertex(
        val x: Int?,
        val y: Int?
    )

    data class FullTextAnnotation(
        val text: String?,
        val pages: List<Page>?
    )

    data class Page(
        val blocks: List<Block>?
    )

    data class Block(
        val paragraphs: List<Paragraph>?
    )

    data class Paragraph(
        val words: List<Word>?
    )

    data class Word(
        val symbols: List<Symbol>?
    )

    data class Symbol(
        val text: String?
    )
}
