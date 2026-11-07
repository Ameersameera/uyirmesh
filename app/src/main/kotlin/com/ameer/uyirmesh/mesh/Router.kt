package com.ameer.uyirmesh.mesh

import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.AckPayload
import com.ameer.uyirmesh.core.AlertPayload
import com.ameer.uyirmesh.core.Authenticity
import com.ameer.uyirmesh.core.ChatPayload
import com.ameer.uyirmesh.core.CryptoException
import com.ameer.uyirmesh.core.Destinations
import com.ameer.uyirmesh.core.FindRequest
import com.ameer.uyirmesh.core.FindResult
import com.ameer.uyirmesh.core.HeartbeatPayload
import com.ameer.uyirmesh.core.MissingPayload
import com.ameer.uyirmesh.core.Opener
import com.ameer.uyirmesh.core.Packet
import com.ameer.uyirmesh.core.PacketFlags
import com.ameer.uyirmesh.core.PacketType
import com.ameer.uyirmesh.core.ReportPayload
import com.ameer.uyirmesh.core.Roles
import com.ameer.uyirmesh.core.SafePayload
import com.ameer.uyirmesh.core.SosCancel
import com.ameer.uyirmesh.core.SosMedia
import com.ameer.uyirmesh.core.SosPayload
import com.ameer.uyirmesh.core.SosUpdate
import com.ameer.uyirmesh.core.TaskPayload
import com.ameer.uyirmesh.core.TaskStatusPayload
import com.ameer.uyirmesh.core.TeamChatPayload
import com.ameer.uyirmesh.core.VolunteerPayload
import com.ameer.uyirmesh.core.CaseSync
import com.ameer.uyirmesh.core.HazardPayload
import com.ameer.uyirmesh.core.PollAnswer
import com.ameer.uyirmesh.core.PollPayload
import com.ameer.uyirmesh.core.RevokePayload
import com.ameer.uyirmesh.core.SensorPayload
import com.ameer.uyirmesh.rescuer.TeamVoice

/** Hands every packet addressed to this phone to the part of the app that uses it. */
class Router {
    private val myId: String get() = Graph.prefs.now.deviceId

    /** Base work happens only while this phone is in base mode (keys stay stored after "Leave base mode"). */
    private val isBase: Boolean get() = Graph.prefs.now.role == Roles.BASE && Graph.base.hasKeys()

    fun handle(p: Packet) {
        val base = Graph.base
        val rescuer = Graph.rescuer
        when (p.type) {
            PacketType.SOS, PacketType.SOS_UPDATE, PacketType.SOS_MEDIA, PacketType.SOS_CANCEL -> {
                if (p.flags and PacketFlags.ENC_BASE == 0) return
                if (isBase) base.keys()?.let { keys ->
                    val plain = open { Opener.asBase(keys, Graph.pinned, p) } ?: return@let
                    when (p.type) {
                        PacketType.SOS -> base.onSos(p, SosPayload.fromBytes(plain))
                        PacketType.SOS_UPDATE -> base.onUpdate(p, SosUpdate.fromBytes(plain))
                        PacketType.SOS_MEDIA -> base.onMedia(p, SosMedia.fromBytes(plain))
                        else -> base.onCancel(p, SosCancel.fromBytes(plain))
                    }
                }
                rescuer.bundle()?.let { b ->
                    val plain = open { Opener.asRescuer(b, Graph.pinned, p) } ?: return@let
                    when (p.type) {
                        PacketType.SOS -> rescuer.onSos(p, SosPayload.fromBytes(plain))
                        PacketType.SOS_UPDATE -> rescuer.onUpdate(p, SosUpdate.fromBytes(plain))
                        PacketType.SOS_MEDIA -> rescuer.onMedia(p, SosMedia.fromBytes(plain))
                        else -> rescuer.onCancel(p, SosCancel.fromBytes(plain))
                    }
                }
            }

            PacketType.ACK -> {
                if (p.dst != myId) return
                val ack = AckPayload.fromBytes(p.payload)
                val by = if (Authenticity.isBaseSigned(Graph.pinned, p)) {
                    "BASE"
                } else {
                    Authenticity.rescuerSigner(Graph.pinned, p, System.currentTimeMillis())?.name ?: return
                }
                Graph.victim.onReply(p, ack, by)
            }

            PacketType.PROGRESS -> {
                // A rescuer on the way says how far away they are; only a base-issued rescuer ID can sign it.
                if (p.dst != myId) return
                val signer = Authenticity.rescuerSigner(Graph.pinned, p, System.currentTimeMillis()) ?: return
                Graph.victim.onProgress(AckPayload.fromBytes(p.payload), signer.name)
            }

            PacketType.ALERT -> {
                if (!Authenticity.isBaseSigned(Graph.pinned, p)) return
                Graph.public.onAlert(p, AlertPayload.fromBytes(p.payload))
            }

            PacketType.CHAT -> Graph.public.onChat(p, ChatPayload.fromBytes(p.payload))
            PacketType.SAFE -> {
                val safe = SafePayload.fromBytes(p.payload)
                Graph.public.onSafe(p, safe)
                if (isBase) base.onSafe(p, safe)
            }
            PacketType.REPORT -> Graph.public.onReport(p, ReportPayload.fromBytes(p.payload))
            PacketType.MISSING -> Graph.public.onMissing(p, MissingPayload.fromBytes(p.payload))

            PacketType.VOLUNTEER -> if (isBase) base.keys()?.let { keys ->
                open { Opener.asBase(keys, Graph.pinned, p) }?.let { base.onVolunteer(p, VolunteerPayload.fromBytes(it)) }
            }

            PacketType.HEARTBEAT -> if (isBase) base.keys()?.let { keys ->
                open { Opener.asBase(keys, Graph.pinned, p) }?.let { base.onHeartbeat(p.src, HeartbeatPayload.fromBytes(it), p.hops) }
            }

            PacketType.FIND_REQUEST -> if (isBase) base.onFindRequest(p, FindRequest.fromBytes(p.payload))

            PacketType.FIND_RESULT -> {
                if (p.dst != myId || !Authenticity.isBaseSigned(Graph.pinned, p)) return
                Graph.public.onFindResult(FindResult.fromBytes(p.payload))
            }

            PacketType.TASK -> {
                if (!Destinations.isTeam(p.dst) || !Authenticity.isBaseSigned(Graph.pinned, p)) return
                val b = rescuer.bundle() ?: return
                if (Destinations.teamName(p.dst) != b.cert.team) return
                open { Opener.team(b.teamKey, p) }?.let { rescuer.onTask(p, TaskPayload.fromBytes(it)) }
            }

            PacketType.TASK_STATUS -> {
                if (!Destinations.isTeam(p.dst)) return
                val team = Destinations.teamName(p.dst)
                teamKey(team)?.let { key ->
                    open { Opener.team(key, p) }?.let { plain ->
                        val st = TaskStatusPayload.fromBytes(plain)
                        if (isBase) base.onTaskStatus(p, st)
                        if (rescuer.bundle() != null) rescuer.onTeammateStatus(p, st)
                    }
                }
            }

            PacketType.TEAM_CHAT -> {
                if (!Destinations.isTeam(p.dst)) return
                val team = Destinations.teamName(p.dst)
                teamKey(team)?.let { key ->
                    open { Opener.team(key, p) }?.let { plain ->
                        val msg = TeamChatPayload.fromBytes(plain)
                        if (rescuer.bundle() != null) rescuer.onTeamChat(p, msg)
                        if (isBase) base.onTeamChat(p, team, msg)
                    }
                }
            }

            PacketType.TEAM_VOICE -> {
                if (!Destinations.isTeam(p.dst)) return
                val team = Destinations.teamName(p.dst)
                teamKey(team)?.let { key ->
                    open { Opener.team(key, p) }?.let { plain ->
                        val clip = SosMedia.fromBytes(plain)
                        TeamVoice.onClip(p, team, clip, toBase = isBase, toRescuer = rescuer.bundle() != null)
                    }
                }
            }

            PacketType.HAZARD -> {
                val signer = if (Authenticity.isBaseSigned(Graph.pinned, p)) {
                    "BASE"
                } else {
                    Authenticity.rescuerSigner(Graph.pinned, p, System.currentTimeMillis())?.name ?: return
                }
                Graph.safety.onHazard(p, HazardPayload.fromBytes(p.payload), signer)
            }

            PacketType.POLL -> {
                if (!Authenticity.isBaseSigned(Graph.pinned, p)) return
                Graph.safety.onPoll(p, PollPayload.fromBytes(p.payload))
            }

            PacketType.POLL_ANSWER -> if (isBase) base.keys()?.let { keys ->
                open { Opener.asBase(keys, Graph.pinned, p) }?.let { Graph.baseOps.onPollAnswer(p.src, PollAnswer.fromBytes(it)) }
            }

            PacketType.BASE_SYNC -> {
                if (!isBase || p.src == myId || !Authenticity.isBaseSigned(Graph.pinned, p)) return
                Graph.baseOps.onCaseSync(p, CaseSync.fromBytes(p.payload))
            }

            PacketType.REVOKE -> {
                if (!Authenticity.isBaseSigned(Graph.pinned, p)) return
                Graph.safety.onRevoke(RevokePayload.fromBytes(p.payload))
            }

            PacketType.SENSOR -> Graph.safety.onSensor(p, SensorPayload.fromBytes(p.payload))

            PacketType.CHILD_FOUND -> if (p.dst == myId) Graph.safety.onChildFound(p)
        }
    }

    private fun teamKey(team: String): ByteArray? {
        Graph.rescuer.bundle()?.let { if (it.cert.team == team) return it.teamKey }
        return if (isBase) Graph.base.teamKey(team, create = false) else null
    }

    private inline fun open(block: () -> ByteArray): ByteArray? = try {
        block()
    } catch (e: CryptoException) {
        null
    } catch (e: Exception) {
        null
    }
}
