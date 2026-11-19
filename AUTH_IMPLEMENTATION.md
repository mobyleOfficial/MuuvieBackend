# Authentication

Accounts sign in with **Apple or Google only**. There is no e-mail and password login. The endpoints are listed in the README (§ API Endpoints › Auth).

## Flow

1. The app runs the native SDK (Sign in with Apple, Google Sign-In) and gets an **ID token**. For Apple, the app generates a raw nonce and passes its SHA-256 (hex) to the SDK.
2. The app sends `POST /api/v1/auth/social` with `{ provider, idToken, nonce? }`.
3. `IdTokenVerifier` (`data/remote/auth/social/SocialTokenVerifier.kt`) checks the token:
   - an RS256 signature with a key from the provider's JWKS. The keys are cached for an hour; an unknown `kid` refetches them at most once a minute;
   - `iss`: `https://accounts.google.com` / `accounts.google.com`, or `https://appleid.apple.com`;
   - `aud`: one of `GOOGLE_CLIENT_IDS` / `APPLE_CLIENT_IDS`;
   - `exp` and `iat`, with 60 s of clock skew;
   - `nonce`: always required for Apple, and checked for Google when the token carries one.
4. `AuthRepositoryImpl.signInWithProvider` resolves the account:
   1. the account linked to `(provider, sub)` in `user_identities`;
   2. otherwise, the account with the same **verified** e-mail. The identity is linked to it, so accounts created with a password before, and the same person on both providers, keep one account;
   3. otherwise, a new account with a generated nickname (`cosmic_ripley_42`), `nickname_confirmed = false` and no `@handle`.
5. The server issues its own access token (HS256, `JWT_SECRET`) and a refresh token. Rotation, reuse detection and the logout blocklist work as before.
6. While `needsNickname` is true, the app asks for a nickname and sends `PUT /api/v1/auth/nickname`.

## Notes

- Apple may give no e-mail, or a private relay address, and sends the user's name only on the first sign-in. The account e-mail is nullable, and the session is looked up by the user id, never by e-mail.
- `users.password_hash` is kept, but nothing reads it any more.
- Deleting an account will have to revoke the Apple token (`POST https://appleid.apple.com/auth/revoke`), which App Review requires. That is not done yet.
