package org.handshake.resolver.engine

import java.io.File

/**
 * Interface abstracting the Handshake resolver engine.
 *
 * This decouples the engine implementation (e.g. C/NDK hnsd or future Node.js hsd SPV)
 * from the Android VpnService and UI layer.
 */
interface HnsEngine {
    val engineName: String
    val defaultPort: Int

    /**
     * Start the Handshake SPV resolver.
     * @param dataDir Storage directory for chain headers and checkpoints.
     * @param nsPort Port to bind the local nameserver (e.g. 5349).
     * @return true if started successfully.
     */
    fun start(dataDir: File, nsPort: Int = defaultPort): Boolean

    /**
     * Gracefully stops the resolver and releases resources.
     */
    fun stop()

    /**
     * Returns chain sync progress from 0.0 to 1.0 (100%).
     */
    fun getProgress(): Float

    /**
     * Returns current block height of the SPV chain tip.
     */
    fun getHeight(): Int

    /**
     * Returns true if chain is fully synced with network.
     */
    fun isSynced(): Boolean

    /**
     * Returns true if the resolver engine is currently running.
     */
    fun isRunning(): Boolean

    /**
     * Checks if a TLD belongs to the ICANN root zone.
     */
    fun isIcannTld(tld: String): Boolean
}
