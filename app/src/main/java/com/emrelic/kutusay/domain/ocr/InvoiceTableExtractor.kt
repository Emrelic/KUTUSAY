package com.emrelic.kutusay.domain.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.abs

/**
 * Fatura tablosu cikarici
 * Koordinat bazli satir ve sutun tespiti ile dogru ilac-miktar eslestirmesi
 * Kirmizi el yazisi ve siyah matbu metin ayirt etme
 */
class InvoiceTableExtractor {

    companion object {
        private const val TAG = "InvoiceTableExtractor"

        // Satir gruplama toleransi - dinamik hesaplanacak
        // Varsayilan deger, gercek deger kelime yuksekligine gore belirlenir
        private const val DEFAULT_ROW_TOLERANCE = 8

        // Sutun gruplama toleransi (piksel)
        private const val COLUMN_TOLERANCE = 50

        // Kirmizi renk tespiti icin esik degerler
        private const val RED_THRESHOLD = 150
        private const val GREEN_BLUE_MAX = 100
    }

    /**
     * Tek bir kelime/metin parcasi
     */
    data class DetectedWord(
        val text: String,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val centerX: Int,
        val centerY: Int,
        val isHandwritten: Boolean = false, // Kirmizi el yazisi mi?
        val confidence: Float = 1.0f
    )

    /**
     * Tablo satiri
     */
    data class TableRow(
        val rowIndex: Int,
        val centerY: Int,
        val words: List<DetectedWord>,
        val locationCode: String?, // 4AD, 6BD, etc.
        val medicineName: String?,
        val quantity: Int?,
        val expiryDate: String?, // Miad (kirmizi el yazisi)
        val isPrinted: Boolean = true // Matbu mu el yazisi mi
    )

    /**
     * Cikarilan fatura tablosu
     */
    data class ExtractedInvoiceTable(
        val rows: List<TableRow>,
        val totalItems: Int?,
        val totalQuantity: Int?,
        val invoiceNo: String?,
        val supplierName: String?,
        val allWords: List<DetectedWord> // Debug icin
    )

    /**
     * Cloud Vision kelime listesinden tablo cikar
     */
    fun extractTable(
        words: List<DetectedWord>,
        bitmap: Bitmap? = null
    ): ExtractedInvoiceTable {
        Log.d(TAG, "=== TABLO CIKARMA BASLADI ===")
        Log.d(TAG, "Toplam kelime sayisi: ${words.size}")

        // Adim 1: Renk tespiti yap (bitmap varsa)
        val wordsWithColor = if (bitmap != null) {
            detectColors(words, bitmap)
        } else {
            words
        }

        // Adim 2: Kelimeleri Y koordinatina gore satirlara grupla
        val rows = groupIntoRows(wordsWithColor)
        Log.d(TAG, "Tespit edilen satir sayisi: ${rows.size}")

        // Adim 3: Her satirdaki kelimeleri X koordinatina gore sirala
        val sortedRows = rows.map { row ->
            row.copy(words = row.words.sortedBy { it.minX })
        }

        // Adim 4: Tablo satirlarini analiz et (konum kodu, ilac, miktar, miad)
        val tableRows = analyzeRows(sortedRows)
        Log.d(TAG, "Analiz edilen tablo satiri: ${tableRows.size}")

        // Adim 5: Fatura bilgilerini cikar
        val invoiceNo = findInvoiceNumber(wordsWithColor)
        val supplierName = findSupplierName(wordsWithColor)
        val (totalItems, totalQty) = findTotals(wordsWithColor)

        Log.d(TAG, "Fatura No: $invoiceNo")
        Log.d(TAG, "Tedarikci: $supplierName")
        Log.d(TAG, "Toplam: $totalItems kalem, $totalQty adet")

        // Ilac satirlarini filtrele ve logla
        val medicineRows = tableRows.filter { it.locationCode != null }
        Log.d(TAG, "=== ILAC SATIRLARI ===")
        medicineRows.forEachIndexed { index, row ->
            Log.d(TAG, "${index + 1}. ${row.locationCode} | ${row.medicineName} | Miktar: ${row.quantity} | Miad: ${row.expiryDate}")
        }

        return ExtractedInvoiceTable(
            rows = tableRows,
            totalItems = totalItems,
            totalQuantity = totalQty,
            invoiceNo = invoiceNo,
            supplierName = supplierName,
            allWords = wordsWithColor
        )
    }

    /**
     * Bitmap uzerinden renk tespiti yap
     * Kirmizi metin = el yazisi (miad notlari)
     */
    private fun detectColors(words: List<DetectedWord>, bitmap: Bitmap): List<DetectedWord> {
        return words.map { word ->
            val isRed = isRedText(bitmap, word)
            word.copy(isHandwritten = isRed)
        }
    }

    /**
     * Belirli bir kelimenin kirmizi olup olmadigini kontrol et
     */
    private fun isRedText(bitmap: Bitmap, word: DetectedWord): Boolean {
        try {
            // Kelimenin merkez noktasindaki rengi al
            val centerX = ((word.minX + word.maxX) / 2).coerceIn(0, bitmap.width - 1)
            val centerY = ((word.minY + word.maxY) / 2).coerceIn(0, bitmap.height - 1)

            // Birden fazla nokta ornekle (daha guvenilir sonuc)
            val samplePoints = listOf(
                Pair(centerX, centerY),
                Pair(word.minX.coerceIn(0, bitmap.width - 1), centerY),
                Pair(word.maxX.coerceIn(0, bitmap.width - 1), centerY)
            )

            var redCount = 0
            for ((x, y) in samplePoints) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Kirmizi: R yuksek, G ve B dusuk
                if (r > RED_THRESHOLD && g < GREEN_BLUE_MAX && b < GREEN_BLUE_MAX) {
                    redCount++
                }
            }

            return redCount >= 2 // En az 2 ornekte kirmizi ise el yazisi
        } catch (e: Exception) {
            Log.w(TAG, "Renk tespiti hatasi: ${e.message}")
            return false
        }
    }

    /**
     * Kelimeleri Y koordinatina gore satirlara grupla
     * Dinamik tolerans: ortalama kelime yuksekliginin yarisi
     */
    private fun groupIntoRows(words: List<DetectedWord>): List<TableRow> {
        if (words.isEmpty()) return emptyList()

        // Dinamik tolerans hesapla: ortalama kelime yuksekliginin yarisi
        val avgWordHeight = words.map { it.maxY - it.minY }.filter { it > 0 }.average()
        val rowTolerance = if (avgWordHeight > 0) (avgWordHeight / 2).toInt().coerceIn(5, 15) else DEFAULT_ROW_TOLERANCE
        Log.d(TAG, "Dinamik satir toleransi: $rowTolerance (ort. kelime yuksekligi: $avgWordHeight)")

        // Y koordinatina gore sirala
        val sortedWords = words.sortedBy { it.centerY }

        val rows = mutableListOf<MutableList<DetectedWord>>()
        val rowCenters = mutableListOf<Int>()

        for (word in sortedWords) {
            // Bu kelime mevcut bir satira ait mi?
            // NOT: Sadece en yakin satiri kontrol et, zincir etkisini onlemek icin
            var foundRow = false
            var bestRowIndex = -1
            var bestDistance = Int.MAX_VALUE

            for (i in rows.indices) {
                val distance = abs(word.centerY - rowCenters[i])
                if (distance <= rowTolerance && distance < bestDistance) {
                    bestDistance = distance
                    bestRowIndex = i
                }
            }

            if (bestRowIndex >= 0) {
                rows[bestRowIndex].add(word)
                // Satir merkezini guncelleme - ilk kelimenin merkezini koru (zincir etkisini onle)
                foundRow = true
            }

            if (!foundRow) {
                // Yeni satir olustur
                rows.add(mutableListOf(word))
                rowCenters.add(word.centerY)
            }
        }

        Log.d(TAG, "${words.size} kelime ${rows.size} satira grupandi")

        // TableRow listesine donustur
        return rows.mapIndexed { index, wordList ->
            TableRow(
                rowIndex = index,
                centerY = rowCenters[index],
                words = wordList.sortedBy { it.minX },
                locationCode = null,
                medicineName = null,
                quantity = null,
                expiryDate = null
            )
        }.sortedBy { it.centerY }
    }

    /**
     * Her satiri analiz et: konum kodu, ilac adi, miktar, miad cikar
     */
    private fun analyzeRows(rows: List<TableRow>): List<TableRow> {
        return rows.map { row ->
            analyzeRow(row)
        }
    }

    /**
     * Tek bir satiri analiz et
     */
    private fun analyzeRow(row: TableRow): TableRow {
        val allText = row.words.joinToString(" ") { it.text }
        val printedText = row.words.filter { !it.isHandwritten }.joinToString(" ") { it.text }
        val handwrittenText = row.words.filter { it.isHandwritten }.joinToString(" ") { it.text }

        Log.d(TAG, "Satir ${row.rowIndex} analiz: '${allText.take(80)}...'")

        // Konum kodu bul - satirda herhangi bir yerde olabilir
        // Formatlar: "4AD-", "4AD -", "4AD", "6BD-", etc.
        var locationCode: String? = null
        var locationCodeIndex = -1

        for ((index, word) in row.words.withIndex()) {
            // Konum kodu formati: 1 rakam + 2 harf (+ opsiyonel tire)
            val codeMatch = Regex("""^(\d[A-Z]{2})[-]?$""", RegexOption.IGNORE_CASE).find(word.text)
            if (codeMatch != null) {
                locationCode = codeMatch.groupValues[1].uppercase()
                locationCodeIndex = index
                Log.d(TAG, "  Konum kodu bulundu: $locationCode (index: $index)")
                break
            }
        }

        // Ilac adi bul (konum kodundan sonraki kelimeler)
        var medicineName: String? = null
        if (locationCode != null && locationCodeIndex >= 0) {
            // Konum kodundan sonraki kelimeleri birleştir
            val wordsAfterCode = row.words.drop(locationCodeIndex + 1)
                .filter { !it.isHandwritten }
                .map { it.text }

            // Ilac formunu iceren kisma kadar al (TB, FTB, CAP, etc. dahil)
            val medicineWords = mutableListOf<String>()
            var foundForm = false

            for (word in wordsAfterCode) {
                medicineWords.add(word)
                // Ilac formu bulduk mu?
                if (Regex("""(TB|FTB|CAP|POM|SRP|AMP|SUSP)\.?$""", RegexOption.IGNORE_CASE).containsMatchIn(word)) {
                    foundForm = true
                    break
                }
                // Miktar veya fiyat gibi gorunuyorsa dur
                if (word.toDoubleOrNull() != null || Regex("""^\d{1,3}[.,]\d{2}$""").matches(word)) {
                    medicineWords.removeLast()
                    break
                }
            }

            if (medicineWords.isNotEmpty()) {
                medicineName = medicineWords.joinToString(" ")
                Log.d(TAG, "  Ilac adi: $medicineName")
            }
        }

        // Miktar bul (matbu metinden)
        val quantity = extractQuantityFromRow(row)
        Log.d(TAG, "  Miktar: $quantity")

        // Miad bul (kirmizi el yazisi veya miktar yanindaki tarih)
        val expiryDate = extractExpiryDate(row, handwrittenText)
        Log.d(TAG, "  Miad: $expiryDate")

        return row.copy(
            locationCode = locationCode,
            medicineName = medicineName,
            quantity = quantity,
            expiryDate = expiryDate
        )
    }

    /**
     * Satirdan miktar cikar
     * Promosyonlu (10+1) ve normal miktarlari destekler
     */
    private fun extractQuantityFromRow(row: TableRow): Int? {
        // Sadece matbu (siyah) metinlerden miktar cikar
        val printedWords = row.words.filter { !it.isHandwritten }
        val printedText = printedWords.joinToString(" ") { it.text }

        // Pattern 1: Promosyonlu miktar "10+1" veya "5+1"
        val promoMatch = Regex("""(\d{1,2})\+(\d{1,2})""").find(printedText)
        if (promoMatch != null) {
            val base = promoMatch.groupValues[1].toIntOrNull() ?: 0
            val promo = promoMatch.groupValues[2].toIntOrNull() ?: 0
            if (base in 1..50 && promo in 1..10) {
                return base + promo
            }
        }

        // Pattern 2: Tek basina miktar (konum kodundan sonra, fiyattan once)
        // Satirda sag tarafta bulunan 1-2 haneli sayilar
        for (word in printedWords.sortedByDescending { it.minX }) {
            val num = word.text.toIntOrNull()
            if (num != null && num in 1..50) {
                // Bu bir fiyat degil miktar mi?
                // Fiyatlar genelde ondalik icerir veya buyuk sayilardir
                if (!word.text.contains(".") && !word.text.contains(",")) {
                    return num
                }
            }
        }

        return null
    }

    /**
     * Satirdan miad (son kullanma tarihi) cikar
     * Genelde kirmizi el yazisi ile yazilir
     */
    private fun extractExpiryDate(row: TableRow, handwrittenText: String): String? {
        // Oncelik: Kirmizi el yazisi
        val dateMatch = Regex("""(\d{1,2}/\d{2})""").find(handwrittenText)
        if (dateMatch != null) {
            return dateMatch.value
        }

        // Fallback: Tum metinden tarih formatini ara
        val allText = row.words.joinToString(" ") { it.text }
        val allDateMatch = Regex("""(\d{1,2}/\d{2})""").find(allText)
        return allDateMatch?.value
    }

    /**
     * Fatura numarasini bul
     */
    private fun findInvoiceNumber(words: List<DetectedWord>): String? {
        val allText = words.joinToString(" ") { it.text }

        // B2K ile baslayan fatura numarasi
        val b2kMatch = Regex("""B2K\d{10,}""").find(allText)
        if (b2kMatch != null) return b2kMatch.value

        // E-Arsiv No
        val earsivMatch = Regex("""E-?Ar[sş]iv\s*No\s*:?\s*([A-Z0-9]+)""", RegexOption.IGNORE_CASE).find(allText)
        return earsivMatch?.groupValues?.getOrNull(1)
    }

    /**
     * Tedarikci adini bul
     */
    private fun findSupplierName(words: List<DetectedWord>): String? {
        val allText = words.joinToString(" ") { it.text }

        if (allText.contains("Selçuk", ignoreCase = true) || allText.contains("Selcuk", ignoreCase = true)) {
            return "Selçuk Ecza Deposu"
        }

        val match = Regex("""([A-ZÇĞİÖŞÜa-zçğıöşü]+\s+)?[Ee]cza\s*[Dd]eposu""").find(allText)
        return match?.value?.trim()
    }

    /**
     * TOPLAM X KALEM, Y ADETTIR bilgisini bul
     */
    private fun findTotals(words: List<DetectedWord>): Pair<Int?, Int?> {
        val allText = words.joinToString(" ") { it.text }

        // "TOPLAM 15 KALEM, 158 ADETTIR"
        val match = Regex("""TOPLAM\s*(\d+)\s*KALEM[,.\s]*(\d+)\s*ADET""", RegexOption.IGNORE_CASE).find(allText)
        if (match != null) {
            val items = match.groupValues[1].toIntOrNull()
            val qty = match.groupValues[2].toIntOrNull()
            return Pair(items, qty)
        }

        // Alternatif format
        val altMatch = Regex("""(\d+)\s*KALEM[,.\s]*(\d+)\s*ADET""", RegexOption.IGNORE_CASE).find(allText)
        if (altMatch != null) {
            val items = altMatch.groupValues[1].toIntOrNull()
            val qty = altMatch.groupValues[2].toIntOrNull()
            return Pair(items, qty)
        }

        return Pair(null, null)
    }
}
