package ch.rhosys.sbb.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

// Every pop-up in the app goes through this instead of Material3's AlertDialog directly,
// so they're all sized the same way: 90% of the available width, and capped (not forced —
// short dialogs still wrap their content) at 90% of the available height. The platform
// default width Compose otherwise applies is well under that, hence usePlatformDefaultWidth
// = false.
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val configuration = LocalConfiguration.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        modifier = modifier
            .fillMaxWidth(0.9f)
            .heightIn(max = configuration.screenHeightDp.dp * 0.9f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
}
