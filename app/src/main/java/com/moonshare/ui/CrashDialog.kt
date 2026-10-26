package com.moonshare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonshare.ui.common.PrimaryButton
import com.moonshare.ui.theme.Aqua
import com.moonshare.ui.theme.Danger
import com.moonshare.ui.theme.InkFaint
import com.moonshare.vm.MoonViewModel

/**
 * Cartel de diagnóstico. Si la app reventó en la sesión anterior, esto es lo primero
 * que se ve: el stack completo, y un botón que lo copia al portapapeles.
 *
 * Está acá, y no en un Toast o enterrado en logcat, porque el caso que resuelve es
 * justamente "se cierra sola antes de que puedas hacer nada".
 */
@Composable
fun CrashDialogHost(vm: MoonViewModel) {
    val engine by vm.engineState.collectAsState()
    // Se lee una sola vez por arranque: no queremos que desaparezca mientras se lee.
    val text = remember { vm.pendingCrash() }
    var shown by remember { mutableStateOf(text != null) }
    var expanded by remember { mutableStateOf(false) }

    if (!shown || text.isNullOrBlank()) return

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF205080B))
            .padding(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White.copy(alpha = 0.045f))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape)
                        .background(Danger.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.BugReport, null, tint = Danger, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "MoonShare se cerró mal",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "El detalle quedó guardado en el teléfono. Nada se envía a ningún lado.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkFaint,
                    )
                }
            }

            // Las primeras líneas siempre visibles; el resto, desplegable.
            val firstTrace = text.lines().firstOrNull { it.contains("Exception") || it.contains("Error") }
                ?: text.lineSequence().elementAtOrNull(7).orEmpty()
            Text(
                if (expanded) text else firstTrace.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                ),
                color = Color(0xFFE7A9A0),
                maxLines = if (expanded) 4000 else 3,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x0D000000))
                    .let { m ->
                        if (expanded) m
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                        else m.padding(12.dp)
                    }
                    .clickable { expanded = !expanded },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (expanded) "replegar" else "ver todo",
                    style = MaterialTheme.typography.labelMedium,
                    color = Aqua,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    PrimaryButton(
                        text = "Copiar y salir",
                        onClick = {
                            vm.copyCrash()
                            vm.dismissCrash()
                        },
                        tint = Aqua,
                    )
                }
            }
            Text(
                "Pegá eso en el chat y se arregla en la próxima build.",
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint,
            )
        }
    }
}
