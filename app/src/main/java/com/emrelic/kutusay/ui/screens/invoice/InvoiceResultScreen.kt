package com.emrelic.kutusay.ui.screens.invoice

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.emrelic.kutusay.data.model.InvoiceItem
import com.emrelic.kutusay.util.ImageUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceResultScreen(
    invoiceId: Long,
    initialImageUri: Uri? = null,
    onContinueToBoxCount: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: InvoiceViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showAddItemDialog by remember { mutableStateOf(false) }
    var editingItemIndex by remember { mutableStateOf<Int?>(null) }
    var hasProcessedInitialImage by remember { mutableStateOf(false) }

    // Image picker for selecting from gallery
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.processInvoiceImage(it) }
    }

    // Camera launcher
    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            tempPhotoUri?.let { viewModel.processInvoiceImage(it) }
        }
    }

    // Process initial image URI from camera
    LaunchedEffect(initialImageUri, hasProcessedInitialImage) {
        if (initialImageUri != null && !hasProcessedInitialImage) {
            hasProcessedInitialImage = true
            viewModel.processInvoiceImage(initialImageUri)
        }
    }

    LaunchedEffect(invoiceId) {
        if (invoiceId > 0) {
            viewModel.loadInvoice(invoiceId)
        }
    }

    // If no image yet and no initial image, show option to take/select photo
    if (uiState.imageUri == null && !uiState.isLoading && initialImageUri == null) {
        LaunchedEffect(Unit) {
            val file = ImageUtils.createImageFile(context, "FATURA")
            tempPhotoUri = ImageUtils.getUriForFile(context, file)
            cameraLauncher.launch(tempPhotoUri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fatura Kalemleri") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddItemDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Kalem Ekle")
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.items.isNotEmpty()) {
                Surface(
                    shadowElevation = 8.dp
                ) {
                    Button(
                        onClick = {
                            if (uiState.isSaved && uiState.invoiceId != null) {
                                onContinueToBoxCount(uiState.invoiceId!!)
                            } else {
                                viewModel.saveInvoice { savedId ->
                                    onContinueToBoxCount(savedId)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .height(56.dp)
                    ) {
                        Icon(Icons.Default.ArrowForward, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Kutu Sayimina Devam Et")
                    }
                }
            }
        }
    ) { paddingValues ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Fatura okunuyor...")
                    }
                }
            }
            uiState.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = uiState.error ?: "Hata",
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            val file = ImageUtils.createImageFile(context, "FATURA")
                            tempPhotoUri = ImageUtils.getUriForFile(context, file)
                            cameraLauncher.launch(tempPhotoUri)
                        }) {
                            Text("Tekrar Dene")
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Invoice info card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                OutlinedTextField(
                                    value = uiState.invoiceNo ?: "",
                                    onValueChange = { viewModel.updateInvoiceNo(it) },
                                    label = { Text("Fatura No") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                if (uiState.supplierName != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Tedarikci: ${uiState.supplierName}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Summary
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Toplam Kalem: ${uiState.items.size}",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "Toplam Kutu: ${uiState.totalQuantity}",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                if (uiState.totalAmount != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Toplam Tutar: ${String.format("%.2f", uiState.totalAmount)} TL",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    // Items header
                    item {
                        Text(
                            text = "Fatura Kalemleri",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    // Invoice items
                    itemsIndexed(uiState.items) { index, item ->
                        InvoiceItemCard(
                            item = item,
                            onEdit = { editingItemIndex = index },
                            onDelete = { viewModel.removeItem(index) }
                        )
                    }

                    // Empty state
                    if (uiState.items.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        Icons.Default.Inventory,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Ilac kalemi bulunamadi",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = "Manuel olarak ekleyebilirsiniz",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(onClick = { showAddItemDialog = true }) {
                                        Icon(Icons.Default.Add, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Kalem Ekle")
                                    }
                                }
                            }
                        }
                    }

                    // Raw text (collapsed)
                    if (uiState.rawText.isNotBlank()) {
                        item {
                            var expanded by remember { mutableStateOf(false) }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { expanded = !expanded }
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Ham OCR Metni",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Icon(
                                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null
                                        )
                                    }
                                    if (expanded) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = uiState.rawText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add item dialog
    if (showAddItemDialog) {
        AddEditItemDialog(
            item = null,
            onDismiss = { showAddItemDialog = false },
            onSave = { item ->
                viewModel.addItem(item)
                showAddItemDialog = false
            }
        )
    }

    // Edit item dialog
    editingItemIndex?.let { index ->
        val item = uiState.items.getOrNull(index)
        if (item != null) {
            AddEditItemDialog(
                item = item,
                onDismiss = { editingItemIndex = null },
                onSave = { updatedItem ->
                    viewModel.updateItem(index, updatedItem)
                    editingItemIndex = null
                }
            )
        }
    }
}

@Composable
private fun InvoiceItemCard(
    item: InvoiceItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "${item.quantity} ${item.unit}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (item.unitPrice != null) {
                        Text(
                            text = "Birim: ${String.format("%.2f", item.unitPrice)} TL",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (item.totalPrice != null) {
                    Text(
                        text = "Toplam: ${String.format("%.2f", item.totalPrice)} TL",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Duzenle")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Sil",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun AddEditItemDialog(
    item: InvoiceItem?,
    onDismiss: () -> Unit,
    onSave: (InvoiceItem) -> Unit
) {
    var name by remember { mutableStateOf(item?.name ?: "") }
    var quantity by remember { mutableStateOf(item?.quantity?.toString() ?: "1") }
    var unitPrice by remember { mutableStateOf(item?.unitPrice?.toString() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Kalem Ekle" else "Kalemi Duzenle") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Ilac Adi") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it.filter { c -> c.isDigit() } },
                    label = { Text("Kutu Sayisi") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = unitPrice,
                    onValueChange = { unitPrice = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    label = { Text("Birim Fiyat (TL)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        val qty = quantity.toIntOrNull() ?: 1
                        val price = unitPrice.replace(",", ".").toDoubleOrNull()
                        onSave(
                            InvoiceItem(
                                id = item?.id ?: 0,
                                invoiceId = item?.invoiceId ?: 0,
                                name = name.trim(),
                                quantity = qty,
                                unit = "kutu",
                                unitPrice = price,
                                totalPrice = price?.let { it * qty }
                            )
                        )
                    }
                }
            ) {
                Text("Kaydet")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Iptal")
            }
        }
    )
}
