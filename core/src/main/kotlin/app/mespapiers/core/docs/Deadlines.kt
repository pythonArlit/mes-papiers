package app.mespapiers.core.docs

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class DeadlineState { NONE, EXPIRED, SOON, VALID }

/** @param daysLeft jours restants (négatif si expiré), null sans échéance. */
data class DeadlineStatus(val state: DeadlineState, val daysLeft: Long?)

/**
 * État d'une échéance, calculé sur des dates civiles. Un document reste valable toute la
 * journée de sa date d'expiration ; il est « expiré » à partir du lendemain.
 */
object Deadlines {
    const val SOON_DAYS = 30L

    fun status(expiry: LocalDate?, today: LocalDate, soonDays: Long = SOON_DAYS): DeadlineStatus {
        if (expiry == null) return DeadlineStatus(DeadlineState.NONE, null)
        val days = ChronoUnit.DAYS.between(today, expiry)
        val state = when {
            days < 0 -> DeadlineState.EXPIRED
            days <= soonDays -> DeadlineState.SOON
            else -> DeadlineState.VALID
        }
        return DeadlineStatus(state, days)
    }

    fun today(wallMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(wallMillis).atZone(zone).toLocalDate()
}

/** Réglages de rappel, bornés. */
data class ReminderSettings(
    val enabled: Boolean = true,
    val offsetsDays: List<Int> = listOf(30, 7, 1),
    val hour: Int = 9,
) {
    init {
        require(hour in 0..23)
        require(offsetsDays.size <= 5 && offsetsDays.all { it in 0..365 })
    }
}

data class PlannedReminder(val offsetDays: Int, val triggerEpochMs: Long)

/**
 * Calendrier des rappels d'une fiche.
 *
 * Chaque rappel tombe à [ReminderSettings.hour] heure locale, N jours avant la date civile
 * d'expiration. L'instant dépend du fuseau : l'application replanifie après un changement
 * de fuseau ou d'heure. Les rappels dont l'instant est déjà passé ne sont pas planifiés
 * (l'état reste visible dans l'application).
 */
object ReminderPlanner {
    fun plan(expiry: LocalDate?, settings: ReminderSettings, zone: ZoneId, nowEpochMs: Long): List<PlannedReminder> {
        if (expiry == null || !settings.enabled) return emptyList()
        return settings.offsetsDays.distinct().sortedDescending().mapNotNull { offset ->
            val trigger = expiry.minusDays(offset.toLong()).atTime(settings.hour, 0).atZone(zone).toInstant().toEpochMilli()
            if (trigger > nowEpochMs) PlannedReminder(offset, trigger) else null
        }
    }
}

/** Rappel d'export : la sauvegarde est la seule voie de récupération. */
object ExportReminder {
    const val STALE_DAYS = 30L

    enum class State { NEVER, STALE, RECENT }

    fun state(lastExportEpochMs: Long?, nowEpochMs: Long): State = when {
        lastExportEpochMs == null -> State.NEVER
        nowEpochMs - lastExportEpochMs > STALE_DAYS * 86_400_000L -> State.STALE
        else -> State.RECENT
    }
}

/**
 * Verrouillage automatique. Toutes les durées sont en temps monotone.
 * Plages bornées : arrière-plan 0 à 120 s, inactivité 30 s à 10 min.
 * [graceMs] : durée maximale accordée à un sélecteur de fichiers, à l'appareil photo ou à
 * la feuille de partage lancés par l'application.
 */
data class LockConfig(
    val backgroundMs: Long = 30_000,
    val inactivityMs: Long = 120_000,
    val graceMs: Long = 180_000,
) {
    init {
        require(backgroundMs in 0..120_000)
        require(inactivityMs in 30_000..600_000)
        require(graceMs in 0..300_000)
    }
}

class LockTimers(private val config: LockConfig) {
    private var lastInteraction = 0L
    private var backgroundSince: Long? = null
    private var graceUntil: Long? = null

    fun onUnlocked(now: Long) {
        lastInteraction = now
        backgroundSince = null
        graceUntil = null
    }

    fun onInteraction(now: Long) {
        lastInteraction = now
    }

    /** À appeler juste avant de lancer une activité externe attendue. */
    fun startGrace(now: Long) {
        graceUntil = now + config.graceMs
    }

    fun onBackground(now: Long) {
        backgroundSince = now
    }

    fun onForeground(now: Long) {
        if (!shouldLock(now)) {
            backgroundSince = null
            graceUntil = null
            lastInteraction = now
        }
    }

    /** Vrai si la session doit être verrouillée à l'instant [now]. */
    fun shouldLock(now: Long): Boolean {
        val bg = backgroundSince
        val grace = graceUntil
        if (bg != null) {
            return if (grace != null) now >= grace else now - bg >= config.backgroundMs
        }
        return now - lastInteraction >= config.inactivityMs
    }

    /** Prochain instant auquel [shouldLock] deviendra vrai, pour programmer une vérification. */
    fun nextCheckAt(): Long {
        val bg = backgroundSince
        if (bg != null) return graceUntil ?: (bg + config.backgroundMs)
        return lastInteraction + config.inactivityMs
    }
}
