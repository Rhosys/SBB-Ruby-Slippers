package ch.rhosys.sbb.ui.logs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ch.rhosys.sbb.data.log.AppLog
import ch.rhosys.sbb.data.log.AppLogEntry

// Recent API calls and searches (newest first), kept in memory until the app is closed.
// Tap an entry to expand its full response; the copy button puts the whole list on the
// clipboard.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onNavigateBack: () -> Unit,
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(viewModel.asText(entries))) },
                        enabled = entries.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy all")
                    }
                    IconButton(onClick = viewModel::clear, enabled = entries.isNotEmpty()) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear")
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Text(
                "No API calls or searches yet since the app started. Only the last " +
                    "${AppLog.MAX_ENTRIES} are kept, and only until the app is closed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries) { entry -> LogEntryCard(entry) }
            }
        }
    }
}

@Composable
private fun LogEntryCard(entry: AppLogEntry) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val detail = remember(entry) { detail(entry) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = detail != null) { expanded = !expanded },
        colors = if (entry.isError) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title(entry), style = MaterialTheme.typography.labelLarge)
            Text(summary(entry), style = MaterialTheme.typography.bodySmall)
            if (detail != null) {
                SelectionContainer {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!expanded) {
                    Text(
                        "Tap to show all",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
