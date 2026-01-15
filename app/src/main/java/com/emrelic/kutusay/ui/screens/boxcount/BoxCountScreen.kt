package com.emrelic.kutusay.ui.screens.boxcount

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.emrelic.kutusay.data.model.BoxCount
import com.emrelic.kutusay.util.ImageUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxCountScreen(
    invoiceId: Long,
    onTakePhoto: () -> Unit,
    onCompare: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: BoxCountViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showAddCountDialog by remember { mutableStateOf(false) }
    var countInput by remember { mutableStateOf("") }

    // Camera launcher
    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            tempPhotoUri?.let { viewModel.setBoxPhotoUri(it) }
        }
    }

    LaunchedEffect(invoiceId) {
        if (invoiceId > 0) {
            viewModel.loadInvoice(invoiceId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kutu Sayimi") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Quick add section
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = countInput,
                            onValueChange = { countInput = it.filter { c -> c.isDigit() } },
                            label = { Text("Kutu Sayisi") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        FilledIconButton(
                            onClick = {
                                val count = countInput.toIntOrNull()
                                if (count != null && count > 0) {
                                    viewModel.addBoxCount(count)
                                    countInput = ""
                                }
                            },
                            enabled = countInput.toIntOrNull()?.let { it > 0 } == true
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Ekle")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Take photo and compare buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val file = ImageUtils.createImageFile(context, "KUTU")
                                tempPhotoUri = ImageUtils.getUriForFile(context, file)
                                cameraLauncher.launch(tempPhotoUri)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Fotograf Cek")
                        }

                        Button(
                            onClick = { onCompare(invoiceId) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Compare, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Karsilastir")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Summary card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.totalCountedBoxes == uiState.totalInvoiceBoxes)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Ozet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Faturadaki",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "${uiState.totalInvoiceBoxes} kutu",
                                    style = MaterialTheme.typography.headlineSmall
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Sayilan",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "${uiState.totalCountedBoxes} kutu",
                                    style = MaterialTheme.typography.headlineSmall
                                )
                            }
                        }
                        if (uiState.totalCountedBoxes != uiState.totalInvoiceBoxes) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val diff = uiState.totalCountedBoxes - uiState.totalInvoiceBoxes
                            Text(
                                text = "Fark: ${if (diff > 0) "+$diff" else diff}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Invoice items reference
            item {
                Text(
                    text = "Fatura Kalemleri (Referans)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            items(uiState.invoiceItems) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${item.quantity} ${item.unit}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Box counts
            if (uiState.boxCounts.isNotEmpty()) {
                item {
                    Text(
                        text = "Sayim Kayitlari",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                items(uiState.boxCounts) { boxCount ->
                    BoxCountCard(
                        boxCount = boxCount,
                        onDelete = { viewModel.deleteBoxCount(boxCount) }
                    )
                }
            }

            // Photo preview if exists
            if (uiState.boxPhotoUri != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Cekilen Fotograf",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                IconButton(onClick = { viewModel.clearBoxPhotoUri() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Kaldir")
                                }
                            }
                            AsyncImage(
                                model = uiState.boxPhotoUri,
                                contentDescription = "Kutu fotografı",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Add count dialog
    if (showAddCountDialog) {
        var dialogCount by remember { mutableStateOf("") }
        var dialogNote by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddCountDialog = false },
            title = { Text("Sayim Ekle") },
            text = {
                Column {
                    OutlinedTextField(
                        value = dialogCount,
                        onValueChange = { dialogCount = it.filter { c -> c.isDigit() } },
                        label = { Text("Kutu Sayisi") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dialogNote,
                        onValueChange = { dialogNote = it },
                        label = { Text("Not (Opsiyonel)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val count = dialogCount.toIntOrNull()
                        if (count != null && count > 0) {
                            viewModel.addBoxCount(
                                count = count,
                                note = dialogNote.ifBlank { null }
                            )
                            showAddCountDialog = false
                        }
                    }
                ) {
                    Text("Ekle")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCountDialog = false }) {
                    Text("Iptal")
                }
            }
        )
    }
}

@Composable
private fun BoxCountCard(
    boxCount: BoxCount,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${boxCount.count} kutu",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (boxCount.itemName != null) {
                    Text(
                        text = boxCount.itemName,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (boxCount.note != null) {
                    Text(
                        text = boxCount.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
