package org.mobyle.data.service

import at.favre.lib.crypto.bcrypt.BCrypt
import org.mobyle.domain.repository.PasswordHasher

/** BCrypt with a lower cost than accounts: a maratona password only gates joining it. */
class BcryptPasswordHasher(private val cost: Int = 10) : PasswordHasher {
    override fun hash(password: String): String = BCrypt.withDefaults().hashToString(cost, password.toCharArray())

    override fun verify(password: String, hash: String): Boolean = BCrypt.verifyer().verify(password.toCharArray(), hash).verified
}
