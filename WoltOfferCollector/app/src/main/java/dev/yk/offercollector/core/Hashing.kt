package dev.yk.offercollector.core

import java.security.MessageDigest

object Hashing {
    /** Salted SHA-256, hex. The salt is per-install and rotated on Delete All. */
    fun saltedSha256(salt: String, value: String): String =
        sha256("$salt\u0000$value")

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
