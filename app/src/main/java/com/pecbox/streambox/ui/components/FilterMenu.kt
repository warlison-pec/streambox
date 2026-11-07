package com.pecbox.streambox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.Surface2
import com.pecbox.streambox.ui.theme.TextMain
import com.pecbox.streambox.ui.theme.TextMuted

/** Barra compacta de filtros: "FILTROS  Ordenar ⌄  Ano ⌄  Gêneros ⌄ …" — as opções só aparecem no clique. */
@Composable
fun FilterBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.FilterList, null, tint = TextMuted, modifier = Modifier.size(18.dp))
        Text("FILTROS", color = TextMuted, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, end = 8.dp))
        content()
    }
}

/**
 * Botão com menu suspenso. options: (chave, rótulo). Quando a escolha não é a padrão (`defaultKey`),
 * o botão mostra o valor escolhido em laranja (ex.: "Gêneros: Ação").
 */
@Composable
fun FilterMenu(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    defaultKey: String = "",
    alwaysShowValue: Boolean = false,   // ex.: "Ordenar: A–Z" mesmo sem filtro
) {
    var open by remember { mutableStateOf(false) }
    val active = selected != defaultKey
    val current = options.firstOrNull { it.first == selected }?.second
    val shape = RoundedCornerShape(50)
    Box {
        Row(
            Modifier
                .tvFocus(shape, 1.05f)
                .background(if (active && !alwaysShowValue) Orange.copy(alpha = 0.16f) else Color.Transparent, shape)
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (current != null && (active || alwaysShowValue)) "$label: $current" else label,
                color = if (active && !alwaysShowValue) Orange else TextMain,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp),
            )
            Icon(Icons.Filled.ExpandMore, null, tint = if (active && !alwaysShowValue) Orange else TextMain, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 420.dp).background(Surface2),
        ) {
            options.forEach { (key, text) ->
                val check: (@Composable () -> Unit)? =
                    if (key == selected) { { Icon(Icons.Filled.Check, null, tint = Orange) } } else null
                DropdownMenuItem(
                    text = { Text(text, color = if (key == selected) Orange else TextMain) },
                    trailingIcon = check,
                    onClick = { open = false; onSelect(key) },
                )
            }
        }
    }
}
