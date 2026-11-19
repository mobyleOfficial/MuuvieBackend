package org.mobyle.domain.usecase.auth

import org.mobyle.domain.repository.AuthRepository

/** Sets the nickname of an account; a new account is asked for it after its first sign-in. */
class ChooseNickname(private val authRepository: AuthRepository) {
    suspend operator fun invoke(userId: String, nickname: String): String =
        authRepository.chooseNickname(userId, nickname)
}
