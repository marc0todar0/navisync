package eu.todaro.navisync.sync

import eu.todaro.navisync.domain.SyncProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Stato di sync condiviso in-process tra il worker e la UI. */
object SyncBus {
    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    fun update(p: SyncProgress) { _progress.value = p }
    fun setRunning(running: Boolean) { _running.value = running }
}
