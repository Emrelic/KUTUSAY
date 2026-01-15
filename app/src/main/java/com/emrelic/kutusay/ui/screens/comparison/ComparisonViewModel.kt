package com.emrelic.kutusay.ui.screens.comparison

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emrelic.kutusay.data.model.Invoice
import com.emrelic.kutusay.data.model.InvoiceStatus
import com.emrelic.kutusay.data.repository.InvoiceRepository
import com.emrelic.kutusay.domain.usecase.CompareInvoiceUseCase
import com.emrelic.kutusay.domain.usecase.ComparisonResult
import com.emrelic.kutusay.util.ShareUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ComparisonUiState(
    val isLoading: Boolean = false,
    val invoice: Invoice? = null,
    val comparisonResult: ComparisonResult? = null,
    val reportText: String = "",
    val imageUris: List<Uri> = emptyList()
)

@HiltViewModel
class ComparisonViewModel @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val compareInvoiceUseCase: CompareInvoiceUseCase,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComparisonUiState())
    val uiState: StateFlow<ComparisonUiState> = _uiState.asStateFlow()

    init {
        val invoiceId = savedStateHandle.get<Long>("invoiceId") ?: 0L
        if (invoiceId > 0) {
            loadComparison(invoiceId)
        }
    }

    fun loadComparison(invoiceId: Long) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val invoiceWithItems = invoiceRepository.getInvoiceWithItems(invoiceId)
            if (invoiceWithItems != null) {
                val result = compareInvoiceUseCase.execute(
                    invoiceWithItems.items,
                    invoiceWithItems.boxCounts
                )

                val reportText = compareInvoiceUseCase.generateReportText(
                    result,
                    invoiceWithItems.invoice.invoiceNo
                )

                // Collect image URIs
                val imageUris = mutableListOf<Uri>()
                try {
                    imageUris.add(Uri.parse(invoiceWithItems.invoice.imageUri))
                } catch (e: Exception) {
                    // Ignore invalid URI
                }
                invoiceWithItems.boxCounts
                    .mapNotNull { it.imageUri }
                    .forEach { uri ->
                        try {
                            imageUris.add(Uri.parse(uri))
                        } catch (e: Exception) {
                            // Ignore invalid URI
                        }
                    }

                // Update invoice status
                val newStatus = if (result.isMatched) InvoiceStatus.MATCHED else InvoiceStatus.MISMATCHED
                invoiceRepository.updateInvoice(
                    invoiceWithItems.invoice.copy(status = newStatus)
                )

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    invoice = invoiceWithItems.invoice,
                    comparisonResult = result,
                    reportText = reportText,
                    imageUris = imageUris
                )
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun shareViaWhatsApp() {
        val state = _uiState.value
        ShareUtils.shareViaWhatsApp(
            context = context,
            text = state.reportText,
            imageUris = state.imageUris
        )
    }

    fun shareGeneric() {
        val state = _uiState.value
        ShareUtils.shareGeneric(
            context = context,
            text = state.reportText,
            imageUris = state.imageUris
        )
    }

    fun markAsCompleted() {
        viewModelScope.launch {
            val invoice = _uiState.value.invoice ?: return@launch
            invoiceRepository.updateInvoice(
                invoice.copy(status = InvoiceStatus.COMPLETED)
            )
        }
    }
}
