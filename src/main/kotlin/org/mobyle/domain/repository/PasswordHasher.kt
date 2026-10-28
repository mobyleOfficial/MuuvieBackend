package org.mobyle.domain.repository

/** One-way hash for passwords that are not account passwords (B7: maratona passwords). */
interface PasswordHasher {
    fun hash(password: String): String

    fun verify(password: String, hash: String): Boolean
}
