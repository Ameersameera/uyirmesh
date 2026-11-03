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
import com.ameer.uyirmesh.core.SafePayload
import com.ameer.uyirmesh.core.SosCancel
import com.ameer.uyirmesh.core.SosMedia
import com.ameer.uyirmesh.core.SosPayload
import com.ameer.uyirmesh.core.SosUpdate
import com.ameer.uyirmesh.core.TaskPayload
import com.ameer.uyirmesh.core.TaskStatusPayload
import com.ameer.uyirmesh.core.TeamChatPayload
import com.ameer.uyirmesh.core.VolunteerPayload

/** Hands every packet addressed to this phone to the part of the app that uses it. */
class Router {
    private val myId: String get() = Graph.prefs.now.deviceId

    fun handle(p: Packet) {
        val base = Graph.base
        val rescuer = Graph.rescuer
        when (p.type) {
            PacketType.SOS, PacketType.SOS_UPDATE, PacketType.SOS_MEDIA, PacketType.SOS_CANCEL -> {
                if (p.flags and PacketFlags.ENC_BASE == 0) return
                base.keys()?.let { keys ->
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

            PacketType.ALERT -> {
                if (!Authenticity.isBaseSigned(Graph.pinned, p)) return
                Graph.public.onAlert(p, AlertPayload.fromBytes(p.payload))
            }

            PacketType.CHAT -> Graph.public.onChat(p, ChatPayload.fromBytes(p.payload))
            PacketType.SAFE -> {
                val safe = SafePayload.fromBytes(p.payload)
                Graph.public.onSafe(p, safe)
                if (base.hasKeys()) base.onSafe(p, safe)
            }
            PacketType.REPORT -> Graph.public.onReport(p, ReportPayload.fromBytes(p.payload))
            PacketType.MISSING -> Graph.public.onMissing(p, MissingPayload.fromBytes(p.payload))

            PacketType.VOLUNTEER -> base.keys()?.let { keys ->
                open { Opener.asBase(keys, Graph.pinned, p) }?.let { base.onVolunteer(p, VolunteerPayload.fromBytes(it)) }
            }

            PacketType.HEARTBEAT -> base.keys()?.let { keys ->
                open { Opener.asBase(keys, Graph.pinned, p) }?.let { base.onHeartbeat(p.src, HeartbeatPayload.fromBytes(it), p.hops) }
            }

            PacketType.FIND_REQUEST -> if (base.hasKeys()) base.onFindRequest(p, FindRequest.fromBytes(p.payload))

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
                        if (base.hasKeys()) base.onTaskStatus(p, st)
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
                        if (base.hasKeys()) base.onTeamChat(p, team, msg)
                    }
                }
            }
        }
    }

    private fun teamKey(team: String): ByteArray? {
        Graph.rescuer.bundle()?.let { if (it.cert.team == team) return it.teamKey }
        return if (Graph.base.hasKeys()) Graph.base.teamKey(team, create = false) else null
    }

    private inline fun open(block: () -> ByteArray): ByteArray? = try {
        block()
    } catch (e: CryptoException) {
        null
    } catch (e: Exception) {
        null
    }
}
