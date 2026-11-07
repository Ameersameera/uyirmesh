package com.ameer.uyirmesh.data

import com.ameer.uyirmesh.Graph

/**
 * Deletes old data when "Delete old data" is on (Settings): messages, logs, finished cases with their photos and
 * voice notes, and other records older than the chosen number of days. Open cases, an SOS that is still active,
 * missing people not yet found, shelters, rescuer IDs and settings are kept.
 */
object Cleanup {
    fun run(days: Int = Graph.prefs.now.autoDeleteDays, now: Long = System.currentTimeMillis()): Int {
        if (days <= 0) return 0
        val cut = now - days * 24L * 3600_000L
        val s = Graph.store
        var removed = 0
        fun <T> Col<T>.drop(old: (T) -> Boolean) {
            val gone = value.count(old)
            if (gone == 0) return
            edit { list -> list.filterNot(old) }
            removed += gone
        }
        s.log.drop { it.at < cut }
        s.chat.drop { it.at < cut }
        s.alerts.drop { it.receivedAt < cut }
        s.safe.drop { it.at < cut }
        s.reports.drop { it.at < cut }
        s.missing.drop { it.found && it.at < cut }
        s.finds.drop { it.at < cut }
        s.mySos.drop { !it.active && it.sentAt < cut }

        val oldCases = s.cases.value.filter { !it.isOpen && it.lastAt < cut }
        for (c in oldCases) for (m in c.media) Graph.vault.delete(m.file)
        s.cases.drop { !it.isOpen && it.lastAt < cut }
        s.checkins.drop { it.at < cut }
        s.volunteers.drop { it.at < cut }
        s.nodes.drop { it.lastSeen < cut }

        s.tasks.drop { it.updatedAt < cut }
        s.seen.drop { it.lastAt < cut }
        for (m in s.teamChat.value) if (m.at < cut && m.audio.isNotBlank()) Graph.vault.delete(m.audio)
        s.teamChat.drop { it.at < cut }
        s.crumbs.drop { it.at < cut }

        s.polls.drop { it.receivedAt < cut }
        s.pollAnswers.drop { it.at < cut }
        s.sensors.drop { it.receivedAt < cut }
        if (removed > 0) {
            s.flush()
            Graph.log("DATA", "Deleted $removed old items (older than $days days)")
        }
        return removed
    }
}
