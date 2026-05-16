package com.hereliesaz.doxray.db

import org.json.JSONObject
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DatabaseExporter(
    private val identityDao: IdentityDao,
    private val encounterDao: EncounterDao,
    private val auditDao: AuditDao,
) {

    companion object {
        const val SCHEMA_VERSION = 4
    }

    suspend fun export(out: OutputStream) {
        val identities = identityDao.getAllIdentities()
        val zip = ZipOutputStream(out)

        zip.putNextEntry(ZipEntry("identities.csv"))
        OutputStreamWriter(zip, Charsets.UTF_8).let { w ->
            w.write("faceId,primaryIdentity,embedding,socialLinks,backgroundData,visibleText,firstSeenTimestamp,lastSeenTimestamp,encounterCount\n")
            for (r in identities) {
                val cells = listOf(
                    r.faceId,
                    r.primaryIdentity,
                    r.embedding.joinToString(","),
                    r.socialLinks,
                    r.backgroundData,
                    r.visibleText.orEmpty(),
                    r.firstSeenTimestamp.toString(),
                    r.lastSeenTimestamp.toString(),
                    r.encounterCount.toString(),
                )
                w.write(cells.joinToString(",") { csvCell(it) })
                w.write("\n")
            }
            w.flush()
        }
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("encounters.csv"))
        OutputStreamWriter(zip, Charsets.UTF_8).let { w ->
            w.write("id,faceId,timestamp,latitude,longitude,locationAccuracyMeters\n")
            for (r in identities) {
                val rows = encounterDao.getAllByFace(r.faceId)
                for (e in rows) {
                    val cells = listOf(
                        e.id.toString(),
                        e.faceId,
                        e.timestamp.toString(),
                        e.latitude?.toString().orEmpty(),
                        e.longitude?.toString().orEmpty(),
                        e.locationAccuracyMeters?.toString().orEmpty(),
                    )
                    w.write(cells.joinToString(",") { csvCell(it) })
                    w.write("\n")
                }
            }
            w.flush()
        }
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("audit.csv"))
        OutputStreamWriter(zip, Charsets.UTF_8).let { w ->
            w.write("id,timestamp,type,summary,detailsJson\n")
            val events = auditDao.getAll()
            for (e in events) {
                val cells = listOf(
                    e.id.toString(),
                    e.timestamp.toString(),
                    e.type,
                    e.summary,
                    e.detailsJson,
                )
                w.write(cells.joinToString(",") { csvCell(it) })
                w.write("\n")
            }
            w.flush()
        }
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("manifest.json"))
        val manifest = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("exportedAt", iso8601(System.currentTimeMillis()))
            .put("identityCount", identities.size)
        OutputStreamWriter(zip, Charsets.UTF_8).let { w ->
            w.write(manifest.toString())
            w.flush()
        }
        zip.closeEntry()

        zip.finish()
    }

    private fun csvCell(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun iso8601(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }
}
