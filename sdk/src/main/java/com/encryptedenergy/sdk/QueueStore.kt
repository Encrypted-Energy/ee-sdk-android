package com.encryptedenergy.sdk

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Disk persistence for the packet queue (plan §1a, iOS 1.1 lesson: a queued
 * packet must survive process death — eid 439e42fd sat on a phone for 56 min
 * because it didn't). Pure JVM.
 *
 * Format: one packet per line, pipe-delimited (none of the fields can
 * contain '|': base64, hex, decimal numbers). Line 1 is the format marker.
 * Writes go temp-file-then-rename on a single daemon thread, coalescing
 * bursts; a torn write costs the file, never a crash — load skips anything
 * malformed.
 */
internal class QueueStore(private val file: File) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ee-queue-store").apply { isDaemon = true }
    }
    private val persistScheduled = AtomicBoolean(false)

    /** Missing/corrupt file -> empty list; malformed lines are skipped. */
    fun load(): List<UploadPacket> {
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().mapNotNull(::decode)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Coalesced async persist; snapshot is taken on the writer thread. */
    fun schedulePersist(queue: PacketQueue) {
        if (!persistScheduled.compareAndSet(false, true)) return
        executor.execute {
            persistScheduled.set(false)
            persistNow(queue.snapshot())
        }
    }

    fun persistNow(packets: List<UploadPacket>) {
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(
                buildString {
                    append(FORMAT_MARKER).append('\n')
                    packets.forEach { append(encode(it)).append('\n') }
                }
            )
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
            // Persistence is best-effort; the live queue is the source of
            // truth and must never be endangered by a disk problem.
        }
    }

    companion object {
        private const val FORMAT_MARKER = "eeq1"

        fun encode(p: UploadPacket): String = listOf(
            p.eidHex,
            p.payloadB64,
            p.rssi.toString(),
            p.timestampSeconds.toString(),
            p.latitude.toString(),
            p.longitude.toString(),
            p.positionAtSeconds.toString(),
            p.accuracyM?.toString() ?: "",
        ).joinToString("|")

        fun decode(line: String): UploadPacket? {
            val parts = line.split("|")
            if (parts.size != 8) return null
            return try {
                UploadPacket(
                    eidHex = parts[0].ifEmpty { return null },
                    payloadB64 = parts[1].ifEmpty { return null },
                    rssi = parts[2].toInt(),
                    timestampSeconds = parts[3].toLong(),
                    latitude = parts[4].toDouble(),
                    longitude = parts[5].toDouble(),
                    positionAtSeconds = parts[6].toLong(),
                    accuracyM = parts[7].ifEmpty { null }?.toDouble(),
                )
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}
