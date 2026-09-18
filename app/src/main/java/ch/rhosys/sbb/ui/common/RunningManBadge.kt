package ch.rhosys.sbb.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Two running-man icons, stacked/overlapping like a "hurry" pictogram — shown wherever
// a transfer's delay-adjusted buffer no longer covers a normal walk, only a run.
@Composable
fun RunningManBadge(iconSize: Dp = 16.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(-iconSize / 3)) {
        repeat(2) {
            Icon(
                Icons.Default.DirectionsRun,
                contentDescription = "Running required to make this transfer",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
