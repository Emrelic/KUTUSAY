package com.emrelic.kutusay.ui.screens.invoice

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emrelic.kutusay.data.model.Invoice
import com.emrelic.kutusay.data.model.InvoiceItem
import com.emrelic.kutusay.data.model.InvoiceStatus
import com.emrelic.kutusay.data.repository.InvoiceRepository
import com.emrelic.kutusay.domain.usecase.ParseInvoiceUseCase
import com.emrelic.kutusay.domain.usecase.ParsedInvoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InvoiceUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val invoiceId: Long? = null,
    val invoiceNo: String? = null,
    val supplierName: String? = null,
    val items: List<InvoiceItem> = emptyList(),
    val rawText: String = "",
    val imageUri: Uri? = null,
    val isSaved: Boolean = false,
    val totalQuantity: Int = 0,
    val totalAmount: Double? = null
)

@HiltViewModel
class InvoiceViewModel @Inject constructor(
    private val parseInvoiceUseCase: ParseInvoiceUseCase,
    private val invoiceRepository: InvoiceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(InvoiceUiState())
    val uiState: StateFlow<InvoiceUiState> = _uiState.asStateFlow()

    fun processInvoiceImage(imageUri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                imageUri = imageUri
            )

            val result = parseInvoiceUseCase.execute(imageUri)

            result.fold(
                onSuccess = { parsedInvoice ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        invoiceNo = parsedInvoice.invoiceNo,
                        supplierName = parsedInvoice.supplierName,
                        items = parsedInvoice.items,
                        rawText = parsedInvoice.rawText,
                        totalQuantity = parsedInvoice.totalQuantity,
                        totalAmount = parsedInvoice.totalAmount
                    )
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = exception.message ?: "Bir hata olustu"
                    )
                }
            )
        }
    }

    fun updateItem(index: Int, item: InvoiceItem) {
        val updatedItems = _uiState.value.items.toMutableList()
        if (index in updatedItems.indices) {
            updatedItems[index] = item
            _uiState.value = _uiState.value.copy(items = updatedItems)
        }
    }

    fun addItem(item: InvoiceItem) {
        val updatedItems = _uiState.value.items + item
        _uiState.value = _uiState.value.copy(items = updatedItems)
    }

    fun removeItem(index: Int) {
        val updatedItems = _uiState.value.items.toMutableList()
        if (index in updatedItems.indices) {
            updatedItems.removeAt(index)
            _uiState.value = _uiState.value.copy(items = updatedItems)
        }
    }

    fun updateInvoiceNo(invoiceNo: String) {
        _uiState.value = _uiState.value.copy(invoiceNo = invoiceNo)
    }

    fun saveInvoice(onSuccess: (Long) -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            val imageUri = state.imageUri ?: return@launch

            val invoice = Invoice(
                invoiceNo = state.invoiceNo ?: "BILINMIYOR",
                date = System.currentTimeMillis(),
                supplierName = state.supplierName,
                imageUri = imageUri.toString(),
                status = InvoiceStatus.PENDING
            )

            val invoiceId = invoiceRepository.saveInvoiceWithItems(invoice, state.items)

            _uiState.value = _uiState.value.copy(
                invoiceId = invoiceId,
                isSaved = true
            )

            onSuccess(invoiceId)
        }
    }

    fun loadInvoice(invoiceId: Long) {
        if (invoiceId <= 0) return

        viewModelScope.launch {
            val invoiceWithItems = invoiceRepository.getInvoiceWithItems(invoiceId)
            if (invoiceWithItems != null) {
                _uiState.value = _uiState.value.copy(
                    invoiceId = invoiceWithItems.invoice.id,
                    invoiceNo = invoiceWithItems.invoice.invoiceNo,
                    supplierName = invoiceWithItems.invoice.supplierName,
                    items = invoiceWithItems.items,
                    imageUri = Uri.parse(invoiceWithItems.invoice.imageUri),
                    isSaved = true
                )
            }
        }
    }
}
