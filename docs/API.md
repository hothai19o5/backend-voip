# Auth and Users API

Base URL: `http://localhost:8080`. JSON request/response bodies. OpenAPI source:
`docs/openapi.yaml`. This document explains the first backend increment.

## Authentication contract

- Register creates an `ACTIVE` account with role `USER`, automatically allocates
  a numeric extension and calls FusionPBX `extension-create`. It returns 201 only
  after the mapping and encrypted SIP password are persisted. It does not log in.
  Client-supplied role/status/extension fields are rejected.
- Usernames are 3–32 ASCII letters/digits/underscore/dot/hyphen, stored lowercase.
  Email is also stored lowercase. Both are trimmed and unique, ignoring case.
- Passwords are 8–128 characters, preserved exactly, salted and hashed with PBKDF2.
- Login accepts `username` and `password`. All incorrect credentials and blocked
  accounts return the same `401 INVALID_CREDENTIALS` response.
- Login returns `{accessToken, tokenType: "Bearer", expiresAt, user}`. Send
  `Authorization: Bearer <accessToken>` for protected endpoints. No cookies are
  used for authentication. Store the token in frontend memory, never a URL.
- Tokens expire after 8 hours by default. No refresh endpoint in this increment.
  Every request checks the persisted session and account status. Logout revokes
  only the supplied session immediately; other devices remain signed in.
- Existing app accounts without a mapping are provisioned after a successful
  password check at login. Auth/logout remain app sessions; logging out does not
  delete a FusionPBX extension. Account status, provisioning and SIP registration
  are independent; the REST extension list does not report online registration.

| Method | Path | Success | Authentication |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/register` | 201, own user profile | Public |
| POST | `/api/v1/auth/login` | 200, token and own user profile | Public |
| POST | `/api/v1/auth/logout` | 204, empty body | Bearer |
| GET | `/api/v1/users/me` | 200, own user profile | Bearer |
| GET | `/api/v1/users?page=0&size=20&q=alice` | 200, page | Bearer |
| GET | `/api/v1/sip/account` | 200, own SIP connection credentials | Bearer |
| PUT | `/api/v1/message-history/messages/{id}` | 200, idempotently saved message | Bearer |
| PUT | `/api/v1/message-history/send/{id}` | 200, direct message saved for both accounts | Bearer |
| GET | `/api/v1/message-history/conversations?size=30` | 200, cursor page | Bearer |
| GET | `/api/v1/message-history/messages?peer=sip:1002@pbx.test&size=50` | 200, cursor page | Bearer |
| GET | `/api/v1/message-history/updates?after=0` | 200, ascending page of new messages | Bearer |
| GET | `/actuator/health` | 200 when healthy | Public, no component details |

Offline web chat, SIP message history, pagination, and retry behavior
are described in [MESSAGE_HISTORY.md](MESSAGE_HISTORY.md). History is stored as
plaintext in this increment. Logout revokes the session without deleting history.

Register body:

```json
{"username":"alice","email":"alice@example.com","password":"example-password","displayName":"Alice"}
```

Login body:

```json
{"username":"alice","password":"example-password"}
```

Own profile: `id` (UUID), `username`, `email`, `displayName`, `role`, `status`,
`createdAt` (UTC instant), `sip`. Public directory entries contain `id`, `username`,
`displayName`, `status`, `sip`; never email, password hashes, session IDs or SIP
secrets. `sip` contains `extension`, `domain`, `provisioningStatus` and
`extensionPresent` (nullable when not freshly checked). Me and directory resolve
the configured domain and call `extension-list` on every request, matching both
extension UUID and number. A missing extension is reported as absent; it is not
silently recreated. PBX-only extensions do not become app/chat identities.

Directory excludes the caller and includes other accounts regardless of session
state, including blocked accounts (marked `BLOCKED`). `q` is an optional literal,
case-insensitive substring on username/display name (max 100 characters). `%` and
`_` are treated literally. `page` is zero-based, >= 0; `size` is 1–100.
Ordering is stable by username then UUID. Response:

```json
{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0,"hasNext":false}
```

Errors use `application/problem+json` with `type`, `title`, `status`, `detail`,
`instance` and `code`; invalid fields also have an `errors` object.

| Status | Code | Meaning |
| --- | --- | --- |
| 400 | VALIDATION_ERROR / INVALID_REQUEST | Invalid fields, JSON or pagination |
| 401 | INVALID_CREDENTIALS | Login rejected |
| 401 | UNAUTHORIZED | Missing, invalid, expired, revoked token or blocked account |
| 403 | FORBIDDEN | Authenticated but access denied |
| 409 | USERNAME_TAKEN / EMAIL_TAKEN / ACCOUNT_CONFLICT | Unique account constraint |
| 409 | EXTENSION_CONFLICT | Extension was claimed externally while provisioning |
| 502 | TELEPHONY_AUTH_FAILED / TELEPHONY_INVALID_RESPONSE / TELEPHONY_REJECTED | Upstream failure; no raw upstream data is returned |
| 503 | TELEPHONY_NOT_CONFIGURED / TELEPHONY_UNAVAILABLE / PROVISIONING_PENDING | Missing configuration, dependency unavailable or setup needs reconciliation |
| 504 | TELEPHONY_TIMEOUT | Upstream request timed out |

`GET /api/v1/sip/account` returns only the caller's `extension`, `domain`,
`serverIp`, `wssUrl` and `password`, after verifying the mapped extension still
exists. The administrative REST token is never returned. The endpoint uses the
same bearer authentication and no-store headers. Keep these SIP credentials in
memory; they are not part of ordinary profile/directory responses.

Provisioning reserves the local user/extension in a committed transaction before
the remote write. On an ambiguous create failure (timeout, network error, invalid
response or unclassified upstream error), the reservation is kept as `UNKNOWN`;
the backend does not retry the create or attach an arbitrary matching extension.
A repeated registration conflicts with that reserved username. The upstream API
has no idempotency key, delete action or safe password recovery action, so an
operator must reconcile ambiguous outcomes. Definitive rejection is `FAILED`.
No account/session is reported successfully provisioned in either case.

Validation never echoes password values. Auth and profile responses use
`Cache-Control: no-store`. CORS allows configured frontend origins only.
