package com.emrelic.kutusay.domain.usecase

import android.net.Uri
import com.emrelic.kutusay.data.model.InvoiceItem
import com.emrelic.kutusay.domain.ocr.TextRecognitionService
import javax.inject.Inject

data class ParsedInvoice(
    val invoiceNo: String?,
    val supplierName: String?,
    val items: List<InvoiceItem>,
    val rawText: String
)

class ParseInvoiceUseCase @Inject constructor(
    private val textRecognitionService: TextRecognitionService
) {
    suspend fun execute(imageUri: Uri): Result<ParsedInvoice> {
        return try {
            val rawText = textRecognitionService.recognizeText(imageUri)

            if (rawText.isBlank()) {
                return Result.failure(Exception("Metin bulunamadi"))
            }

            val parsedInvoice = parseInvoiceText(rawText)
            Result.success(parsedInvoice)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseInvoiceText(text: String): ParsedInvoice {
        val lines = text.lines().filter { it.isNotBlank() }

        // Fatura numarasini bul
        val invoiceNo = findInvoiceNumber(lines)

        // Tedarikci adini bul
        val supplierName = findSupplierName(lines)

        // Ilac kalemlerini bul
        val items = parseInvoiceItems(lines)

        return ParsedInvoice(
            invoiceNo = invoiceNo,
            supplierName = supplierName,
            items = items,
            rawText = text
        )
    }

    private fun findInvoiceNumber(lines: List<String>): String? {
        // Fatura no, Fatura No:, Belge No, vb. kaliplarini ara
        val patterns = listOf(
            Regex("""(?i)fatura\s*(?:no|numaras[ıi])\s*:?\s*(\S+)"""),
            Regex("""(?i)belge\s*(?:no|numaras[ıi])\s*:?\s*(\S+)"""),
            Regex("""(?i)irsaliye\s*(?:no|numaras[ıi])\s*:?\s*(\S+)""")
        )

        for (line in lines) {
            for (pattern in patterns) {
                val match = pattern.find(line)
                if (match != null) {
                    return match.groupValues[1]
                }
            }
        }
        return null
    }

    private fun findSupplierName(lines: List<String>): String? {
        // Genellikle ilk satirlarda firma adi bulunur
        // Buyuk harfle baslayan ve "A.S.", "LTD", "ECZA" iceren satirlari ara
        val patterns = listOf(
            Regex("""(?i).*(?:A\.?S\.?|LTD|ECZA|DEPO|ILAC).*""")
        )

        for (line in lines.take(10)) {
            for (pattern in patterns) {
                if (pattern.matches(line) && line.length > 5) {
                    return line.trim()
                }
            }
        }
        return null
    }

    private fun parseInvoiceItems(lines: List<String>): List<InvoiceItem> {
        val items = mutableListOf<InvoiceItem>()

        // Ilac adlari genellikle buyuk harfle yazilir ve yaninda miktar/kutu bilgisi bulunur
        // Ornek kaliplar: "ASPIRIN 500MG 20 TABLET  2 KUTU"
        // veya tablo formatinda: "1  ASPIRIN 500MG    2    KUTU"

        val itemPattern = Regex(
            """(?i)^(?:\d+\s+)?(.+?)\s+(\d+)\s*(?:kutu|adet|ktu|ad|pkt|paket)?$"""
        )

        // Alternatif: Sayiyla baslayan satirlar (sira no)
        val numberedPattern = Regex(
            """(?i)^\d+[.\s]+(.+?)\s+(\d+)\s*(?:kutu|adet|ktu|ad)?"""
        )

        // Basit miktar patterni
        val simplePattern = Regex(
            """(.+?)\s+[xX]?\s*(\d+)(?:\s*(?:kutu|adet|ad|ktu))?$"""
        )

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.length < 3) continue

            // Once numbered pattern dene
            var match = numberedPattern.find(trimmedLine)
            if (match != null) {
                val name = match.groupValues[1].trim()
                val quantity = match.groupValues[2].toIntOrNull() ?: 1
                if (name.isNotBlank() && name.length > 2) {
                    items.add(InvoiceItem(
                        id = 0,
                        invoiceId = 0,
                        name = cleanItemName(name),
                        quantity = quantity,
                        unit = "kutu"
                    ))
                    continue
                }
            }

            // Sonra item pattern dene
            match = itemPattern.find(trimmedLine)
            if (match != null) {
                val name = match.groupValues[1].trim()
                val quantity = match.groupValues[2].toIntOrNull() ?: 1
                if (name.isNotBlank() && name.length > 2) {
                    items.add(InvoiceItem(
                        id = 0,
                        invoiceId = 0,
                        name = cleanItemName(name),
                        quantity = quantity,
                        unit = "kutu"
                    ))
                    continue
                }
            }

            // Son olarak simple pattern dene
            match = simplePattern.find(trimmedLine)
            if (match != null) {
                val name = match.groupValues[1].trim()
                val quantity = match.groupValues[2].toIntOrNull() ?: 1
                // Isim ilac gibi gorunuyorsa (buyuk harf iceriyor, minimum uzunluk)
                if (name.isNotBlank() && name.length > 3 && name.any { it.isUpperCase() }) {
                    items.add(InvoiceItem(
                        id = 0,
                        invoiceId = 0,
                        name = cleanItemName(name),
                        quantity = quantity,
                        unit = "kutu"
                    ))
                }
            }
        }

        return items.distinctBy { it.name.lowercase() }
    }

    private fun cleanItemName(name: String): String {
        // Gereksiz karakterleri temizle
        return name
            .replace(Regex("""^\d+[.\s]+"""), "") // Bastaki numaralari kaldir
            .replace(Regex("""[|_]+"""), " ")     // Ozel karakterleri boslukla degistir
            .replace(Regex("""\s+"""), " ")       // Coklu bosluklari tek boslukla degistir
            .trim()
    }
}
