package com.kitsugi.animelist.ui.components.addons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kitsugi.animelist.R
import com.kitsugi.animelist.data.manga.MangaSource
import com.kitsugi.animelist.ui.utils.requestFocusAfterFrames

@Composable
internal fun SingleMangaSourcePanel(
    sources: List<MangaSource>,
    onQuickCheck: (MangaSource) -> Unit,
    onHealthReport: () -> Unit,
    onVerify: ((String, String) -> Unit)? = null,
    isTv: Boolean = false,
) {
    val source = sources.singleOrNull()
    val focus = remember { FocusRequester() }
    LaunchedEffect(isTv, source) {
        if (isTv && source != null) focus.requestFocusAfterFrames(frames = 2)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(stringResource(R.string.manga_recovery_title), style = MaterialTheme.typography.headlineSmall) }
        item { Text(stringResource(R.string.manga_recovery_note), style = MaterialTheme.typography.bodyLarge) }
        item { Text(source?.name ?: stringResource(R.string.manga_recovery_loading), style = MaterialTheme.typography.titleLarge) }
        if (source != null) {
            item { Text(source.baseUrl, style = MaterialTheme.typography.bodyMedium) }
            item { Text(stringResource(R.string.manga_recovery_webview_note), style = MaterialTheme.typography.bodyMedium) }
            item {
                Button(onClick = { onQuickCheck(source) }, modifier = Modifier.focusRequester(focus)) {
                    Text(stringResource(R.string.manga_recovery_check))
                }
            }
            if (onVerify != null) {
                item { TextButton(onClick = { onVerify(source.baseUrl, source.name) }) { Text(stringResource(R.string.manga_recovery_verify)) } }
            }
        }
        item { TextButton(onClick = onHealthReport) { Text(stringResource(R.string.manga_recovery_report)) } }
    }
}
