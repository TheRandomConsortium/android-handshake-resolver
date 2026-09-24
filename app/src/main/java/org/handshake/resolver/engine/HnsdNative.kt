package org.handshake.resolver.engine

object HnsdNative {
    init {
        System.loadLibrary("hsk_jni")
    }

    external fun nativeStart(dataDir: String, nsPort: Int): Boolean
    external fun nativeStop()
    external fun nativeGetProgress(): Float
    external fun nativeGetHeight(): Int
    external fun nativeIsSynced(): Boolean
    external fun nativeIsRunning(): Boolean
    external fun nativeIsIcannTld(tld: String): Boolean
}
