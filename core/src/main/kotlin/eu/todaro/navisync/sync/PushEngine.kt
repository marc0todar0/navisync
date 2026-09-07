package eu.todaro.navisync.sync

import eu.todaro.navisync.data.subsonic.SubsonicClient
import eu.todaro.navisync.data.subsonic.humanMessage
import eu.todaro.navisync.domain.PlaylistPlan
import eu.todaro.navisync.domain.PushProgress
import eu.todaro.navisync.domain.PushReport

/**
 * Orchestrazione del push: analizza i m3u abbinandoli al server (dry-run), poi carica
 * solo le playlist completamente abbinate creando/sostituendo su Navidrome.
 */
class PushEngine(
    private val client: SubsonicClient,
    /** Nome della playlist "preferiti": se un m3u ha questo nome, mappa alle stelle invece che a una playlist. Null = disabilitato. */
    private val favoritesName: String? = null,
) {

    private fun isFavorites(name: String): Boolean =
        !favoritesName.isNullOrBlank() && name.trim().equals(favoritesName.trim(), ignoreCase = true)

    /** Fase di analisi: costruisce un [PlaylistPlan] per ogni m3u senza toccare il server. */
    suspend fun analyze(
        parsed: List<M3uPlaylist>,
        onProgress: suspend (PushProgress) -> Unit,
    ): List<PlaylistPlan> {
        val log = ArrayList<String>()
        onProgress(PushProgress(phase = PushProgress.Phase.ANALYZING, current = "Indicizzo la libreria…", log = ArrayList(log)))
        val songs = client.listSongs()
        val matcher = PlaylistMatcher(songs)
        log.add("Indicizzate ${songs.size} tracce sul server.")

        val refs = client.listPlaylistRefs()
        // Nome (lowercase) → lista di playlist esistenti (per rilevare i duplicati).
        val byName = HashMap<String, MutableList<String>>()
        for (r in refs) byName.getOrPut(r.name.lowercase()) { ArrayList() }.add(r.id)

        val plans = ArrayList<PlaylistPlan>(parsed.size)
        for ((i, pl) in parsed.withIndex()) {
            onProgress(
                PushProgress(
                    phase = PushProgress.Phase.ANALYZING,
                    done = i, total = parsed.size, current = pl.name, log = ArrayList(log),
                )
            )
            val match = matcher.match(pl.entries)
            val fav = isFavorites(pl.name)
            val existing = if (fav) emptyList() else byName[pl.name.lowercase()].orEmpty()
            val plan = PlaylistPlan(
                name = pl.name,
                existingId = existing.firstOrNull(),
                duplicateNames = existing.size > 1,
                match = match,
                skipped = !match.complete,
                isFavorites = fav,
            )
            plans.add(plan)
            log.add(
                "${pl.name}: ${match.matched}/${match.total} abbinate" +
                    (when {
                        plan.skipped -> " → SALTATA"
                        fav -> " → preferiti ★"
                        plan.existingId != null -> " → sostituisci"
                        else -> " → crea"
                    })
            )
        }
        onProgress(
            PushProgress(
                phase = PushProgress.Phase.ANALYZING,
                done = parsed.size, total = parsed.size, log = ArrayList(log),
            )
        )
        return plans
    }

    /** Fase di caricamento: crea/sostituisce solo i piani non saltati. */
    suspend fun push(
        plans: List<PlaylistPlan>,
        onProgress: suspend (PushProgress) -> Unit,
    ): PushReport {
        val toPush = plans.filter { !it.skipped }
        val log = ArrayList<String>()
        var created = 0
        var replaced = 0
        for ((i, plan) in toPush.withIndex()) {
            onProgress(
                PushProgress(
                    phase = PushProgress.Phase.PUSHING,
                    done = i, total = toPush.size, current = plan.name, log = ArrayList(log),
                )
            )
            try {
                if (plan.isFavorites) {
                    // Mirror completo: i preferiti sul server diventano ESATTAMENTE le tracce del m3u.
                    val target = plan.match.songIds
                    val targetSet = target.toSet()
                    val current = client.listStarredSongs().map { it.id }
                    val currentSet = current.toSet()
                    val toStar = target.filter { it !in currentSet }
                    val toUnstar = current.filter { it !in targetSet }
                    client.setStarred(toStar)
                    client.unsetStarred(toUnstar)
                    replaced++
                    log.add("Preferiti \"${plan.name}\": +${toStar.size} / -${toUnstar.size} (totale ${target.size}).")
                } else {
                    client.createOrReplacePlaylist(plan.name, plan.existingId, plan.match.songIds)
                    if (plan.existingId != null) {
                        replaced++
                        log.add("Sostituita \"${plan.name}\" (${plan.match.songIds.size} tracce).")
                    } else {
                        created++
                        log.add("Creata \"${plan.name}\" (${plan.match.songIds.size} tracce).")
                    }
                }
            } catch (e: Exception) {
                log.add("Errore su \"${plan.name}\": ${humanMessage(e)}")
            }
        }
        val skipped = plans.count { it.skipped }
        log.add("Fatto: $created create, $replaced sostituite, $skipped saltate.")
        onProgress(
            PushProgress(
                phase = PushProgress.Phase.DONE,
                done = toPush.size, total = toPush.size, log = ArrayList(log),
            )
        )
        return PushReport(plans = plans, created = created, replaced = replaced, skipped = skipped, log = log)
    }
}
