package com.moonshare.ui.pick

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InverseColors
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Android as AndroidIcon
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moonshare.data.model.FileItem
import com.moonshare.data.model.MediaCategory
import com.moonshare.ui.common.CheckDot
import com.moonshare.ui.common.EmptyState
import com.moonshare.ui.common.PrimaryButton
import com.moonshare.ui.theme.Aqua
import com.moonshare.ui.theme.InkFaint
import com.moonshare.ui.theme.PillShape
import com.moonshare.vm.MoonViewModel
import com.moonshare.vm.sizeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Selector de archivos. Grilla densa, miniaturas reales del MediaStore y
 * selección táctil sin confirmaciones intermedias: tocás, y si hay algo elegido,
 * el botón de abajo ya dice cuántos bytes van a salir.
 */
@Composable
fun FilePickerScreen(vm: MoonViewModel, onBack: () -> Unit, onPairing: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val catalog = ui.catalog
    val categories = remember {
        listOf(
            MediaCategory.Photos, MediaCategory.Videos, MediaCategory.Audio,
            MediaCategory.Docs, MediaCategory.Apps, MediaCategory.All,
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShapeLocal)
                    .background(Color.White.copy(alpha = 0.05f))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack, "Volver",
                    tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Qué querés pasar",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (ui.selected.isEmpty()) "Tocá para elegir"
                    else "${ui.selected.size} elegidos · ${ui.selectedBytes.sizeLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.selected.isEmpty()) InkFaint else Aqua,
                )
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShapeLocal)
                    .background(
                        if (ui.selected.isNotEmpty()) Aqua.copy(alpha = 0.12f) else Color.Transparent
                    )
                    .clickable(enabled = catalog.items.isNotEmpty()) {
                        if (ui.selected.size == catalog.items.size) vm.clearSelection() else vm.selectAll()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.SelectAll, "Todo",
                    tint = if (ui.selected.isNotEmpty()) Aqua else InkFaint,
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // buscador sin caja: una línea de foco, como en los reproductores
        TextField(
            value = catalog.query,
            onValueChange = { vm.setQuery(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Buscar por nombre…", color = InkFaint) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = InkFaint, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            shape = PillShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Aqua,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        Spacer(Modifier.height(10.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories) { cat ->
                CategoryChip(
                    label = cat.label,
                    selected = catalog.category == cat,
                    onClick = { vm.setCategory(cat) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Box(Modifier.weight(1f)) {
            when {
                catalog.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp, color = Aqua)
                }

                catalog.items.isEmpty() -> EmptyState(
                    text = catalog.error ?: "Nada para mostrar",
                    hint = when (catalog.category) {
                        MediaCategory.All -> "Si es la primera vez, autorizá una carpeta o revisá los permisos."
                        MediaCategory.Apps -> "El sistema no indexa APKs por seguridad. Usá la pestaña Todo y elegí la carpeta."
                        else -> "Revisá los permisos de almacenamiento en Ajustes."
                    },
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(catalog.items, key = { it.id }) { item ->
                        FileCell(
                            item = item,
                            selected = item.id in ui.selected,
                            onClick = { vm.toggle(item) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (catalog.category == MediaCategory.All && catalog.treeUri == null) {
                Box(
                    Modifier
                        .clip(PillShape)
                        .background(Color.White.copy(alpha = 0.05f))
                        .clickable { vm.openFolderPicker() }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FileOpen, null, tint = InkFaint, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Carpeta…", style = MaterialTheme.typography.labelLarge, color = InkFaint)
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            PrimaryButton(
                text = if (ui.selected.isEmpty()) "Elegí algo" else "Enviar ${ui.selected.size}",
                onClick = { if (ui.selected.isNotEmpty()) onPairing() },
                enabled = ui.selected.isNotEmpty(),
                icon = Icons.Rounded.InverseColors,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(PillShape)
            .background(if (selected) Aqua.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Aqua else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileCell(item: FileItem, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(0.82f)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = if (selected) 0.10f else 0.055f), Color(0x0A000000))
                )
            )
            .clickable(onClick = onClick)
    ) {
        when {
            item.isImage || item.isVideo -> MediaThumb(item.uri, Modifier.fillMaxSize())
            else -> {
                Column(
                    Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Icon(
                        when {
                            item.isAudio -> Icons.Rounded.MusicNote
                            item.isApk -> Icons.Rounded.AndroidIcon
                            item.mime == "application/pdf" -> Icons.Rounded.PictureAsPdf
                            else -> Icons.Rounded.FileOpen
                        },
                        null,
                        tint = if (selected) Aqua else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(22.dp),
                    )
                    Column {
                        Text(
                            item.name.substringBeforeLast('.'),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            item.sizeLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = InkFaint,
                        )
                    }
                }
            }
        }

        if (item.isVideo && item.durationMs > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(PillShape)
                    .background(Color(0x99000000))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    formatDuration(item.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }

        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            CheckDot(selected)
        }
    }
}

/**
 * Miniatura real. Usa ContentResolver.loadThumbnail (API 29+, el piso de la app)
 * en vez de una librería de imágenes: cero dependencias y el sistema ya tiene
 * la miniatura en caché para fotos y videos.
 */
@Composable
private fun MediaThumb(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver
                    .loadThumbnail(android.net.Uri.parse(uri), android.util.Size(280, 280), null)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(18.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier.background(
                Brush.linearGradient(listOf(Color(0xFF101922), Color(0xFF0A0F14)))
            )
        ) {
            Icon(
                Icons.Rounded.Videocam, null,
                tint = Color.White.copy(alpha = 0.14f),
                modifier = Modifier.align(Alignment.Center).size(22.dp),
            )
        }
    }
}

private val CircleShapeLocal = RoundedCornerShape(percent = 50)

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
