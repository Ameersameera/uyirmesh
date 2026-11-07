package com.ameer.uyirmesh.data

import com.ameer.uyirmesh.core.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** One persisted list of items, kept in memory as a StateFlow and saved encrypted in the [Vault]. */
class Col<T>(
    val name: String,
    private val max: Int,
    private val toJ: (T) -> Map<String, Any?>,
    private val fromJ: (Map<String, Any?>) -> T,
) {
    internal val state = MutableStateFlow<List<T>>(emptyList())
    val flow: StateFlow<List<T>> = state
    val value: List<T> get() = state.value
    internal var onChange: (() -> Unit)? = null

    fun edit(change: (List<T>) -> List<T>) {
        state.update { old ->
            val n = change(old)
            if (n.size > max) n.takeLast(max) else n
        }
        onChange?.invoke()
    }

    fun add(item: T) = edit { it + item }

    /** Replaces the item with the same key, or appends it. Returns the stored item. */
    fun <K> upsert(key: (T) -> K, item: T): T {
        val k = key(item)
        edit { list ->
            val i = list.indexOfFirst { key(it) == k }
            if (i >= 0) list.toMutableList().also { it[i] = item } else list + item
        }
        return item
    }

    /** Changes the item with key [k] if it exists. Returns the new item or null. */
    fun <K> modify(key: (T) -> K, k: K, change: (T) -> T): T? {
        var result: T? = null
        edit { list ->
            val i = list.indexOfFirst { key(it) == k }
            if (i < 0) {
                list
            } else {
                val n = change(list[i])
                result = n
                list.toMutableList().also { it[i] = n }
            }
        }
        return result
    }

    fun clear() = edit { emptyList() }

    internal fun load(vault: Vault) {
        state.value = decodeList(vault.readText(name)).mapNotNull {
            try {
                fromJ(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    internal fun save(vault: Vault) {
        vault.writeText(name, encodeList(state.value.map(toJ)))
    }
}

/** All app data. Lists are saved a moment after they change (and immediately with [flush]). */
class Store(private val vault: Vault, private val scope: CoroutineScope) {
    val log = Col("log.json", 600, LogEntry::toJson, LogEntry::from)
    val mySos = Col("mysos.json", 30, MySos::toJson, MySos::from)
    val chat = Col("chat.json", 400, ChatItem::toJson, ChatItem::from)
    val alerts = Col("alerts.json", 150, AlertItem::toJson, AlertItem::from)
    val safe = Col("safe.json", 500, SafeItem::toJson, SafeItem::from)
    val reports = Col("reports.json", 300, ReportItem::toJson, ReportItem::from)
    val missing = Col("missing.json", 200, MissingItem::toJson, MissingItem::from)
    val finds = Col("finds.json", 50, FindAnswer::toJson, FindAnswer::from)

    val cases = Col("cases.json", 1000, CaseItem::toJson, CaseItem::from)
    val rescuers = Col("rescuers.json", 300, RescuerRecord::toJson, RescuerRecord::from)
    val nodes = Col("nodes.json", 500, NodeRecord::toJson, NodeRecord::from)
    val shelters = Col("shelters.json", 100, ShelterRecord::toJson, ShelterRecord::from)
    val checkins = Col("checkins.json", 3000, CheckInItem::toJson, CheckInItem::from)
    val volunteers = Col("volunteers.json", 300, VolunteerItem::toJson, VolunteerItem::from)

    val tasks = Col("tasks.json", 300, TaskItem::toJson, TaskItem::from)
    val seen = Col("seen.json", 300, SeenSos::toJson, SeenSos::from)
    val teamChat = Col("teamchat.json", 400, TeamMsg::toJson, TeamMsg::from)
    val crumbs = Col("crumbs.json", 2000, Crumb::toJson, Crumb::from)

    // version 2
    val polls = Col("polls.json", 60, PollItem::toJson, PollItem::from)
    val pollAnswers = Col("pollanswers.json", 3000, PollResponse::toJson, PollResponse::from)
    val revoked = Col("revoked.json", 500, RevokedItem::toJson, RevokedItem::from)
    val sensors = Col("sensors.json", 200, SensorItem::toJson, SensorItem::from)
    val children = Col("children.json", 20, ChildItem::toJson, ChildItem::from)
    val checklist = Col("checklist.json", 60, ChecklistItem::toJson, ChecklistItem::from)

    private val _profile = MutableStateFlow(Profile())
    val profile: StateFlow<Profile> = _profile

    private val all: List<Col<*>> = listOf(
        log, mySos, chat, alerts, safe, reports, missing, finds, cases, rescuers, nodes, shelters, checkins, volunteers,
        tasks, seen, teamChat, crumbs, polls, pollAnswers, revoked, sensors, children, checklist,
    )
    private val dirty = ConcurrentHashMap.newKeySet<String>()

    fun load() {
        for (c in all) {
            c.load(vault)
            c.onChange = { markDirty(c.name) }
        }
        _profile.value = try {
            vault.readText("profile.json")?.let { Profile.from(Json.parseObject(it)) } ?: Profile()
        } catch (e: Exception) {
            Profile()
        }
        scope.launch {
            while (true) {
                delay(1500)
                saveDirty()
            }
        }
    }

    fun setProfile(p: Profile) {
        _profile.value = p
        vault.writeText("profile.json", Json.stringify(p.toJson()))
    }

    private fun markDirty(name: String) {
        dirty.add(name)
    }

    @Synchronized
    private fun saveDirty() {
        if (dirty.isEmpty()) return
        for (c in all) {
            if (dirty.remove(c.name)) {
                try {
                    c.save(vault)
                } catch (e: Exception) {
                    // Keep going; it will be retried on the next change.
                }
            }
        }
    }

    fun flush() = saveDirty()

    /** Removes everything that belongs to one role (used when leaving rescuer or base mode). */
    fun clearRole(role: String) {
        when (role) {
            "RESCUER" -> listOf(tasks, seen, teamChat, crumbs).forEach { it.clear() }
            "BASE" -> listOf(cases, rescuers, nodes, shelters, checkins, volunteers).forEach { it.clear() }
        }
        flush()
    }
}
