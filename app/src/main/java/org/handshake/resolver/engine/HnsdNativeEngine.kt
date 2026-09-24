package org.handshake.resolver.engine

import android.util.Log
import java.io.File

class HnsdNativeEngine : HnsEngine {

    override val engineName: String = "hnsd (C / NDK SPV)"
    override val defaultPort: Int = 5349

    override fun start(dataDir: File, nsPort: Int): Boolean {
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
        Log.i(TAG, "Starting $engineName on port $nsPort with dataDir: ${dataDir.absolutePath}")
        return HnsdNative.nativeStart(dataDir.absolutePath, nsPort)
    }

    override fun stop() {
        Log.i(TAG, "Stopping $engineName")
        HnsdNative.nativeStop()
    }

    override fun getProgress(): Float = HnsdNative.nativeGetProgress()

    override fun getHeight(): Int = HnsdNative.nativeGetHeight()

    override fun isSynced(): Boolean = HnsdNative.nativeIsSynced()

    override fun isRunning(): Boolean = HnsdNative.nativeIsRunning()

    override fun isIcannTld(tld: String): Boolean = HnsdNative.nativeIsIcannTld(tld)

    companion object {
        private const val TAG = "HnsdNativeEngine"
    }
}
