package com.pecbox.streambox.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.ui.theme.LocalIsTv
import com.pecbox.streambox.ui.theme.Orange
import kotlinx.coroutines.delay

/**
 * Campo de texto do app.
 *
 * Na TV, o controle passa pelo campo sem abrir o teclado (antes, só de navegar pelos Ajustes o
 * teclado abria e atrapalhava). O teclado abre só quando o usuário aperta OK no campo e fecha
 * ao confirmar ou sair dele. No celular é um campo normal.
 */
@Composable
fun SbTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    if (!LocalIsTv.current) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange, modifier = modifier,
            label = label, placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
            singleLine = singleLine, enabled = enabled, visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        )
        return
    }

    var editing by remember { mutableStateOf(false) }
    var fieldHadFocus by remember { mutableStateOf(false) }
    var coverFocused by remember { mutableStateOf(false) }
    var refocusCover by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val cover = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(6.dp)

    LaunchedEffect(editing) {
        if (editing) {
            delay(30)
            runCatching { field.requestFocus() }
            keyboard?.show()
        } else if (refocusCover) {
            refocusCover = false
            delay(30)
            runCatching { cover.requestFocus() }   // volta o foco para o campo (fechado) e a navegação segue
        }
    }

    // Ao confirmar no teclado: faz a ação do campo e fecha a edição
    fun finish() { keyboard?.hide(); refocusCover = true; editing = false }
    val actions = KeyboardActions(
        onDone = { keyboardActions.onDone?.invoke(this) ?: defaultKeyboardAction(ImeAction.Done); finish() },
        onGo = { keyboardActions.onGo?.invoke(this) ?: defaultKeyboardAction(ImeAction.Go); finish() },
        onSearch = { keyboardActions.onSearch?.invoke(this) ?: defaultKeyboardAction(ImeAction.Search); finish() },
        onSend = { keyboardActions.onSend?.invoke(this) ?: defaultKeyboardAction(ImeAction.Send); finish() },
        onNext = { keyboardActions.onNext?.invoke(this) ?: defaultKeyboardAction(ImeAction.Next); keyboard?.hide(); editing = false },
        onPrevious = { keyboardActions.onPrevious?.invoke(this) ?: defaultKeyboardAction(ImeAction.Previous); keyboard?.hide(); editing = false },
    )

    Box(modifier) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(field)
                .focusProperties { canFocus = editing }
                .onFocusChanged {
                    if (it.isFocused) fieldHadFocus = true
                    else if (fieldHadFocus) { fieldHadFocus = false; editing = false }
                },
            label = label, placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
            singleLine = singleLine, enabled = enabled, readOnly = !editing,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions, keyboardActions = actions,
        )
        if (!editing) {
            // Capa por cima do campo: recebe o foco do controle; OK (ou toque) abre a edição
            Box(
                Modifier
                    .matchParentSize()
                    .padding(top = if (label != null) 8.dp else 0.dp)
                    .clip(shape)
                    .border(2.dp, if (coverFocused) Orange else Color.Transparent, shape)
                    .focusRequester(cover)
                    .onFocusChanged { coverFocused = it.isFocused }
                    .clickable(
                        enabled = enabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { editing = true }
            )
        }
    }
}
