package com.sterbenden.clockedin.core

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Stores the ledger as JSON in a single file. Writes go to a temporary file first and
 * are then moved into place, so a crash never leaves a half-written file.
 */
class SessionStore(private val file: Path) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Problems found while loading, for logging by the caller. */
    var lastLoadProblem: String? = null
        private set

    fun load(): LedgerData {
        lastLoadProblem = null
        if (!Files.exists(file)) return LedgerData()
        return try {
            json.decodeFromString(LedgerData.serializer(), Files.readString(file))
        } catch (e: Exception) {
            val backup = file.resolveSibling("${file.fileName}.corrupt-${Instant.now().toEpochMilli()}")
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING)
            lastLoadProblem = "Could not read ${file}; moved it to $backup and started empty (${e.message})"
            LedgerData()
        }
    }

    fun save(data: LedgerData) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(LedgerData.serializer(), data))
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
