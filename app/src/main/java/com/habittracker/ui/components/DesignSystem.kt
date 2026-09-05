package com.habittracker.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

object AppSpacing {
    val xs = 8.dp
    val sm = 16.dp
    val md = 24.dp
    val lg = 32.dp
}

private val ButtonShape = RoundedCornerShape(12.dp)
val LocalAppSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("Snackbar host is missing") }

class AppNavigationGuard {
    var hasUnsavedChanges by mutableStateOf(false)
    var pendingAction by mutableStateOf<(() -> Unit)?>(null)

    fun navigate(action: () -> Unit) {
        if (hasUnsavedChanges) pendingAction = action else action()
    }
}

val LocalAppNavigationGuard = staticCompositionLocalOf<AppNavigationGuard> { error("Navigation guard is missing") }

/**
 * 모든 화면이 같은 여백과 배경을 사용하도록 만든 최상위 화면 컨테이너다.
 *
 * [content]가 `LazyListScope.() -> Unit`인 것은 Kotlin의 수신 객체 지정 람다 예시다.
 * 호출하는 쪽에서는 `item { ... }`, `items(...)`를 바로 사용할 수 있다.
 */
@Composable
fun AppScreen(
    modifier: Modifier = Modifier,
    bottomBar: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = AppSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
            content = content,
        )
        bottomBar?.let {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(AppSpacing.sm)) { it() }
        }
    }
}

/**
 * 제목 영역의 공통 레이아웃이다.
 * nullable 매개변수와 Composable 슬롯을 사용해 화면마다 필요한 내용만 선택적으로 끼운다.
 */
@Composable
fun AppHeroCard(
    title: String,
    description: String? = null,
    icon: String? = null,
    @DrawableRes iconRes: Int? = null,
    eyebrow: String? = null,
    status: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
    ) {
        Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        if (!description.isNullOrBlank()) {
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!status.isNullOrBlank()) {
            Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        action?.let {
            Column(Modifier.fillMaxWidth().padding(top = AppSpacing.xs), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) { it() }
        }
    }
}

@Composable
fun AppSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            content = content,
        )
    }
}

@Composable
fun AppPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .sizeIn(minHeight = 52.dp),
        enabled = enabled,
        shape = ButtonShape,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AppSaveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = "저장",
    enabled: Boolean = true,
) {
    AppPrimaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    )
}

@Composable
fun AppEditButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = "수정",
    enabled: Boolean = true,
) {
    AppPrimaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    )
}

@Composable
fun AppSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .sizeIn(minHeight = 52.dp),
        enabled = enabled,
        shape = ButtonShape,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    minLines: Int = 1,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingContent: (@Composable () -> Unit)? = null,
    trailingOverlay: (@Composable BoxScope.() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        AppOutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            readOnly = readOnly,
            singleLine = singleLine,
            minLines = minLines,
            enabled = enabled,
            label = { Text(label) },
            visualTransformation = visualTransformation,
            trailingIcon = trailingContent,
            keyboardOptions = keyboardOptions,
            isError = isError,
            supportingText = if (supportingText != null) { { Text(supportingText) } } else null,
        )
        trailingOverlay?.invoke(this)
    }
}

@Composable
fun AppOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier,
        label = label, singleLine = singleLine, minLines = minLines,
        readOnly = readOnly, enabled = enabled, isError = isError,
        supportingText = supportingText, trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, textStyle = textStyle,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
fun AppSelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.small,
            )
            .background(
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = MaterialTheme.shapes.small,
            )
            .clip(MaterialTheme.shapes.small)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .sizeIn(minHeight = 48.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun AppStatusText(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = if (message.contains("실패")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun AppEmptyCard(message: String, modifier: Modifier = Modifier) {
    AppSectionCard(modifier = modifier) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun AppSupportText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun AppLoadingCard(
    message: String,
    modifier: Modifier = Modifier,
) {
    AppSectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.sizeIn(maxWidth = 20.dp, maxHeight = 20.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AppSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AppNoticeDialog(
    message: String,
    onDismiss: () -> Unit,
    title: String = "처리 완료",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("확인") }
        },
        title = { Text(title) },
        text = { Text(message, color = MaterialTheme.colorScheme.onSurface) },
    )
}

/** 작업 결과는 화면을 가리지 않는 공통 Snackbar로 전달한다. */
@Composable
fun AppActionNotice(
    message: String?,
    onDismiss: () -> Unit,
) {
    val snackbar = LocalAppSnackbar.current
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message = message, withDismissAction = true)
            dismiss()
        }
    }
}

/** 단순 확인/취소 다이얼로그의 버튼 배치와 스타일을 공통화한다. */
@Composable
fun AppConfirmDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                message?.let { Text(it, color = MaterialTheme.colorScheme.onSurface) }
                content?.invoke(this)
            }
        },
    )
}

@Composable
fun AppButtonRow(
    primaryText: String,
    onPrimaryClick: () -> Unit,
    secondaryText: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    secondaryEnabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
    ) {
        if (secondaryText != null && onSecondaryClick != null) {
            AppSecondaryButton(
                text = secondaryText,
                onClick = onSecondaryClick,
                modifier = Modifier.weight(1f),
                enabled = secondaryEnabled,
            )
        }
        AppPrimaryButton(
            text = primaryText,
            onClick = onPrimaryClick,
            modifier = Modifier.weight(1f),
            enabled = primaryEnabled,
        )
    }
}
