package com.moonshare.ui

import androidx.compose.foundation.text.BasicText
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pantalla de arranque fallido. Existe para un solo caso: la app NO llega a su UI,
 * entonces el diagnóstico no puede depender de la UI.
 *
 * Por eso está escrita a propósito con lo mínimo del toolkit — sin `MoonApp`, sin
 * `MoonTheme`, sin `ViewModel`, sin el motor y sin `MaterialTheme`: si lo que rompe
 * el arranque es justamente alguna de esas piezas, este panel igual se dibuja.
 */
@Composable
fun BootFailureScreen(text: String, phase: String, onCopy: () -> Unit, onContinue: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = Color(0xFFE7A9A0))
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF06090D))
            .padding(20.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(24.dp))
            BasicText(
                "MoonShare no pudo abrir",
                style = TextStyle(color = Color.White, fontSize = 24.sp, letterSpacing = (-0.4).sp),
            )
            BasicText(
                "Fase alcanzada antes de morir: $phase\n" +
                    "Si esto se repite, copia el detalle y pegalo en el reporte. " +
                    "Podes tocar “Abrir igual” para usar la app sin esperar el arreglo.",
                style = TextStyle(color = Color(0xFF8A98A6), fontSize = 13.sp, lineHeight = 19.sp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
                    .clickable { expanded = !expanded },
            ) {
                BasicText(if (expanded) text.ifBlank { "(vacío)" } else text.lines().take(12).joinToString("\n"), style = mono)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BootButton("Copiar reporte", primary = true, onClick = onCopy, modifier = Modifier.weight(1f))
                BootButton("Abrir igual", primary = false, onClick = onContinue, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BootButton(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(if (primary) Color(0xFF39D5C4) else Color.White.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = TextStyle(
                color = if (primary) Color(0xFF04221F) else Color(0xFFD7E2EA),
                fontSize = 14.sp,
            ),
        )
    }
}
