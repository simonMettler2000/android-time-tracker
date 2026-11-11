package com.simonmettler.timetracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simonmettler.timetracker.data.CalendarItem
import com.simonmettler.timetracker.data.TrackerActivity
import com.simonmettler.timetracker.model.Boundary
import com.simonmettler.timetracker.model.TimeRules
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GapEditorSheet(
    gap: CalendarItem.Gap,
    zone: ZoneId,
    activities: List<TrackerActivity>,
    onDismiss: () -> Unit,
    onSave: (String, Boundary, Boundary, (String) -> Unit) -> Unit
) {
    val start = remember(gap, zone) { GapEndpoint(gap.startAtMs, zone) }
    val end = remember(gap, zone) { GapEndpoint(gap.endAtMs, zone) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = activities.find { it.id == selectedId }
    var menuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save(activity: TrackerActivity) {
        try {
            val startBoundary = start.resolve()
            val endBoundary = end.resolve()
            error = null
            busy = true
            onSave(activity.id, startBoundary, endBoundary) { message ->
                error = message
                busy = false
            }
        } catch (problem: IllegalArgumentException) {
            error = problem.message
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Fill untracked time", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Choose an activity and adjust the times if you only want to fill part of the gap.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, enabled = !busy && activities.isNotEmpty()) {
                        selected?.let {
                            ColorDot(Color(it.colorArgb))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(selected?.title ?: "Choose activity", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        activities.forEach { activity ->
                            DropdownMenuItem(
                                text = { Text(activity.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { ColorDot(Color(activity.colorArgb)) },
                                onClick = {
                                    selectedId = activity.id
                                    menuOpen = false
                                }
                            )
                        }
                    }
                }
                if (activities.isEmpty()) Text("Create or restore a tracker in Buttons to fill this gap.")
                start.Editor("Start")
                end.Editor("End")
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            error?.let {
                Text(it, Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Text("Cancel")
                }
                Button(onClick = { selected?.let(::save) }, enabled = !busy && selected != null,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Text("Add entry")
                }
            }
        }
    }
}

private class GapEndpoint(atMs: Long, zoneId: ZoneId) {
    private val original = Boundary.capture(Instant.ofEpochMilli(atMs), zoneId)
    private var text by mutableStateOf(TimeRules.originalLocal(original))
    private var zone by mutableStateOf(original.zoneId)
    private var offset by mutableStateOf(ZoneOffset.ofTotalSeconds(original.offsetSeconds).id)

    fun resolve() = TimeRules.resolve(text, zone, offset)

    @Composable
    fun Editor(label: String) {
        EndpointEditor(label, text, zone, offset,
            onText = { text = it; offset = "" },
            onZone = { zone = it; offset = "" },
            onOffset = { offset = it })
    }
}
