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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

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
        private const val LINE_THRESHOLD = 15 // Ayni satir icin Y toleransi (piksel)
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
            throw IllegalStateException("Cloud Vision API key not configured")
        }

        val bitmap = loadAndResizeBitmap(imageUri)
        recognizeTextFromBitmap(bitmap)
    }

    suspend fun recognizeText(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            throw IllegalStateException("Cloud Vision API key not configured")
        }

        val resizedBitmap = resizeBitmap(bitmap)
        recognizeTextFromBitmap(resizedBitmap)
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
            throw IllegalStateException("Cloud Vision API key not configured")
        }

        val bitmap = loadAndResizeBitmap(imageUri)
        recognizeTextDetailedFromBitmap(bitmap)
    }

    suspend fun recognizeTextDetailed(bitmap: Bitmap): DetailedOcrResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            throw IllegalStateException("Cloud Vision API key not configured")
        }

        val resizedBitmap = resizeBitmap(bitmap)
        recognizeTextDetailedFromBitmap(resizedBitmap)
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
            throw Exception("Cloud Vision API error: ${response.code}")
        }

        val responseBody = response.body?.string() ?: throw Exception("Empty response")
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
            throw Exception("Cloud Vision API error: ${response.code}")
        }

        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        Log.d(TAG, "Response received, parsing...")

        val visionResponse = gson.fromJson(responseBody, VisionResponse::class.java)

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

    /**
     * Kelimeleri Y koordinatina gore satirlara grupla
     * Ayni satirdaki kelimeler X koordinatina gore siralanir
     * Merkez Y koordinati kullanilarak zincir etkisi onlenir
     */
    private fun groupTextByLines(annotations: List<TextAnnotation>): String {
        // Her kelimenin Y koordinatini ve metnini al
        data class WordWithPosition(
            val text: String,
            val minX: Int,
            val minY: Int,
            val maxY: Int,
            val centerY: Int
        )

        val words = annotations.mapNotNull { annotation ->
            val vertices = annotation.boundingPoly?.vertices
            if (vertices != null && vertices.size >= 4 && annotation.description != null) {
                val minX = vertices.mapNotNull { it.x }.minOrNull() ?: 0
                val minY = vertices.mapNotNull { it.y }.minOrNull() ?: 0
                val maxY = vertices.mapNotNull { it.y }.maxOrNull() ?: 0
                val centerY = (minY + maxY) / 2
                WordWithPosition(annotation.description, minX, minY, maxY, centerY)
            } else {
                null
            }
        }

        if (words.isEmpty()) return ""

        // Kelimeleri centerY'ye gore sirala ve satirlara grupla
        val sortedWords = words.sortedBy { it.centerY }
        val lines = mutableListOf<MutableList<WordWithPosition>>()

        for (word in sortedWords) {
            // Bu kelimenin centerY'si mevcut bir satirin ilk kelimesinin centerY'sine yakin mi?
            val matchingLine = lines.find { line ->
                // Satirin ilk kelimesinin centerY'sini referans al (genislemeyi onle)
                val lineBaseCenterY = line.first().centerY
                abs(word.centerY - lineBaseCenterY) <= LINE_THRESHOLD
            }

            if (matchingLine != null) {
                matchingLine.add(word)
            } else {
                // Yeni satir olustur
                lines.add(mutableListOf(word))
            }
        }

        // Her satiri X koordinatina gore sirala ve birlestir
        val result = StringBuilder()
        for (line in lines.sortedBy { it.first().centerY }) {
            val sortedLineWords = line.sortedBy { it.minX }
            val lineText = sortedLineWords.joinToString(" ") { it.text }
            result.appendLine(lineText)
        }

        Log.d(TAG, "Grouped ${words.size} words into ${lines.size} lines")
        return result.toString()
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
