package com.nextcraft.forge.runtime

import com.nextcraft.forge.ui.UiText
import com.nextcraft.forge.ui.toUiText
import com.nextcraft.forge.R
import android.content.Context
import com.nextcraft.forge.model.Project
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SnapshotsState(
    val projectName: String = "",
    val snapshots: List<WorkspaceSnapshot> = emptyList(),
    val busy: Boolean = false,
    val message: UiText? = null,
)

/** Checkpoint manager: manual project snapshots with restore (see [SnapshotManager]). */
class SnapshotsController(
    context: Context,
    private val activeProject: () -> Project?,
    private val workspaceFor: (String) -> File,
    /** True while an agent turn, build or terminal command may be writing to the project. */
    private val projectBusy: () -> Boolean,
    private val onRestored: (String) -> Unit = {},
) {
    private val manager = SnapshotManager(context.applicationContext.filesDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutable = MutableStateFlow(SnapshotsState())
    val state: StateFlow<SnapshotsState> = mutable.asStateFlow()
    private var lastProjectId: String? = null

    fun refresh() {
        val project = activeProject() ?: run {
            lastProjectId = null
            mutable.update { SnapshotsState(message = UiText.res(R.string.rt_open_project_first)) }
            return
        }
        val projectChanged = project.id != lastProjectId
        lastProjectId = project.id
        if (projectChanged) {
            mutable.update { SnapshotsState(projectName = project.name) }
        }
        scope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { manager.list(project.id) }.getOrDefault(emptyList()) }
            if (activeProject()?.id == project.id) {
                mutable.update { it.copy(projectName = project.name, snapshots = list) }
            }
        }
    }

    fun create(label: String) = task(UiText.res(R.string.rt_snap_saved)) { project ->
        manager.create(project.id, workspaceFor(project.id), label)
    }

    fun restore(snapshot: WorkspaceSnapshot) {
        if (projectBusy()) {
            mutable.update { it.copy(message = UiText.res(R.string.rt_snap_wait_running)) }
            return
        }
        task(
            success = UiText.res(R.string.rt_snap_restored, snapshot.label),
            onSuccess = { project -> onRestored(project.id) },
        ) { project ->
            manager.restore(project.id, workspaceFor(project.id), snapshot)
        }
    }

    fun delete(snapshot: WorkspaceSnapshot) = task(UiText.res(R.string.rt_snap_deleted)) { manager.delete(snapshot) }

    private fun task(
        success: UiText,
        onSuccess: (Project) -> Unit = {},
        block: (Project) -> Unit,
    ) {
        val project = activeProject() ?: return
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block(project) } }
            val list = withContext(Dispatchers.IO) { runCatching { manager.list(project.id) }.getOrDefault(emptyList()) }
            if (result.isSuccess) {
                runCatching { onSuccess(project) }
            }
            if (activeProject()?.id == project.id) {
                mutable.update {
                    it.copy(
                        busy = false,
                        snapshots = list,
                        projectName = project.name,
                        message = result.fold({ success }, { e -> e.toUiText(R.string.rt_snap_failed) }),
                    )
                }
            } else {
                mutable.update { it.copy(busy = false) }
            }
        }
    }
}
