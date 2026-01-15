package com.emrelic.kutusay.ui.screens.boxcount

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emrelic.kutusay.data.model.BoxCount
import com.emrelic.kutusay.data.model.InvoiceItem
import com.emrelic.kutusay.data.repository.InvoiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BoxCountUiState(
    val isLoading: Boolean = false,
    val invoiceId: Long = 0,
    val invoiceItems: List<InvoiceItem> = emptyList(),
    val boxCounts: List<BoxCount> = emptyList(),
    val totalInvoiceBoxes: Int = 0,
    val totalCountedBoxes: Int = 0,
    val boxPhotoUri: Uri? = null
)

@HiltViewModel
class BoxCountViewModel @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(BoxCountUiState())
    val uiState: StateFlow<BoxCountUiState> = _uiState.asStateFlow()

    init {
        val invoiceId = savedStateHandle.get<Long>("invoiceId") ?: 0L
        if (invoiceId > 0) {
            loadInvoice(invoiceId)
        }
    }

    fun loadInvoice(invoiceId: Long) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, invoiceId = invoiceId)

            val invoiceWithItems = invoiceRepository.getInvoiceWithItems(invoiceId)
            if (invoiceWithItems != null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    invoiceItems = invoiceWithItems.items,
                    boxCounts = invoiceWithItems.boxCounts,
                    totalInvoiceBoxes = invoiceWithItems.items.sumOf { it.quantity },
                    totalCountedBoxes = invoiceWithItems.boxCounts.sumOf { it.count }
                )
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun addBoxCount(count: Int, itemName: String? = null, note: String? = null) {
        viewModelScope.launch {
            val boxCount = BoxCount(
                invoiceId = _uiState.value.invoiceId,
                count = count,
                itemName = itemName,
                imageUri = _uiState.value.boxPhotoUri?.toString(),
                note = note
            )

            invoiceRepository.insertBoxCount(boxCount)

            // Reload data
            loadInvoice(_uiState.value.invoiceId)
        }
    }

    fun updateBoxCount(boxCount: BoxCount) {
        viewModelScope.launch {
            invoiceRepository.updateBoxCount(boxCount)
            loadInvoice(_uiState.value.invoiceId)
        }
    }

    fun deleteBoxCount(boxCount: BoxCount) {
        viewModelScope.launch {
            invoiceRepository.deleteBoxCount(boxCount)
            loadInvoice(_uiState.value.invoiceId)
        }
    }

    fun setBoxPhotoUri(uri: Uri?) {
        _uiState.value = _uiState.value.copy(boxPhotoUri = uri)
    }

    fun clearBoxPhotoUri() {
        _uiState.value = _uiState.value.copy(boxPhotoUri = null)
    }
}
