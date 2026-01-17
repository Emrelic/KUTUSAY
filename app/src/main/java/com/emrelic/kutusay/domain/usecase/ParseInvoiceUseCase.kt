package com.emrelic.kutusay.domain.usecase

import android.net.Uri
import android.util.Log
import com.emrelic.kutusay.data.model.InvoiceItem
import com.emrelic.kutusay.domain.ocr.CloudVisionService
import com.emrelic.kutusay.domain.ocr.InvoiceTableExtractor
import com.emrelic.kutusay.domain.ocr.TextRecognitionService
import javax.inject.Inject

data class ParsedInvoice(
    val invoiceNo: String?,
    val supplierName: String?,
    val items: List<InvoiceItem>,
    val rawText: String,
    val totalQuantity: Int,
    val totalAmount: Double?,
    val declaredItemCount: Int?,
    val declaredTotalQty: Int?
)

class ParseInvoiceUseCase @Inject constructor(
    private val textRecognitionService: TextRecognitionService,
    private val cloudVisionService: CloudVisionService
) {
    private val tableExtractor = InvoiceTableExtractor()
    companion object {
        private const val TAG = "ParseInvoiceUseCase"

        // Bilinen ilac isimleri - OCR'dan bagimsiz eslestirme icin
        // NOT: CO-DIOVAN ve DIOVAN ayni ilac, sadece CO-DIOVAN kullan
        private val KNOWN_MEDICINES = listOf(
            "APRANAX", "AZITRO", "CO-DIOVAN", "CODIOVAN",
            "DELIX", "DEPAKIN", "DEVIT", "ECOPIRIN", "EFEXOR",
            "ENDOL", "HAMETAN", "NORVASC", "NOTUSS", "TARDEN", "CARDURA",
            "ARVELES", "AUGMENTIN", "CIPRO", "KLACID", "MAJEZIK",
            "PAROL", "TYLOL", "VERMIDON", "XANAX", "PROZAC"
        )

        // Ilac formlari
        private val MEDICINE_FORMS = listOf(
            "TB", "TABLET", "TAB", "FTB", "FT", "FILM",
            "CAP", "KAP", "KAPSUL", "KAPSÜL",
            "AMP", "AMPUL", "FLK", "FLAKON",
            "SRP", "SURUP", "ŞURUP", "SUSP",
            "DML", "DAMLA", "DROP",
            "JEL", "GEL", "KREM", "CREAM", "POM", "POMAD",
            "ENJ", "INJ", "ENJEKTABL",
            "SPREY", "SPRAY", "SOL", "SOLÜSYON",
            "SASE", "ŞASE", "SACHET"
        )

        // Toplam satiri paterni - farkli formatlari ve OCR hatalarini destekle
        // "TOPLAM 15 KALEM, 158 ADETTIR" veya "ADFTTIR" (OCR hatasi)
        private val TOTAL_LINE_PATTERN = Regex(
            """TOPLAM\s*(\d+)\s*KALEM[,.\s]*(\d+)\s*AD[EF][TI]?T[TI]?[IR]""",
            RegexOption.IGNORE_CASE
        )
        // Alternatif: "15 KALEM, 158 ADETTIR" (TOPLAM ayri satirda)
        private val TOTAL_LINE_ALT_PATTERN = Regex(
            """(\d+)\s*KALEM[,.\s]*(\d+)\s*AD[EF][TI]?T[TI]?[IR]""",
            RegexOption.IGNORE_CASE
        )
        // Ters siralama (OCR karisik): "158 KALEM 15 TOPLAM" veya ". 158 , KALEM 15 TOPLAM"
        private val TOTAL_LINE_REVERSE_PATTERN = Regex(
            """[.,\s]*(\d+)\s*[.,\s]*KALEM\s*(\d+)\s*TOPLAM""",
            RegexOption.IGNORE_CASE
        )

        // Yer kodu paterni: 1-2 rakam + 2 harf + tire/bosluk + ilac adi
        // Ornekler: "4AD- APRANAX", "6BD-APRANAX", "2CE- CO-DIOVAN"
        private val LOCATION_CODE_PATTERN = Regex(
            """^(\d[A-Z]{2}[-\s]+)(.+)$""",
            RegexOption.IGNORE_CASE
        )

        // Miktar + miad birlestik paterni: "2504/30" veya "25 04/30" -> miktar=25, miad=04/30
        // Veya promosyonlu: "10+108/28" veya "10+1 08/28" -> miktar=11, miad=08/28
        private val QTY_MIAD_PATTERN = Regex(
            """(\d{1,2})(\+(\d{1,2}))?\s*(\d{1,2}/\d{2})""",
            RegexOption.IGNORE_CASE
        )

        // Sadece miktar + promosyon paterni: "10+1", "5+1"
        private val QTY_PROMO_PATTERN = Regex("""^(\d{1,2})\+(\d{1,2})$""")

        // Miad paterni: XX/YY (ay/yil)
        private val MIAD_PATTERN = Regex("""\d{1,2}/\d{2}""")
    }

    /**
     * YENI: Koordinat bazli tablo cikarimi ile fatura parse et
     * Kirmizi el yazisi ve siyah matbu metni ayirt eder
     * Her kelimeyi koordinatiyla tespit eder ve dogru satirlara yerlestirir
     */
    suspend fun execute(imageUri: Uri): Result<ParsedInvoice> {
        return try {
            Log.d(TAG, "=== KOORDINAT BAZLI TABLO CIKARIMI BASLADI ===")

            // Detayli OCR sonucu al (koordinatlarla birlikte)
            val detailedResult = cloudVisionService.recognizeTextDetailed(imageUri)
            Log.d(TAG, "OCR tamamlandi: ${detailedResult.words.size} kelime tespit edildi")

            if (detailedResult.words.isEmpty()) {
                Log.w(TAG, "Kelime bulunamadi, klasik yonteme donuluyor...")
                return executeClassic(imageUri)
            }

            // Tablo cikarimi yap (renk tespiti dahil)
            val extractedTable = tableExtractor.extractTable(
                words = detailedResult.words,
                bitmap = detailedResult.bitmap
            )

            // Ilac satirlarini filtrele
            val medicineRows = extractedTable.rows.filter { it.locationCode != null && it.medicineName != null }

            // Koordinat bazli sonuclari dogrula
            val isValidResult = validateCoordinateResults(medicineRows, extractedTable)

            if (!isValidResult) {
                Log.w(TAG, "Koordinat bazli sonuclar gecersiz, klasik yonteme donuluyor...")
                return executeClassic(imageUri)
            }

            // InvoiceItem listesine donustur
            val items = medicineRows.map { row ->
                InvoiceItem(
                    id = 0,
                    invoiceId = 0,
                    name = row.medicineName ?: "Bilinmeyen",
                    quantity = row.quantity ?: 1,
                    unit = "kutu",
                    unitPrice = null,
                    totalPrice = null
                )
            }

            val totalQuantity = extractedTable.totalQuantity ?: items.sumOf { it.quantity }

            val parsedInvoice = ParsedInvoice(
                invoiceNo = extractedTable.invoiceNo,
                supplierName = extractedTable.supplierName,
                items = items,
                rawText = detailedResult.rawText,
                totalQuantity = totalQuantity,
                totalAmount = null,
                declaredItemCount = extractedTable.totalItems,
                declaredTotalQty = extractedTable.totalQuantity
            )

            Log.d(TAG, "=== KOORDINAT BAZLI PARSING SONUCU ===")
            Log.d(TAG, "Items: ${parsedInvoice.items.size}")
            Log.d(TAG, "Declared: ${parsedInvoice.declaredItemCount} items, ${parsedInvoice.declaredTotalQty} qty")
            Log.d(TAG, "Bulunan toplam: ${items.sumOf { it.quantity }}")
            parsedInvoice.items.forEach { item ->
                Log.d(TAG, "  - ${item.name}: ${item.quantity}")
            }

            Result.success(parsedInvoice)
        } catch (e: Exception) {
            Log.e(TAG, "Koordinat bazli parsing hatasi, klasik yonteme donuluyor...", e)
            executeClassic(imageUri)
        }
    }

    /**
     * Koordinat bazli sonuclari dogrula
     * - Yeterli sayida ilac bulundu mu?
     * - Ilac isimleri mantikli mi (konum kodu icermiyor mu)?
     * - Beyan edilen degerlerle uyumlu mu?
     */
    private fun validateCoordinateResults(
        medicineRows: List<InvoiceTableExtractor.TableRow>,
        extractedTable: InvoiceTableExtractor.ExtractedInvoiceTable
    ): Boolean {
        // Kural 1: En az 5 ilac bulmali (tipik fatura 10-20 kalem)
        if (medicineRows.size < 5) {
            Log.d(TAG, "Validation failed: only ${medicineRows.size} medicines found")
            return false
        }

        // Kural 2: Beyan edilen degerle karsilastir
        val declaredItems = extractedTable.totalItems
        if (declaredItems != null && declaredItems > 0) {
            // Bulunan ilac sayisi, beyan edilenin %50'sinden az olmamali
            if (medicineRows.size < declaredItems * 0.5) {
                Log.d(TAG, "Validation failed: found ${medicineRows.size} but declared $declaredItems")
                return false
            }
        }

        // Kural 3: Ilac isimleri konum kodu formatinda olmamali
        // Ornek: "68D- 5AB- ZCE-" gibi isimler hatali gruplama gosterir
        val invalidNames = medicineRows.count { row ->
            val name = row.medicineName ?: ""
            // Konum kodu paterni: Rakam + 2 Harf + Tire
            val locationCodePattern = Regex("""\d[A-Z]{2}[-\s]""", RegexOption.IGNORE_CASE)
            locationCodePattern.findAll(name).count() > 1 // Birden fazla konum kodu varsa
        }
        if (invalidNames > medicineRows.size * 0.3) {
            Log.d(TAG, "Validation failed: $invalidNames/${medicineRows.size} names look like location codes")
            return false
        }

        // Kural 4: En az bir ilac ismi bilinen ilac listesinde olmali
        val hasKnownMedicine = medicineRows.any { row ->
            val name = (row.medicineName ?: "").uppercase()
            KNOWN_MEDICINES.any { known ->
                name.contains(known.uppercase().take(5))
            }
        }
        if (!hasKnownMedicine) {
            Log.d(TAG, "Validation failed: no known medicines found in results")
            return false
        }

        Log.d(TAG, "Validation passed: ${medicineRows.size} valid medicines")
        return true
    }

    /**
     * Klasik (eski) yontem - yedek olarak kullanilir
     */
    private suspend fun executeClassic(imageUri: Uri): Result<ParsedInvoice> {
        return try {
            val rawText = textRecognitionService.recognizeText(imageUri)
            Log.d(TAG, "=== KLASIK OCR RAW TEXT START ===")
            Log.d(TAG, rawText)
            Log.d(TAG, "=== KLASIK OCR RAW TEXT END ===")

            if (rawText.isBlank()) {
                return Result.failure(Exception("Metin bulunamadi"))
            }

            val parsedInvoice = parseInvoiceText(rawText)
            Log.d(TAG, "=== KLASIK PARSING RESULT ===")
            Log.d(TAG, "Items: ${parsedInvoice.items.size}")
            Log.d(TAG, "Declared: ${parsedInvoice.declaredItemCount} items, ${parsedInvoice.declaredTotalQty} qty")
            parsedInvoice.items.forEach { item ->
                Log.d(TAG, "  - ${item.name}: ${item.quantity}")
            }
            Result.success(parsedInvoice)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing invoice", e)
            Result.failure(e)
        }
    }

    private fun parseInvoiceText(text: String): ParsedInvoice {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }

        // Fatura numarasini bul
        val invoiceNo = findInvoiceNumber(text)
        Log.d(TAG, "Invoice No: $invoiceNo")

        // Tedarikci adini bul
        val supplierName = findSupplierName(text)
        Log.d(TAG, "Supplier: $supplierName")

        // TOPLAM X KALEM, Y ADETTİR satirindan bilgi cek
        val (declaredItemCount, declaredTotalQty) = extractDeclaredTotals(text)
        Log.d(TAG, "Declared: $declaredItemCount items, $declaredTotalQty qty")

        // Yontem 1: Yer kodlu satirlardan ilac bul
        val medicinesFromLocationCode = extractMedicinesWithLocationCode(lines)
        Log.d(TAG, "Found ${medicinesFromLocationCode.size} medicines with location code")

        // Yontem 2: Bilinen ilac isimlerini ara
        val medicinesFromKnown = findKnownMedicines(text, medicinesFromLocationCode)
        Log.d(TAG, "Found ${medicinesFromKnown.size} additional known medicines")

        // Tum ilaclari birlestir (dozaj bilgisini de dahil et)
        val allMedicines = (medicinesFromLocationCode + medicinesFromKnown).distinctBy {
            it.name.uppercase().replace(Regex("""[^A-Z0-9]"""), "")
        }
        Log.d(TAG, "Total unique medicines: ${allMedicines.size}")

        // YENI: Satir bazli miktar eslestirme - her ilac icin kendi satirindan miktar bul
        val items = extractQuantitiesPerMedicine(text, allMedicines, declaredTotalQty)
        Log.d(TAG, "Line-based quantity extraction complete")

        val totalQuantity = declaredTotalQty ?: items.sumOf { it.quantity }

        return ParsedInvoice(
            invoiceNo = invoiceNo,
            supplierName = supplierName,
            items = items,
            rawText = text,
            totalQuantity = totalQuantity,
            totalAmount = null,
            declaredItemCount = declaredItemCount,
            declaredTotalQty = declaredTotalQty
        )
    }

    /**
     * Yer kodlu satirlardan ilac isimlerini cikar
     * Ornek: "4AD- APRANAX 275 MG 20 FTB." -> "APRANAX 275 MG 20 FTB"
     */
    private fun extractMedicinesWithLocationCode(lines: List<String>): List<InvoiceItem> {
        val items = mutableListOf<InvoiceItem>()
        val processedNames = mutableSetOf<String>()

        for (line in lines) {
            // Yer kodu ile baslayan satir mi? (ornek: "4AD- APRANAX")
            val locationMatch = LOCATION_CODE_PATTERN.find(line)
            if (locationMatch != null) {
                var medicinePart = locationMatch.groupValues[2].trim()

                // Ilac formu iceriyorsa devam et
                if (containsMedicineForm(medicinePart)) {
                    val cleanName = cleanMedicineName(medicinePart)
                    if (cleanName.length >= 3) {
                        // Dozaj bilgisini de dahil et (ayni ilac farkli dozaj olabilir)
                        // Ornek: APRANAX 275 MG ve APRANAX 550 MG farkli
                        val normalized = cleanName.uppercase().replace(Regex("""[^A-Z0-9]"""), "")
                        if (!processedNames.contains(normalized)) {
                            processedNames.add(normalized)
                            items.add(createInvoiceItem(cleanName, 1))
                            Log.d(TAG, "Found medicine (location code): $cleanName")
                        }
                    }
                }
                continue
            }

            // Alternatif: Yer kodu ayirici olmadan (ornek: "4ADAPRANAX")
            val altMatch = Regex("""^(\d[A-Z]{2})([A-Z]{3,}.*)""", RegexOption.IGNORE_CASE).find(line)
            if (altMatch != null) {
                var medicinePart = altMatch.groupValues[2].trim()
                if (containsMedicineForm(medicinePart)) {
                    val cleanName = cleanMedicineName(medicinePart)
                    if (cleanName.length >= 3) {
                        val normalized = cleanName.uppercase().replace(Regex("""[^A-Z0-9]"""), "")
                        if (!processedNames.contains(normalized)) {
                            processedNames.add(normalized)
                            items.add(createInvoiceItem(cleanName, 1))
                            Log.d(TAG, "Found medicine (alt pattern): $cleanName")
                        }
                    }
                }
            }
        }

        return items
    }

    /**
     * Bilinen ilac isimlerini OCR metninde ara
     */
    private fun findKnownMedicines(text: String, alreadyFound: List<InvoiceItem>): List<InvoiceItem> {
        val items = mutableListOf<InvoiceItem>()
        val upperText = text.uppercase()
        val alreadyFoundNames = alreadyFound.map {
            it.name.uppercase().replace(Regex("""[^A-Z]"""), "").take(6)
        }.toSet()

        // CO-DIOVAN ozel kontrolu - OCR bazen "- CO" ve "DIOVAN" ayri yazar
        val hasCoDiovan = upperText.contains("CO-DIOVAN") ||
                          upperText.contains("CODIOVAN") ||
                          upperText.contains("CO DIOVAN") ||
                          (upperText.contains("- CO") && upperText.contains("DIOVAN"))

        for (medicine in KNOWN_MEDICINES) {
            val searchName = medicine.uppercase().replace("-", "")
            val normalized = searchName.take(6)

            // Zaten bulunmus mu?
            if (alreadyFoundNames.any { it.contains(normalized) || normalized.contains(it) }) {
                continue
            }

            // CO-DIOVAN ozel durumu
            if (medicine == "CO-DIOVAN" || medicine == "CODIOVAN") {
                if (hasCoDiovan) {
                    items.add(createInvoiceItem("CO-DIOVAN", 1))
                    Log.d(TAG, "Found known medicine: CO-DIOVAN")
                }
                continue
            }

            // Metinde var mi?
            if (upperText.contains(searchName) || upperText.contains(medicine.uppercase())) {
                // APRANAX gibi coklu dozaj olabilecek ilaclar icin tum varyantlari bul
                val variants = findAllMedicineVariants(text, medicine)
                for (variant in variants) {
                    // Bu varyant zaten eklenmis mi kontrol et
                    val variantNorm = variant.uppercase().replace(Regex("""[^A-Z0-9]"""), "")
                    val alreadyAdded = items.any {
                        it.name.uppercase().replace(Regex("""[^A-Z0-9]"""), "") == variantNorm
                    }
                    if (!alreadyAdded) {
                        items.add(createInvoiceItem(variant, 1))
                        Log.d(TAG, "Found known medicine: $variant")
                    }
                }
            }
        }

        return items
    }

    /**
     * Bilinen ilac adinin tum varyantlarini metinden bul
     * APRANAX 275 MG ve APRANAX 550 MG gibi farkli dozajlari ayri ayri bulur
     */
    private fun findFullMedicineName(text: String, baseName: String): String? {
        // Ilac adini iceren TUM eslestirmeleri bul
        val pattern = Regex(
            """(?i)$baseName\s*(\d+\s*)?(MG|ML|GR|IU)?[^\n]*?(TB|FTB|CAP|POM|SRP|SUSP|AMP)\.?""",
            RegexOption.IGNORE_CASE
        )
        val matches = pattern.findAll(text).toList()

        if (matches.isEmpty()) {
            return baseName
        }

        // Ilk eslestirmeyi dondur
        return cleanMedicineName(matches.first().value)
    }

    /**
     * Bilinen ilacin tum dozaj varyantlarini bul
     * Ornek: APRANAX icin hem 275 MG hem 550 MG varyantlarini bulur
     */
    private fun findAllMedicineVariants(text: String, baseName: String): List<String> {
        val variants = mutableListOf<String>()
        val pattern = Regex(
            """(?i)$baseName\s+(\d+)\s*(MG|ML|GR|IU)""",
            RegexOption.IGNORE_CASE
        )

        pattern.findAll(text).forEach { match ->
            val dosage = match.groupValues[1]
            val unit = match.groupValues[2].uppercase()
            val variantName = "$baseName $dosage $unit"
            if (!variants.any { it.contains(dosage) }) {
                variants.add(variantName)
            }
        }

        return if (variants.isEmpty()) listOf(baseName) else variants
    }

    /**
     * OCR metninden miktarlari cikar
     * Fatura formati: Her satir sonunda miktar + miad (XX/YY) var
     */
    private fun extractQuantitiesFromText(text: String, expectedCount: Int, declaredTotal: Int?): List<Int> {
        val qtyMiadMatches = mutableListOf<Triple<Int, String, Int>>() // qty, match, position

        // Pattern 1: Promosyonlu bitisik - "10+108/28" -> 10+1=11, miad=08/28
        val promoPattern = Regex("""(\d{1,2})\+(\d)(\d{2}/\d{2})""")
        promoPattern.findAll(text).forEach { match ->
            val base = match.groupValues[1].toIntOrNull() ?: 0
            val promo = match.groupValues[2].toIntOrNull() ?: 0
            val total = base + promo
            if (total in 1..100) {
                qtyMiadMatches.add(Triple(total, match.value, match.range.first))
                Log.d(TAG, "Found promo: ${match.value} -> $base+$promo=$total")
            }
        }

        // Pattern 2: Promosyonlu bosluklu - "10+1 08/28"
        val promoSpacePattern = Regex("""(\d{1,2})\+(\d{1,2})\s+(\d{1,2}/\d{2})""")
        promoSpacePattern.findAll(text).forEach { match ->
            val base = match.groupValues[1].toIntOrNull() ?: 0
            val promo = match.groupValues[2].toIntOrNull() ?: 0
            val total = base + promo
            if (total in 1..100) {
                val exists = qtyMiadMatches.any { kotlin.math.abs(it.third - match.range.first) < 5 }
                if (!exists) {
                    qtyMiadMatches.add(Triple(total, match.value, match.range.first))
                    Log.d(TAG, "Found promo space: ${match.value} -> $total")
                }
            }
        }

        // Pattern 3: Ayrik miktar + miad - "25 04/30", "20 10/28", "15 7/28"
        val separatePattern = Regex("""(\d{1,2})\s+(\d{1,2}/\d{2})""")
        separatePattern.findAll(text).forEach { match ->
            val qty = match.groupValues[1].toIntOrNull() ?: 0
            val miad = match.groupValues[2]
            val ayYil = miad.split("/")
            if (ayYil.size == 2) {
                val ay = ayYil[0].toIntOrNull() ?: 0
                val yil = ayYil[1].toIntOrNull() ?: 0
                // Miad kontrolu: ay 1-12, yil 24-35
                if (ay in 1..12 && yil in 24..35 && qty in 1..50) {
                    val exists = qtyMiadMatches.any { kotlin.math.abs(it.third - match.range.first) < 5 }
                    if (!exists) {
                        qtyMiadMatches.add(Triple(qty, match.value, match.range.first))
                        Log.d(TAG, "Found separate: ${match.value} -> qty=$qty")
                    }
                }
            }
        }

        // Pattern 4: Birlesmis miktar+miad - "2504/30" -> qty=25, miad=04/30
        val mergedPattern = Regex("""(\d{1,2})(\d{2}/\d{2})""")
        mergedPattern.findAll(text).forEach { match ->
            val qty = match.groupValues[1].toIntOrNull() ?: 0
            val miad = match.groupValues[2]
            val ayYil = miad.split("/")
            if (ayYil.size == 2) {
                val ay = ayYil[0].toIntOrNull() ?: 0
                val yil = ayYil[1].toIntOrNull() ?: 0
                if (ay in 1..12 && yil in 24..35 && qty in 1..50) {
                    val exists = qtyMiadMatches.any { kotlin.math.abs(it.third - match.range.first) < 5 }
                    if (!exists) {
                        qtyMiadMatches.add(Triple(qty, match.value, match.range.first))
                        Log.d(TAG, "Found merged: ${match.value} -> qty=$qty")
                    }
                }
            }
        }

        // Pattern 5: OCR hatali miad - "25 c2/30" (c yerine 0 olmali) veya "8 40/79" gibi
        val ocrErrorPattern = Regex("""(\d{1,2})\s+[a-zA-Z]?\d/\d{2}""")
        ocrErrorPattern.findAll(text).forEach { match ->
            val qtyMatch = Regex("""^(\d{1,2})""").find(match.value)
            val qty = qtyMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (qty in 1..50) {
                val exists = qtyMiadMatches.any { kotlin.math.abs(it.third - match.range.first) < 5 }
                if (!exists) {
                    qtyMiadMatches.add(Triple(qty, match.value, match.range.first))
                    Log.d(TAG, "Found OCR error pattern: ${match.value} -> qty=$qty")
                }
            }
        }

        // Pattern 6: Satir sonunda tek sayi - satirlarda "Tutar" sütunundan once gelen miktar
        // Ornek satirlar: "20 (/2 1,884.20" -> 20
        val lineEndQtyPattern = Regex("""^(\d{1,2})\s+[\(\[/]""")
        text.lines().forEach { line ->
            lineEndQtyPattern.find(line.trim())?.let { match ->
                val qty = match.groupValues[1].toIntOrNull() ?: 0
                if (qty in 1..50) {
                    val exists = qtyMiadMatches.any { it.first == qty }
                    if (!exists) {
                        qtyMiadMatches.add(Triple(qty, match.value, 0))
                        Log.d(TAG, "Found line start qty: ${match.value} -> qty=$qty")
                    }
                }
            }
        }

        // Pozisyona gore sirala
        qtyMiadMatches.sortBy { it.third }
        val quantities = qtyMiadMatches.map { it.first }

        Log.d(TAG, "All quantities found (${quantities.size}): $quantities")
        return quantities
    }

    /**
     * YENI: Sira bazli miktar eslestirme
     * OCR'daki miktar satirlarini cikarip ilaclara sirayla atar
     * Selcuk Ecza fatura formati: ilac satirlari ve miktar satirlari ayri
     */
    private fun extractQuantitiesPerMedicine(
        text: String,
        medicines: List<InvoiceItem>,
        declaredTotalQty: Int?
    ): List<InvoiceItem> {
        val lines = text.lines()

        // Adim 1: Tum miktar satirlarini bul (miad iceren satirlar)
        val quantityLines = mutableListOf<Pair<Int, String>>() // qty, line

        for (line in lines) {
            val qty = extractQuantityFromLine(line, "")
            if (qty > 0) {
                quantityLines.add(Pair(qty, line))
                Log.d(TAG, "Found quantity line: $qty from '${line.take(40)}...'")
            }
        }

        Log.d(TAG, "Total quantity lines found: ${quantityLines.size}")
        Log.d(TAG, "Quantities: ${quantityLines.map { it.first }}")

        // Adim 2: Miktarlari ilaclara sirali ata
        val result = mutableListOf<InvoiceItem>()
        var totalFound = 0

        for ((index, medicine) in medicines.withIndex()) {
            val quantity = if (index < quantityLines.size) {
                quantityLines[index].first
            } else {
                // Miktar bulunamadi, 1 ata
                Log.d(TAG, "No qty for ${medicine.name} at index $index, defaulting to 1")
                1
            }

            Log.d(TAG, "Assigned qty for ${medicine.name}: $quantity")
            totalFound += quantity
            result.add(medicine.copy(quantity = quantity))
        }

        // Toplam kontrolu
        if (declaredTotalQty != null && totalFound != declaredTotalQty) {
            Log.d(TAG, "Quantity mismatch: found $totalFound, declared $declaredTotalQty")

            // Fark varsa ve kucukse, 1 atanmis olanlara dagit
            val diff = declaredTotalQty - totalFound
            if (diff > 0) {
                val onesCount = result.count { it.quantity == 1 }
                if (onesCount > 0) {
                    val extraPerItem = diff / onesCount
                    val remainder = diff % onesCount
                    var extraIndex = 0
                    return result.map { item ->
                        if (item.quantity == 1) {
                            val extra = extraPerItem + if (extraIndex < remainder) 1 else 0
                            extraIndex++
                            item.copy(quantity = 1 + extra)
                        } else item
                    }
                }
            }
        }

        return result
    }

    /**
     * Bir satirdan miktar cikar
     * SADECE miktar+miad paterniyle eslesen degerler alinir
     * Konum kodlari (4AD, 5AB vs) ve diger sayilar atlanir
     */
    private fun extractQuantityFromLine(line: String, medicineName: String): Int {
        // Konum kodu iceren satir mi? (ornek: "4AD- APRANAX")
        // Bunlardan miktar cikarma - miktar ayri satirda
        if (Regex("""^\d[A-Z]{2}[-\s]""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
            return 0 // Miktar bu satirda degil
        }

        // Pattern 1: Promosyonlu + miad - "10+108/28" veya "5+109/28"
        val promoMiadMatch = Regex("""(\d{1,2})\+(\d)(\d{2}/\d{2})""").find(line)
        if (promoMiadMatch != null) {
            val base = promoMiadMatch.groupValues[1].toIntOrNull() ?: 0
            val promo = promoMiadMatch.groupValues[2].toIntOrNull() ?: 0
            if (base in 1..50 && promo in 1..10) {
                return base + promo
            }
        }

        // Pattern 2: Promosyonlu bosluklu - "10+1 08/28"
        val promoSpaceMatch = Regex("""(\d{1,2})\+(\d{1,2})\s+(\d{1,2}/\d{2})""").find(line)
        if (promoSpaceMatch != null) {
            val base = promoSpaceMatch.groupValues[1].toIntOrNull() ?: 0
            val promo = promoSpaceMatch.groupValues[2].toIntOrNull() ?: 0
            if (base in 1..50 && promo in 1..10) {
                return base + promo
            }
        }

        // Pattern 3: Miktar + miad birlestik - "2502/30" (miktar=25)
        val mergedMatch = Regex("""(\d{1,2})(\d{2}/\d{2})""").find(line)
        if (mergedMatch != null) {
            val qty = mergedMatch.groupValues[1].toIntOrNull() ?: 0
            val miad = mergedMatch.groupValues[2]
            val parts = miad.split("/")
            if (parts.size == 2) {
                val month = parts[0].toIntOrNull() ?: 0
                if (month in 1..12 && qty in 1..50) {
                    return qty
                }
            }
        }

        // Pattern 4: Miktar + bosluk + miad - "25 02/30" veya "10 10/28"
        val separateMatch = Regex("""(\d{1,2})\s+(\d{1,2}/\d{2})""").find(line)
        if (separateMatch != null) {
            val qty = separateMatch.groupValues[1].toIntOrNull() ?: 0
            val miad = separateMatch.groupValues[2]
            val parts = miad.split("/")
            if (parts.size == 2) {
                val month = parts[0].toIntOrNull() ?: 0
                if (month in 1..12 && qty in 1..50) {
                    return qty
                }
            }
        }

        return 0 // Miad olmadan miktar kabul etme
    }

    /**
     * Ilac adinin yakinindaki metinden miktar cikar
     */
    private fun extractQuantityNearMedicine(text: String, medicineName: String): Int {
        val baseName = medicineName.split(" ").firstOrNull()?.uppercase() ?: return 0
        val index = text.uppercase().indexOf(baseName)
        if (index == -1) return 0

        // Ilac adindan sonraki 50 karaktere bak
        val endIndex = minOf(index + baseName.length + 50, text.length)
        val context = text.substring(index, endIndex)

        return extractQuantityFromLine(context, medicineName)
    }

    /**
     * Ilaclara miktar ata (eski yontem - yedek)
     */
    private fun assignQuantities(
        items: List<InvoiceItem>,
        quantities: List<Int>,
        declaredItemCount: Int?,
        declaredTotalQty: Int?
    ): List<InvoiceItem> {
        if (items.isEmpty()) return items

        // Eger yeterli miktar varsa sirali ata
        if (quantities.size >= items.size) {
            return items.mapIndexed { index, item ->
                item.copy(quantity = quantities[index])
            }
        }

        // Miktar eksikse declared total'dan dagitr
        if (declaredTotalQty != null && declaredTotalQty > 0) {
            val foundSum = quantities.sum()
            val remaining = declaredTotalQty - foundSum
            val unassignedCount = items.size - quantities.size

            if (unassignedCount > 0 && remaining > 0) {
                val avgForRemaining = remaining / unassignedCount
                val remainder = remaining % unassignedCount

                return items.mapIndexed { index, item ->
                    when {
                        index < quantities.size -> item.copy(quantity = quantities[index])
                        else -> {
                            val extra = if (index - quantities.size < remainder) 1 else 0
                            item.copy(quantity = (avgForRemaining + extra).coerceAtLeast(1))
                        }
                    }
                }
            }
        }

        // Hicbir bilgi yoksa esit dagit veya 1 ata
        val avgQty = (declaredTotalQty ?: items.size) / items.size
        return items.mapIndexed { index, item ->
            if (index < quantities.size) {
                item.copy(quantity = quantities[index])
            } else {
                item.copy(quantity = avgQty.coerceAtLeast(1))
            }
        }
    }

    private fun extractDeclaredTotals(text: String): Pair<Int?, Int?> {
        // Oncelikle tam formati dene: "TOPLAM 15 KALEM, 158 ADETTIR"
        var match = TOTAL_LINE_PATTERN.find(text)
        if (match != null) {
            val itemCount = match.groupValues[1].toIntOrNull()
            val totalQty = match.groupValues[2].toIntOrNull()
            Log.d(TAG, "Found TOPLAM (full): $itemCount items, $totalQty qty")
            return Pair(itemCount, totalQty)
        }

        // Alternatif: "15 KALEM, 158 ADETTIR" (TOPLAM ayri satirda olabilir)
        match = TOTAL_LINE_ALT_PATTERN.find(text)
        if (match != null) {
            val itemCount = match.groupValues[1].toIntOrNull()
            val totalQty = match.groupValues[2].toIntOrNull()
            Log.d(TAG, "Found TOPLAM (alt): $itemCount items, $totalQty qty")
            return Pair(itemCount, totalQty)
        }

        // Ters siralama (OCR karmasasi): ". 158 , KALEM 15 TOPLAM"
        match = TOTAL_LINE_REVERSE_PATTERN.find(text)
        if (match != null) {
            // Ters: ilk grup qty, ikinci grup item count
            val totalQty = match.groupValues[1].toIntOrNull()
            val itemCount = match.groupValues[2].toIntOrNull()
            Log.d(TAG, "Found TOPLAM (reverse): $itemCount items, $totalQty qty")
            return Pair(itemCount, totalQty)
        }

        // Son calistirma: KALEM ve adet sayilarini ayri ayri bul
        val kalemMatch = Regex("""(\d+)\s*KALEM""", RegexOption.IGNORE_CASE).find(text)
        val adetMatch = Regex("""(\d+)\s*AD[EF]?[TI]?T[TI]?[IR]""", RegexOption.IGNORE_CASE).find(text)
        if (kalemMatch != null && adetMatch != null) {
            val itemCount = kalemMatch.groupValues[1].toIntOrNull()
            val totalQty = adetMatch.groupValues[1].toIntOrNull()
            if (itemCount != null && totalQty != null && itemCount < totalQty) {
                Log.d(TAG, "Found TOPLAM (separate): $itemCount items, $totalQty qty")
                return Pair(itemCount, totalQty)
            }
        }

        return Pair(null, null)
    }

    private fun findInvoiceNumber(text: String): String? {
        // B2K ile baslayan fatura numarasi
        val b2kMatch = Regex("""B2K\d{10,}""").find(text)
        if (b2kMatch != null) return b2kMatch.value

        // E-Arsiv No
        val earsivMatch = Regex("""E-?Ar[sş]iv\s*No\s*:?\s*([A-Z0-9]+)""", RegexOption.IGNORE_CASE).find(text)
        if (earsivMatch != null) return earsivMatch.groupValues.getOrNull(1) ?: earsivMatch.value

        return null
    }

    private fun findSupplierName(text: String): String? {
        // Selcuk Ecza Deposu
        if (text.contains("Selçuk", ignoreCase = true) || text.contains("Selcuk", ignoreCase = true)) {
            return "Selçuk Ecza Deposu"
        }
        // Diger ecza depolari
        val match = Regex("""([A-ZÇĞİÖŞÜa-zçğıöşü]+\s+)?[Ee]cza\s*[Dd]eposu""").find(text)
        return match?.value?.trim()
    }

    private fun containsMedicineForm(text: String): Boolean {
        val upperText = text.uppercase()
        return MEDICINE_FORMS.any { form ->
            upperText.contains(Regex("""\b${Regex.escape(form)}\.?\b"""))
        }
    }

    private fun cleanMedicineName(name: String): String {
        var cleaned = name.trim()

        // Sondaki fiyat bilgilerini kaldir (XXX.XX formatinda)
        cleaned = cleaned.replace(Regex("""\s+\d{1,4}[.,]\d{2}.*$"""), "")

        // Sondaki yuzde bilgilerini kaldir
        cleaned = cleaned.replace(Regex("""\s+%\d+.*$"""), "")

        // Sondaki tek sayilari kaldir (miktar olabilir)
        cleaned = cleaned.replace(Regex("""\s+\d{1,2}$"""), "")

        // Sondaki noktayi kaldir
        cleaned = cleaned.replace(Regex("""\.+$"""), "")

        // Coklu bosluklari tek boslukla degistir
        cleaned = cleaned.replace(Regex("""\s+"""), " ")

        return cleaned.trim()
    }

    private fun createInvoiceItem(name: String, quantity: Int): InvoiceItem {
        return InvoiceItem(
            id = 0,
            invoiceId = 0,
            name = name,
            quantity = quantity,
            unit = "kutu",
            unitPrice = null,
            totalPrice = null
        )
    }
}
