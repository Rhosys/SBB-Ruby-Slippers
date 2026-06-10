package ch.rhosys.sbb.ui.journey

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun MissedBoardingDialog(
    fromName: String,
    onMissedIt: () -> Unit,
    onDifferentRoute: () -> Unit,
    onStillOnIt: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onStillOnIt,
        title = { Text("Still on track?") },
        text = { Text("You're still near $fromName. Did your plans change?") },
        confirmButton = {
            Column {
                TextButton(onClick = onMissedIt, modifier = Modifier.fillMaxWidth()) {
                    Text("I missed the train")
                }
                TextButton(onClick = onDifferentRoute, modifier = Modifier.fillMaxWidth()) {
                    Text("I took a different route")
                }
                TextButton(onClick = onStillOnIt, modifier = Modifier.fillMaxWidth()) {
                    Text("Still on this one")
                }
            }
        },
    )
}
