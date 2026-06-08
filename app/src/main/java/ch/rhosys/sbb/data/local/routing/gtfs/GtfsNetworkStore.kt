package ch.rhosys.sbb.data.local.routing.gtfs

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GtfsNetworkStore @Inject constructor(dir: File) {
    private val dir = dir.also { it.mkdirs() }
    private val binaryFile = File(dir, "network.bin")
    private val metaFile = File(dir, "meta.txt")
    private val etagFile = File(dir, "etag.txt")
    private val urlFile  = File(dir, "url.txt")

    fun hasData(): Boolean = binaryFile.exists()

    fun lastImportMillis(): Long = runCatching { metaFile.readText().trim().toLong() }.getOrDefault(0L)

    fun lastEtag(): String? = runCatching { etagFile.readText().trim().takeIf { it.isNotBlank() } }.getOrNull()
    fun lastUrl():  String? = runCatching { urlFile.readText().trim().takeIf { it.isNotBlank() } }.getOrNull()

    fun writeEtag(etag: String) { etagFile.writeText(etag) }
    fun writeUrl(url: String)   { urlFile.writeText(url) }

    fun write(parsed: GtfsParser.ParsedGtfs) {
        val tmp = File(dir, "network.bin.tmp")
        tmp.outputStream().buffered().use { out ->
            GtfsNetworkSerializer.write(
                network = parsed.network,
                patternRows = parsed.calendarPatternRows,
                exceptionRows = parsed.calendarExceptionRows,
                out = out,
            )
        }
        // Atomic swap: rename guarantees no partial reads see half-written data
        tmp.renameTo(binaryFile)
        metaFile.writeText(System.currentTimeMillis().toString())
    }

    fun read(): GtfsParser.ParsedGtfs? {
        if (!binaryFile.exists()) return null
        return runCatching {
            binaryFile.inputStream().buffered().use { input ->
                val (network, patternRows, exceptionRows) = GtfsNetworkSerializer.read(input)
                GtfsParser.ParsedGtfs(
                    network = network,
                    calendar = GtfsCalendarResolver(patternRows, exceptionRows),
                    calendarPatternRows = patternRows,
                    calendarExceptionRows = exceptionRows,
                )
            }
        }.getOrNull()
    }

    fun delete() {
        binaryFile.delete()
        metaFile.delete()
    }
}
