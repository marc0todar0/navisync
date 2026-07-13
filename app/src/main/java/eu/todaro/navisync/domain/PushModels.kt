package eu.todaro.navisync.domain

import eu.todaro.navisync.sync.MatchResult

/** Stato di avanzamento del push playlist verso Navidrome. */
data class PushProgress(
    val phase: Phase = Phase.IDLE,
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val log: List<String> = emptyList(),
    val error: String? = null,
) {
    enum class Phase { IDLE, ANALYZING, PUSHING, DONE, FAILED }
}

/** Piano d'azione per una singola playlist, prodotto dalla fase di analisi (dry-run). */
data class PlaylistPlan(
    val name: String,
    val existingId: String?,      // null → verrà creata; valorizzato → sostituita
    val duplicateNames: Boolean,  // più playlist sul server con lo stesso nome
    val match: MatchResult,
    val skipped: Boolean,         // true se il match è incompleto (non verrà caricata)
)

/** Esito complessivo del push. */
data class PushReport(
    val plans: List<PlaylistPlan>,
    val created: Int = 0,
    val replaced: Int = 0,
    val skipped: Int = 0,
    val log: List<String> = emptyList(),
)
