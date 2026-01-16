package com.emrelic.kutusay.domain.usecase

import android.net.Uri
import android.util.Log
import com.emrelic.kutusay.data.model.InvoiceItem
import com.emrelic.kutusay.domain.ocr.TextRecognitionService
import javax.inject.Inject

data class ParsedInvoice(
    val invoiceNo: String?,
    val supplierName: String?,
    val items: List<InvoiceItem>,
    val rawText: String,
    val totalQuantity: Int,
    val totalAmount: Double?,
    val declaredItemCount: Int?,    // Faturada yazan kalem sayisi
    val declaredTotalQty: Int?      // Faturada yazan toplam adet
)

class ParseInvoiceUseCase @Inject constructor(
    private val textRecognitionService: TextRecognitionService
) {
    companion object {
        private const val TAG = "ParseInvoiceUseCase"

        // Ilac formlarini tanimla
        private val MEDICINE_FORMS = listOf(
            "TB", "TABLET", "TAB", "FTB", "FT", "FILM",
            "CAP", "KAP", "KAPSUL", "KAPSÜL",
            "AMP", "AMPUL", "FLK", "FLAKON",
            "SRP", "SURUP", "ŞURUP", "SUSP",
            "DML", "DAMLA", "DROP", "DAMLASI",
            "JEL", "GEL", "KREM", "CREAM", "POM", "POMAD",
            "ENJ", "INJ", "ENJEKTABL",
            "SPREY", "SPRAY", "LOT", "LOSYON",
            "SOL", "SOLÜSYON", "SOLUTION",
            "SASE", "ŞASE", "SACHET",
            "OVL", "OVUL", "SUP", "SUPOZITUVAR",
            "GARG", "GARGLE", "CIGN",
            "PİSİK", "PISIK", "BABY",
            "MG", "ML", "GR", "MCG", "G",
            "PLUS", "FORT", "FORTE"
        )

        // Yer kodlari paterni (1CA, 2AB, 5AC, 6BI, 2AA., 3AB-, vs.)
        private val LOCATION_CODE_PATTERN = Regex("""^(\d{1,2}[A-Z]{1,3})[.\-\s]""")

        // Toplam satiri paterni: "TOPLAM 17 KALEM, 104 ADETTİR"
        private val TOTAL_LINE_PATTERN = Regex(
            """TOPLAM\s*(\d+)\s*KALEM[,.\s]*(\d+)\s*ADETT""",
            RegexOption.IGNORE_CASE
        )
    }

    suspend fun execute(imageUri: Uri): Result<ParsedInvoice> {
        return try {
            val rawText = textRecognitionService.recognizeText(imageUri)
            Log.d(TAG, "OCR Raw Text:\n$rawText")

            if (rawText.isBlank()) {
                return Result.failure(Exception("Metin bulunamadi"))
            }

            val parsedInvoice = parseInvoiceText(rawText)
            Log.d(TAG, "Parsed ${parsedInvoice.items.size} items")
            Log.d(TAG, "Declared: ${parsedInvoice.declaredItemCount} items, ${parsedInvoice.declaredTotalQty} qty")
            Log.d(TAG, "Calculated total quantity: ${parsedInvoice.totalQuantity}")
            Result.success(parsedInvoice)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing invoice", e)
            Result.failure(e)
        }
    }

    private fun parseInvoiceText(text: String): ParsedInvoice {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }

        // Fatura numarasini bul
        val invoiceNo = findInvoiceNumber(lines)

        // Tedarikci adini bul
        val supplierName = findSupplierName(text)

        // TOPLAM X KALEM, Y ADETTİR satirindan bilgi cek
        val (declaredItemCount, declaredTotalQty) = extractDeclaredTotals(text)
        Log.d(TAG, "Declared totals from invoice: $declaredItemCount items, $declaredTotalQty qty")

        // Ilac isimlerini bul
        val medicineNames = extractMedicineNames(lines)
        Log.d(TAG, "Found ${medicineNames.size} medicine names")

        // Miktarlari bul
        val quantities = extractQuantities(lines)
        Log.d(TAG, "Found ${quantities.size} quantities: $quantities")

        // Eslestir
        val items = matchMedicinesWithQuantities(
            medicineNames,
            quantities,
            declaredItemCount,
            declaredTotalQty
        )

        // Toplam miktar
        val totalQuantity = if (declaredTotalQty != null && declaredTotalQty > 0) {
            declaredTotalQty
        } else {
            items.sumOf { it.quantity }
        }

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
     * "TOPLAM 17 KALEM, 104 ADETTİR" satirindan bilgi cek
     */
    private fun extractDeclaredTotals(text: String): Pair<Int?, Int?> {
        val match = TOTAL_LINE_PATTERN.find(text)
        if (match != null) {
            val itemCount = match.groupValues[1].toIntOrNull()
            val totalQty = match.groupValues[2].toIntOrNull()
            Log.d(TAG, "Found TOPLAM line: $itemCount KALEM, $totalQty ADET")
            return Pair(itemCount, totalQty)
        }
        return Pair(null, null)
    }

    private fun findInvoiceNumber(lines: List<String>): String? {
        val patterns = listOf(
            Regex("""E-?Arşiv\s*No\s*:?\s*([A-Z0-9]+)""", RegexOption.IGNORE_CASE),
            Regex("""B2K\d+"""),
            Regex("""Kayıt\s*No\s*:?\s*([A-Z0-9\-]+)""", RegexOption.IGNORE_CASE)
        )

        for (line in lines) {
            for (pattern in patterns) {
                val match = pattern.find(line)
                if (match != null) {
                    return match.groupValues.getOrNull(1) ?: match.value
                }
            }
        }
        return null
    }

    private fun findSupplierName(text: String): String? {
        val patterns = listOf(
            Regex("""(?i)(sel[cç]uk\s*ecza\s*deposu)"""),
            Regex("""(?i)([A-ZÇĞİÖŞÜa-zçğıöşü\s]+ecza\s*deposu)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null && match.value.length > 5) {
                return match.value.trim()
            }
        }
        return null
    }

    /**
     * Ilac isimlerini bul - yer koduyla baslayan satirlar
     */
    private fun extractMedicineNames(lines: List<String>): List<String> {
        val medicines = mutableListOf<String>()

        for (line in lines) {
            val codeMatch = LOCATION_CODE_PATTERN.find(line)
            if (codeMatch != null) {
                var medicinePart = line.substring(codeMatch.range.last + 1).trim()

                // Ilac formu iceriyor mu kontrol et
                if (containsMedicineForm(medicinePart)) {
                    medicinePart = cleanMedicineName(medicinePart)
                    if (medicinePart.length >= 3) {
                        medicines.add(medicinePart)
                        Log.d(TAG, "Found medicine: $medicinePart")
                    }
                }
            }
        }

        return medicines
    }

    /**
     * Miktarlari bul - ozellikle X+Y formatina odaklan
     */
    private fun extractQuantities(lines: List<String>): List<Int> {
        val quantities = mutableListOf<Int>()

        for (line in lines) {
            val trimmed = line.trim()

            // Cok uzun satirlari atla
            if (trimmed.length > 15) continue

            // X+Y formati (ornek: 10+1, 5+3) - en guvenilir
            val plusMatch = Regex("""^(\d{1,2})\+(\d{1,2})$""").find(trimmed)
            if (plusMatch != null) {
                val base = plusMatch.groupValues[1].toIntOrNull() ?: 0
                val bonus = plusMatch.groupValues[2].toIntOrNull() ?: 0
                val total = base + bonus
                if (total in 1..100) {
                    quantities.add(total)
                    Log.d(TAG, "Found quantity (X+Y): $trimmed = $total")
                    continue
                }
            }

            // S+Y formati (OCR hatasi, S yerine 5)
            val sMatch = Regex("""^[S5]\+(\d{1,2})$""", RegexOption.IGNORE_CASE).find(trimmed)
            if (sMatch != null) {
                val bonus = sMatch.groupValues[1].toIntOrNull() ?: 0
                val total = 5 + bonus
                if (total in 1..100) {
                    quantities.add(total)
                    Log.d(TAG, "Found quantity (S+Y): $trimmed = $total")
                    continue
                }
            }

            // Tek sayi - sadece tam eslesen satirlar (1-99 arasi)
            if (Regex("""^\d{1,2}$""").matches(trimmed)) {
                val num = trimmed.toIntOrNull()
                if (num != null && num in 1..99) {
                    quantities.add(num)
                    Log.d(TAG, "Found quantity (single): $num")
                }
            }
        }

        return quantities
    }

    /**
     * Ilaclari miktarlarla eslestir
     * Eger miktar sayisi yeterli degilse, faturadaki toplam adetten hesapla
     */
    private fun matchMedicinesWithQuantities(
        medicines: List<String>,
        quantities: List<Int>,
        declaredItemCount: Int?,
        declaredTotalQty: Int?
    ): List<InvoiceItem> {
        val items = mutableListOf<InvoiceItem>()
        val medicineCount = medicines.size

        // Eger yeterli miktar varsa direkt esle
        if (quantities.size >= medicineCount) {
            for ((index, medicine) in medicines.withIndex()) {
                items.add(createInvoiceItem(medicine, quantities[index]))
            }
        } else if (declaredTotalQty != null && medicineCount > 0) {
            // Yeterli miktar yok, faturadaki toplam adetten ortalama hesapla
            val avgQty = declaredTotalQty / medicineCount
            val remainder = declaredTotalQty % medicineCount

            Log.d(TAG, "Not enough quantities, using declared total: $declaredTotalQty / $medicineCount = avg $avgQty")

            for ((index, medicine) in medicines.withIndex()) {
                // Bulunan miktari kullan, yoksa ortalama
                val qty = if (index < quantities.size) {
                    quantities[index]
                } else {
                    // Kalani ilk birkac ilaca dagit
                    if (index < remainder) avgQty + 1 else avgQty
                }
                items.add(createInvoiceItem(medicine, qty.coerceAtLeast(1)))
            }
        } else {
            // Hicbir bilgi yok, her ilaca 1 kutu ata
            for (medicine in medicines) {
                items.add(createInvoiceItem(medicine, 1))
            }
        }

        return items
    }

    private fun createInvoiceItem(name: String, quantity: Int): InvoiceItem {
        Log.d(TAG, "Created item: $name | Qty: $quantity")
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

    private fun containsMedicineForm(text: String): Boolean {
        val upperText = text.uppercase()
        return MEDICINE_FORMS.any { form ->
            upperText.contains(Regex("""\b${Regex.escape(form)}\.?\b"""))
        }
    }

    private fun cleanMedicineName(name: String): String {
        return name
            // Sondaki fiyat/sayi bilgilerini kaldir
            .replace(Regex("""\s+\d{2,3}[.,]\d{2}.*$"""), "")
            .replace(Regex("""\s+%\d+.*$"""), "")
            // Sondaki noktayi kaldir
            .replace(Regex("""\.+$"""), "")
            // Coklu bosluklari tek boslukla degistir
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
