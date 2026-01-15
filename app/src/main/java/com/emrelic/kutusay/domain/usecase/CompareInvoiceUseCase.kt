package com.emrelic.kutusay.domain.usecase

import com.emrelic.kutusay.data.model.BoxCount
import com.emrelic.kutusay.data.model.InvoiceItem
import javax.inject.Inject

data class ComparisonResult(
    val invoiceItems: List<InvoiceItem>,
    val boxCounts: List<BoxCount>,
    val invoiceTotalBoxes: Int,
    val countedTotalBoxes: Int,
    val isMatched: Boolean,
    val difference: Int,
    val itemComparisons: List<ItemComparison>
)

data class ItemComparison(
    val itemName: String,
    val invoiceQuantity: Int,
    val countedQuantity: Int,
    val isMatched: Boolean,
    val difference: Int
)

class CompareInvoiceUseCase @Inject constructor() {

    fun execute(
        invoiceItems: List<InvoiceItem>,
        boxCounts: List<BoxCount>
    ): ComparisonResult {
        val invoiceTotalBoxes = invoiceItems.sumOf { it.quantity }
        val countedTotalBoxes = boxCounts.sumOf { it.count }

        val itemComparisons = createItemComparisons(invoiceItems, boxCounts)

        val isMatched = invoiceTotalBoxes == countedTotalBoxes
        val difference = countedTotalBoxes - invoiceTotalBoxes

        return ComparisonResult(
            invoiceItems = invoiceItems,
            boxCounts = boxCounts,
            invoiceTotalBoxes = invoiceTotalBoxes,
            countedTotalBoxes = countedTotalBoxes,
            isMatched = isMatched,
            difference = difference,
            itemComparisons = itemComparisons
        )
    }

    private fun createItemComparisons(
        invoiceItems: List<InvoiceItem>,
        boxCounts: List<BoxCount>
    ): List<ItemComparison> {
        val comparisons = mutableListOf<ItemComparison>()

        // Fatura kalemlerini boxCount'larla eslesir
        // Simdilik basit: isim bazli eslestirme
        val countsByName = boxCounts
            .filter { it.itemName != null }
            .groupBy { it.itemName!!.lowercase() }
            .mapValues { it.value.sumOf { bc -> bc.count } }

        for (item in invoiceItems) {
            val countedQty = countsByName[item.name.lowercase()] ?: 0
            comparisons.add(
                ItemComparison(
                    itemName = item.name,
                    invoiceQuantity = item.quantity,
                    countedQuantity = countedQty,
                    isMatched = item.quantity == countedQty,
                    difference = countedQty - item.quantity
                )
            )
        }

        // Faturada olmayan ama sayilan kalemleri ekle
        val invoiceNames = invoiceItems.map { it.name.lowercase() }.toSet()
        for ((name, count) in countsByName) {
            if (name !in invoiceNames) {
                comparisons.add(
                    ItemComparison(
                        itemName = name,
                        invoiceQuantity = 0,
                        countedQuantity = count,
                        isMatched = false,
                        difference = count
                    )
                )
            }
        }

        return comparisons
    }

    fun generateReportText(result: ComparisonResult, invoiceNo: String?): String {
        val sb = StringBuilder()

        sb.appendLine("=== FATURA KONTROL RAPORU ===")
        sb.appendLine()

        if (invoiceNo != null) {
            sb.appendLine("Fatura No: $invoiceNo")
        }

        sb.appendLine("Tarih: ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale("tr")).format(java.util.Date())}")
        sb.appendLine()

        sb.appendLine("--- OZET ---")
        sb.appendLine("Faturadaki toplam kutu: ${result.invoiceTotalBoxes}")
        sb.appendLine("Sayilan toplam kutu: ${result.countedTotalBoxes}")
        sb.appendLine("Fark: ${if (result.difference >= 0) "+${result.difference}" else result.difference}")
        sb.appendLine()

        if (result.isMatched) {
            sb.appendLine("SONUC: ESLESTI")
        } else {
            sb.appendLine("SONUC: UYUSMAZLIK VAR")
        }

        sb.appendLine()
        sb.appendLine("--- DETAY ---")

        if (result.itemComparisons.isNotEmpty()) {
            for (comparison in result.itemComparisons) {
                val status = if (comparison.isMatched) "OK" else "!"
                sb.appendLine("[$status] ${comparison.itemName}")
                sb.appendLine("    Fatura: ${comparison.invoiceQuantity}, Sayim: ${comparison.countedQuantity}")
            }
        } else {
            sb.appendLine("Fatura Kalemleri:")
            for (item in result.invoiceItems) {
                sb.appendLine("  - ${item.name}: ${item.quantity} ${item.unit}")
            }
        }

        sb.appendLine()
        sb.appendLine("=== RAPOR SONU ===")

        return sb.toString()
    }
}
