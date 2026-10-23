package org.swatonly.xmrvault

/**
 * The Rust core (libxmrvault.so). Secrets go in and out as ByteArray so callers can zero them;
 * errors arrive as exceptions (SecurityException = wrong PIN, IllegalArgumentException = bad
 * input). Every call may block on the network or on Argon2: never call from the UI thread.
 */
object Native {
    init {
        System.loadLibrary("xmrvault")
    }

    const val UNLOCKED = 0
    const val WRONG_PIN = 1
    const val DURESS = 2

    external fun init(dir: String)
    external fun exists(): Boolean
    external fun create(pin: ByteArray, pepper: ByteArray, net: Int, legacy: Boolean): ByteArray
    external fun restore(pin: ByteArray, pepper: ByteArray, net: Int, phrase: ByteArray, height: Long)
    external fun unlock(pin: ByteArray, pepper: ByteArray): Int
    external fun lock()
    external fun isUnlocked(): Boolean
    external fun status(): String
    external fun setNode(json: String)
    external fun node(): String
    external fun testNode(): Long
    external fun syncStep(): String
    external fun address(index: Int): String
    external fun newSubaddress(): String
    external fun validateAddress(address: String): Boolean
    external fun prepareSend(destination: String, amount: Long, priority: Int): String
    external fun confirmSend(pin: ByteArray, pepper: ByteArray): String
    external fun cancelSend()
    external fun history(): String
    external fun revealSeed(pin: ByteArray, pepper: ByteArray): ByteArray
    external fun verifyPin(pin: ByteArray, pepper: ByteArray): Boolean
    external fun changePin(old: ByteArray, new: ByteArray, pepper: ByteArray)
    external fun setDuressPin(pin: ByteArray, duress: ByteArray?, pepper: ByteArray)
    external fun rescan(height: Long)
    external fun wipe()
    external fun qr(text: String): BooleanArray
    external fun securityReport(): String
}
