package com.ameer.uyirmesh.base

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.ameer.uyirmesh.Graph
import com.ameer.uyirmesh.core.SecureZip
import com.ameer.uyirmesh.data.CaseItem
import com.ameer.uyirmesh.data.CaseStatus
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** CSV and PDF incident reports made on the base phone (also used by the web dashboard). */
object Reports {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private fun t(at: Long): String = if (at > 0) fmt.format(Date(at)) else ""

    private fun csvCell(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun csv(): String {
        val header = listOf(
            "case_id", "received", "priority", "score", "status", "kind", "people", "children", "elderly", "disabled",
            "pregnant", "injured", "needs", "name", "phone", "blood", "medical", "battery", "lat", "lon", "hops",
            "team", "rescuer", "eta_min", "assigned", "rescued", "times_sent", "duplicate_of", "drill", "note", "reasons",
            "animals", "silent", "reported_for", "checks", "outcome", "handled_by",
        )
        val sb = StringBuilder(header.joinToString(",")).append('\n')
        for (c in Graph.store.cases.value.sortedBy { it.firstAt }) {
            val p = c.payload
            sb.append(
                listOf(
                    c.caseId, t(c.firstAt), c.label, c.score, c.status, p.kind, p.people, p.children, p.elderly, p.disabled,
                    p.pregnant, p.injured, p.needs.joinToString(" "), p.name, p.phone, p.blood, p.medical, c.battery,
                    c.lat ?: "", c.lon ?: "", c.hops, c.team, c.rescuerName, c.etaMin, t(c.assignedAt), t(c.rescuedAt),
                    c.repeats + 1, c.duplicateOf, c.drill, p.note, c.reasons.joinToString("; ") { "${it.label} +${it.points}" },
                    p.animals, p.silent, if (p.forOther) listOf(p.otherName, p.otherPhone).filter { it.isNotBlank() }.joinToString(" ") else "",
                    c.flags.joinToString(" "), c.outcome, c.handledBy,
                ).joinToString(",") { csvCell(it) },
            ).append('\n')
        }
        return sb.toString()
    }

    fun writeCsv(ctx: Context): File? = try {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        File(dir, "uyirmesh_cases_${System.currentTimeMillis()}.csv").apply { writeText(csv()) }
    } catch (e: Exception) {
        Graph.note("csv", e)
        null
    }

    /**
     * The cases (CSV) and the report (PDF) in a password-protected ZIP (AES-256), so a lost or forwarded file does
     * not expose victims' names, phone numbers and positions.
     */
    fun writeSecureZip(ctx: Context, password: String): File? = try {
        val pdf = writePdf(ctx)
        val now = System.currentTimeMillis()
        val entries = buildList {
            add(SecureZip.Entry("uyirmesh_cases.csv", csv().toByteArray(Charsets.UTF_8), now))
            if (pdf != null) add(SecureZip.Entry("uyirmesh_report.pdf", pdf.readBytes(), now))
            add(SecureZip.Entry("README.txt", readme(now).toByteArray(Charsets.UTF_8), now))
        }
        pdf?.delete()
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        File(dir, "uyirmesh_cases_$now.zip").apply { writeBytes(SecureZip.write(entries, password)) }
    } catch (e: Exception) {
        Graph.note("secure zip", e)
        null
    }

    private fun readme(now: Long): String = """
        UyirMesh case export
        Base: ${Graph.prefs.now.baseName}
        Made: ${t(now)}
        Cases: ${Graph.store.cases.value.size}

        uyirmesh_cases.csv  - one line per SOS (open it in a spreadsheet)
        uyirmesh_report.pdf - summary report

        This file contains personal data of people in danger. Keep it private and delete it when it is no longer needed.
    """.trimIndent()

    /** A4 report: summary numbers followed by one line per case, most urgent first. */
    fun writePdf(ctx: Context): File? = try {
        val doc = PdfDocument()
        val w = 595
        val h = 842
        val title = Paint().apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val head = Paint().apply { textSize = 11f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val body = Paint().apply { textSize = 9f; isAntiAlias = true }
        val cases = Graph.store.cases.value
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
        var canvas = page.canvas
        var y = 50f
        fun newPage() {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            canvas = page.canvas
            y = 50f
        }
        fun line(text: String, p: Paint, gap: Float = 14f) {
            if (y > h - 50) newPage()
            canvas.drawText(text, 40f, y, p)
            y += gap
        }
        line("UyirMesh incident report - ${Graph.prefs.now.baseName}", title, 24f)
        line("Generated ${t(System.currentTimeMillis())} · base key ${Graph.pinned.sigFingerprint}", body, 20f)
        val open = cases.count { it.isOpen }
        val rescued = cases.count { it.status == CaseStatus.RESCUED }
        val people = cases.sumOf { it.payload.people }
        line("Summary", head, 16f)
        line("Total SOS cases: ${cases.size}   Open: $open   Rescued: $rescued   Cancelled: ${cases.count { it.status == CaseStatus.CANCELLED }}", body)
        line("People reported: $people   Injured cases: ${cases.count { it.payload.injured }}   With children: ${cases.count { it.payload.children > 0 }}", body)
        for (label in listOf("Critical", "High", "Medium", "Low")) line("  $label: ${cases.count { it.label == label }}", body)
        val avgMin = cases.filter { it.rescuedAt > 0 }.map { (it.rescuedAt - it.firstAt) / 60_000.0 }.average()
        if (!avgMin.isNaN()) line("Average time from SOS to rescue: ${"%.1f".format(Locale.US, avgMin)} min", body)
        line("Teams: ${Graph.base.teams().joinToString()}   Shelters: ${Graph.store.shelters.value.size}   Volunteers: ${Graph.store.volunteers.value.size}", body, 22f)
        line("Cases (most urgent first)", head, 16f)
        for (c in cases.sortedWith(compareByDescending<CaseItem> { it.isOpen }.thenByDescending { it.score })) {
            val p = c.payload
            line("${c.caseId.take(6)}  ${c.label} ${c.score}  ${c.status}  ${t(c.firstAt)}  ${p.kind} · ${p.people} ppl${if (p.injured) " · injured" else ""}  ${c.team}", body, 12f)
            val loc = if (c.lat != null && c.lon != null) "%.5f,%.5f".format(Locale.US, c.lat, c.lon) else "no GPS"
            line("      ${p.name} ${p.phone}  $loc  ${c.hops} hops  ${c.reasons.take(3).joinToString { it.label }}", body, 15f)
        }
        doc.finishPage(page)
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "uyirmesh_report_${System.currentTimeMillis()}.pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        f
    } catch (e: Exception) {
        Graph.note("pdf", e)
        null
    }
}
