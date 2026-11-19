-- Sign in with Apple and Google only; there is no password login any more.
--
--   * user_identities: the provider accounts (provider + its stable `sub`) an account signs in with.
--     An identity whose verified e-mail matches an existing account is linked to it, so accounts
--     created with e-mail and password keep their data.
--   * users.nickname_confirmed: an account created by social sign-in starts with a generated
--     nickname and is asked to pick one; every account that exists already has picked it.

CREATE TABLE user_identities (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    provider   VARCHAR(20)  NOT NULL CONSTRAINT ck_user_identities_provider CHECK (provider IN ('apple', 'google')),
    subject    VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NULL,
    created_at TIMESTAMP    NOT NULL,
    CONSTRAINT uq_user_identities_provider_subject UNIQUE (provider, subject),
    CONSTRAINT fk_user_identities_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX user_identities_user_id ON user_identities (user_id);

ALTER TABLE users ADD COLUMN nickname_confirmed BOOLEAN DEFAULT TRUE NOT NULL;
