package com.hereliesaz.doxray.db

import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import java.util.zip.ZipInputStream

class DatabaseImporter(
    private val identityDao: IdentityDao,
    private val encounterDao: EncounterDao,
    private val auditDao: AuditDao,
) {

    private val TAG = "DatabaseImporter"

    data class Report(
        val identitiesImported: Int, val identitiesSkipped: Int, val identitiesMalformed: Int,
        val encountersImported: Int, val encountersDeduped: Int, val encountersMalformed: Int,
        val auditImported: Int, val auditDeduped: Int, val auditMalformed: Int,
    ) {
        fun summary(): String =
            "Import complete:\n" +
            "  Identities: $identitiesImported imported, $identitiesSkipped skipped, $identitiesMalformed malformed\n" +
            "  Encounters: $encountersImported imported, $encountersDeduped deduped, $encountersMalformed malformed\n" +
            "  Audit: $auditImported imported, $auditDeduped deduped, $auditMalformed malformed"
    }

    suspend fun import(input: InputStream): Report {
        val entries = readZipEntries(input)
        val manifestText = entries["manifest.json"]
            ?: throw IllegalStateException("manifest.json missing from import")
        val manifest = runCatching { JSONObject(manifestText) }.getOrElse {
            throw IllegalStateException("manifest.json malformed")
        }
        val schemaVersion = manifest.optInt("schemaVersion", -1)
        if (schemaVersion > DatabaseExporter.SCHEMA_VERSION) {
            throw IllegalStateException("Import schema v$schemaVersion newer than current v${DatabaseExporter.SCHEMA_VERSION}")
        }

        val identitiesCsv = entries["identities.csv"]
            ?: throw IllegalStateException("identities.csv missing from import")
        val encountersCsv = entries["encounters.csv"]
            ?: throw IllegalStateException("encounters.csv missing from import")
        val auditCsv = entries["audit.csv"]
            ?: throw IllegalStateException("audit.csv missing from import")

        var idsImported = 0; var idsSkipped = 0; var idsMalformed = 0
        for (row in parseCsv(identitiesCsv).drop(1)) {
            val rec = parseIdentityRow(row)
            if (rec == null) { idsMalformed++; continue }
            val existing = identityDao.getIdentityById(rec.faceId)
            if (existing != null) { idsSkipped++; continue }
            runCatching { identityDao.insertIdentity(rec) }
                .onSuccess { idsImported++ }
                .onFailure { idsMalformed++ }
        }

        var encImported = 0; var encDeduped = 0; var encMalformed = 0
        val existingEncKeys = mutableSetOf<Pair<String, Long>>()
        for (rec in identityDao.getAllIdentities()) {
            for (e in encounterDao.getAllByFace(rec.faceId)) {
                existingEncKeys.add(e.faceId to e.timestamp)
            }
        }
        for (row in parseCsv(encountersCsv).drop(1)) {
            val enc = parseEncounterRow(row)
            if (enc == null) { encMalformed++; continue }
            if ((enc.faceId to enc.timestamp) in existingEncKeys) { encDeduped++; continue }
            runCatching { encounterDao.insert(enc) }
                .onSuccess {
                    encImported++
                    existingEncKeys.add(enc.faceId to enc.timestamp)
                }
                .onFailure { encMalformed++ }
        }

        var auditImported = 0; var auditDeduped = 0; var auditMalformed = 0
        val existingAuditKeys = auditDao.getAll().map { Triple(it.timestamp, it.type, it.summary) }.toMutableSet()
        for (row in parseCsv(auditCsv).drop(1)) {
            val evt = parseAuditRow(row)
            if (evt == null) { auditMalformed++; continue }
            val key = Triple(evt.timestamp, evt.type, evt.summary)
            if (key in existingAuditKeys) { auditDeduped++; continue }
            runCatching { auditDao.insert(evt) }
                .onSuccess {
                    auditImported++
                    existingAuditKeys.add(key)
                }
                .onFailure { auditMalformed++ }
        }

        return Report(
            idsImported, idsSkipped, idsMalformed,
            encImported, encDeduped, encMalformed,
            auditImported, auditDeduped, auditMalformed,
        )
    }

    private fun readZipEntries(input: InputStream): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(input).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                out[e.name] = zis.readBytes().toString(Charsets.UTF_8)
                e = zis.nextEntry
            }
        }
        return out
    }

    private fun parseIdentityRow(cells: List<String>): IdentityRecord? {
        if (cells.size < 9) return null
        return try {
            IdentityRecord(
                faceId = cells[0],
                primaryIdentity = cells[1],
                embedding = parseEmbedding(cells[2]) ?: return null,
                socialLinks = cells[3],
                backgroundData = cells[4],
                visibleText = cells[5].ifBlank { null },
                firstSeenTimestamp = cells[6].toLong(),
                lastSeenTimestamp = cells[7].toLong(),
                encounterCount = cells[8].toInt(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Identity row parse failed: ${e.message}")
            null
        }
    }

    private fun parseEncounterRow(cells: List<String>): Encounter? {
        if (cells.size < 6) return null
        return try {
            Encounter(
                id = 0, // let Room autoincrement
                faceId = cells[1],
                timestamp = cells[2].toLong(),
                latitude = cells[3].toDoubleOrNull(),
                longitude = cells[4].toDoubleOrNull(),
                locationAccuracyMeters = cells[5].toFloatOrNull(),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAuditRow(cells: List<String>): AuditEvent? {
        if (cells.size < 5) return null
        return try {
            AuditEvent(
                id = 0,
                timestamp = cells[1].toLong(),
                type = cells[2],
                summary = cells[3],
                detailsJson = cells[4],
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseEmbedding(cell: String): FloatArray? {
        if (cell.isBlank()) return null
        return try {
            cell.split(",").map { it.toFloat() }.toFloatArray()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Minimal RFC 4180 reader: supports quoted cells with embedded quotes ("")
     * and embedded commas/newlines.
     */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        var cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i += 2; continue }
                    c == '"' -> { inQuotes = false; i++; continue }
                    else -> { cell.append(c); i++ }
                }
            } else {
                when (c) {
                    '"' -> { inQuotes = true; i++ }
                    ',' -> { row.add(cell.toString()); cell = StringBuilder(); i++ }
                    '\n' -> { row.add(cell.toString()); rows.add(row); row = mutableListOf(); cell = StringBuilder(); i++ }
                    '\r' -> { i++ }
                    else -> { cell.append(c); i++ }
                }
            }
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }
        return rows
    }
}
