package eu.todaro.navisync.sync

import eu.todaro.navisync.domain.PlaylistPlan
import eu.todaro.navisync.domain.PushProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Stato del push playlist condiviso in-process tra ViewModel e UI. Rispecchia [SyncBus]. */
object PushBus {
    private val _progress = MutableStateFlow(PushProgress())
    val progress: StateFlow<PushProgress> = _progress

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _plans = MutableStateFlow<List<PlaylistPlan>>(emptyList())
    val plans: StateFlow<List<PlaylistPlan>> = _plans

    fun update(p: PushProgress) { _progress.value = p }
    fun setRunning(running: Boolean) { _running.value = running }
    fun setPlans(plans: List<PlaylistPlan>) { _plans.value = plans }
}
