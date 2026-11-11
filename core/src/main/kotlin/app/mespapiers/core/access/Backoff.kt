package app.mespapiers.core.access

import app.mespapiers.core.util.Clock

/**
 * État du délai progressif, lisible hors session (voir MENACES.md).
 * @param failures échecs consécutifs depuis la dernière ouverture réussie.
 * @param delayMs délai imposé par le dernier échec (0 : aucun délai en cours).
 * @param lockedUntilElapsed fin du délai en temps monotone, valable pour [bootCount].
 */
data class BackoffState(
    val failures: Int = 0,
    val delayMs: Long = 0,
    val lockedUntilElapsed: Long = 0,
    val bootCount: Int = 0,
)

/**
 * Délai progressif après des échecs d'accès.
 *
 * Le délai est mesuré en temps monotone : changer l'heure du téléphone ne le raccourcit pas.
 * Un redémarrage remet le temps monotone à zéro ; on le détecte par le compteur de
 * démarrages et le délai en cours repart alors de zéro. C'est volontairement défavorable à
 * la personne qui redémarre : aucun raccourci n'est possible par ce biais.
 */
class Backoff(
    private val threshold: Int = 5,
    private val delaysMs: List<Long> = listOf(30_000, 60_000, 300_000, 900_000, 3_600_000),
) {
    fun delayFor(failures: Int): Long =
        if (failures < threshold) 0 else delaysMs[minOf(failures - threshold, delaysMs.size - 1)]

    fun onFailure(state: BackoffState, clock: Clock): BackoffState {
        val failures = if (state.failures == Int.MAX_VALUE) state.failures else state.failures + 1
        val delay = delayFor(failures)
        return BackoffState(failures, delay, clock.elapsedMillis() + delay, clock.bootCount())
    }

    /**
     * À appeler avant toute lecture du délai : réarme le délai après un redémarrage et
     * efface un délai purgé. L'appelant persiste l'état renvoyé s'il a changé.
     */
    fun normalize(state: BackoffState, clock: Clock): BackoffState {
        if (state.delayMs == 0L) return state
        if (state.bootCount != clock.bootCount()) {
            return state.copy(lockedUntilElapsed = clock.elapsedMillis() + state.delayMs, bootCount = clock.bootCount())
        }
        return if (clock.elapsedMillis() >= state.lockedUntilElapsed) state.copy(delayMs = 0) else state
    }

    /** Temps d'attente restant pour un état déjà normalisé. */
    fun remainingMs(state: BackoffState, clock: Clock): Long {
        if (state.delayMs == 0L) return 0
        if (state.bootCount != clock.bootCount()) return state.delayMs
        // Borné par le délai lui-même : une valeur incohérente ne peut pas bloquer plus longtemps.
        return (state.lockedUntilElapsed - clock.elapsedMillis()).coerceIn(0, state.delayMs)
    }

    fun onSuccess(): BackoffState = BackoffState()
}
