# Alohub backend

Xem [triển khai Cuộc gọi](docs/PHONE_CALLING.md) để bổ sung số điện thoại và nối FreeSWITCH với OpenVox hoặc Carrier SIP Trunk.

Spring Boot 4.1.2 / Java 25 / PostgreSQL. First increment: registration, login,
session revocation, FusionPBX extension provisioning, a searchable user directory
and persistent web chat and SIP message history.
No database migrations;
Hibernate uses `ddl-auto=update` for development.

## Run locally

Use JDK 25 (`JAVA_HOME` must point to the JDK). With your existing PostgreSQL,
set the following environment variables in PowerShell as needed:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:25432/voip_db'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = '<your-database-password>'
$env:CORS_ALLOWED_ORIGINS = 'http://localhost:5173,http://127.0.0.1:5173'
./mvnw.cmd spring-boot:run
```

The database must exist; Hibernate creates/updates application tables (`users`,
`auth_sessions`, `sip_accounts`, `message_history`). The default datasource matches the included development Compose
file. If you use that file, start Docker Desktop then run
`docker compose up -d postgres`. Compose ports are bound to localhost. Redis is
optional and unused in this increment. Automatic Compose startup is disabled;
set `DOCKER_COMPOSE_ENABLED=true` to opt in.

Optional settings: `SERVER_PORT` (8080), `AUTH_TOKEN_TTL` (8h), `JWT_SECRET`
(at least 32 UTF-8 bytes). Without `JWT_SECRET`, the process generates a random
development signing key and existing tokens stop working after restart. Supply a
random persistent secret through the environment to retain sessions across
restarts. Neither keys nor bearer tokens are logged. No refresh-token flow yet.

Auth uses an explicit bearer header, not cookies. The frontend should keep the
token in memory and require login again when it expires. Logout revokes the
current session immediately; other device sessions remain active. Expired/revoked
session rows are retained for now; scheduled retention cleanup is future work.

## Message history

Direct messages between web accounts are saved for both participants in one
PostgreSQL transaction. The recipient sees messages after logging in or
reconnecting. SIP message history is also stored per authenticated account.
Content is plaintext in this increment. See [the history contract and
verification steps](docs/MESSAGE_HISTORY.md). The Postman collection keeps one
editable request per endpoint, including save, list conversations and list messages.

## FusionPBX connection

Spring imports `.env` from the backend directory (also when launched from the
parent workspace). A private, gitignored `.env` was created with the supplied
domain/IP and a random credential encryption key. Fill **FUSIONPBX_API_TOKEN**
with the complete `key_uuid:token_secret` value, then restart the backend. For a
fresh clone, copy `.env.example` to `.env` and generate a stable encryption key:

```powershell
[Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

Save that generated value as `FUSIONPBX_CREDENTIALS_KEY` in `.env`. It protects
stored SIP passwords with AES-256-GCM, binding ciphertext to its account UUID.
Changing/removing the key makes existing passwords unreadable. Keep it outside Git.

| Variable | Purpose / default |
| --- | --- |
| `FUSIONPBX_API_URL` | `https://10.155.199.136/app/rest_api/rest.php` |
| `FUSIONPBX_API_TOKEN` | Required `key_uuid:token_secret`; sent using Basic Auth |
| `FUSIONPBX_DOMAIN_NAME` | `10.155.199.136`; resolved through `domain-details` |
| `FUSIONPBX_SERVER_IP` | `10.155.199.136` |
| `FUSIONPBX_WSS_URL` | `wss://10.155.199.136:7443`; verify against the actual SIP profile |
| `FUSIONPBX_CREDENTIALS_KEY` | Required base64 of 32 random bytes, stable across restarts |
| `FUSIONPBX_EXTENSION_START` / `FUSIONPBX_EXTENSION_END` | Automatic allocation range, `1000` through `9999` |
| `FUSIONPBX_CONNECT_TIMEOUT` / `FUSIONPBX_REQUEST_TIMEOUT` | `2s` / `5s` |
| `FUSIONPBX_VERIFY_SSL` | `true`; set `false` to bypass certificate and hostname validation for this REST client only |

By default, the Java trust store must trust the API HTTPS certificate and the
certificate must match the endpoint hostname/IP. For the current self-signed
development PBX, the private `.env` uses `FUSIONPBX_VERIFY_SSL=false`. Restart the
backend after changing this flag. This bypasses certificate and hostname checks
only on the FusionPBX REST client's private SSLContext; other Java HTTPS clients
and browser WSS verification retain their own settings. HTTPS is still used and
redirects remain disabled.

With verification disabled, the backend cannot authenticate the server and its
REST token could be intercepted. Use this only for the trusted development
environment; set the flag back to `true` with a trusted, matching certificate for
deployment. Use the API URL displayed by your FusionPBX installation if it
differs from this default.

Registration reserves the app user and a free numeric extension in one local
transaction, then calls `extension-create`, encrypts the returned SIP password
and saves the returned extension UUID. Existing PBX numbers and all local
reservations are skipped. Unique database constraints protect concurrent backend
instances. A legacy app user without a mapping is provisioned after a successful
password check on login.

Me and List Users call `domain-details` and `extension-list` and match both the
stored UUID and extension number. A PBX-only extension is not automatically an
app account/chat member. Directory results include a safe `sip` summary; the
administrative token and SIP password are excluded. `extensionPresent` verifies
existence, not SIP registration or application online presence. Dependency
failures return an error rather than a local-only directory result.

`GET /api/v1/sip/account` supplies only the authenticated caller's extension,
domain, server IP, WSS URL and decrypted SIP password for the browser SIP client.
It verifies the extension still exists and sends no-store headers. App logout
revokes the app session; it does not delete the shared PBX extension.

### Incomplete provisioning

The remote write and local database cannot share a transaction. A reservation
survives an unsuccessful create: explicit rejection is `FAILED`; timeouts,
invalid responses, network errors and uncertain failures are `UNKNOWN`.
No blind create retry or adoption of an externally owned extension occurs.
Login returns `503 PROVISIONING_PENDING` until an operator reconciles the account.
If the database becomes unavailable when recording the outcome, the durable
reservation may still be `PROVISIONING` and likewise needs reconciliation.

Inspect the user and its reserved domain/number in `sip_accounts`, then verify
the actual extension in the FusionPBX UI. Do not delete/adopt an existing PBX
extension based only on its number. The inspected REST API has no idempotency
key, extension-delete action or working password recovery action; recovery and
credential rotation require an operator. Automatic reconciliation is not yet
implemented.

### Upstream source finding

The integration follows [AccelerateNetworks/fusionpbx-rest-api at f32b7a9](https://github.com/AccelerateNetworks/fusionpbx-rest-api/tree/f32b7a9578e29484671b0e4ab956f7f9300ea1cc).
The inspected [rest.php](https://github.com/AccelerateNetworks/fusionpbx-rest-api/blob/f32b7a9578e29484671b0e4ab956f7f9300ea1cc/rest.php)
reverses the `password_verify` parameters and rejects on true instead of false.
Check the deployed revision. If affected, its rejection condition should use
`!password_verify($_SERVER['PHP_AUTH_PW'], $secret)`. This task does not patch the
remote server. Local mocks enforce the intended authentication contract.

## API and verification

- Interface contract: [OpenAPI](docs/openapi.yaml).
- Request/response semantics and examples: [API guide](docs/API.md).
- `./mvnw.cmd test`: integration tests through Spring Security, controllers and
  actual JPA persistence using an isolated H2 database in PostgreSQL mode. This
  verifies application behavior, not PostgreSQL-specific behavior.
- `./mvnw.cmd package`: tests and executable JAR.
- The Postman collection contains one request per endpoint, including Presence
  Heartbeat. Select **Alohub Local** from
  `postman/environments/local.environment.yaml`. Edit the register/login JSON
  bodies and directory query parameters directly for each case you want to try.
- The environment defines `base_url` (default `http://localhost:8080`) and
  `access_token` (initially empty). Successful login stores the bearer token in
  the selected environment; successful logout clears it. No scripts generate
  account data or assert a particular test outcome. Requests that require auth
  inherit the collection bearer configuration.
- To validate the files or run the collection with the selected environment:

```powershell
postman collection lint postman/collections/auth-users
postman environment lint postman/environments/local.environment.yaml
postman collection run postman/collections/auth-users -e postman/environments/local.environment.yaml --no-report-events
```

Run individual requests for manual testing; running the whole collection executes
Register through Logout using your current body values. A second registration
with the same account will return 409. CLI token changes are available within the
run, without writing the token into the committed environment file. Backend
regression checks remain in the Java integration tests. The collection does not
generate or overwrite the OpenAPI source.

For integration tests on real PostgreSQL, create a dedicated empty `voip_test`
database (`docker compose exec -T postgres createdb -U root voip_test`), then run:

```powershell
./mvnw.cmd '-Dspring.datasource.url=jdbc:postgresql://localhost:25432/voip_test' '-Dspring.datasource.driver-class-name=org.postgresql.Driver' '-Dspring.datasource.username=root' '-Dspring.datasource.password=voip@123' test
```

The test profile uses `create-drop`; target the test database only. Application
startup continues to use `update`. Tests start a real HTTP stub of the inspected
FusionPBX contract and never call the configured real PBX.

Verified on 2026-11-17: 34 integration tests passed on H2 and real PostgreSQL;
executable JAR packaging passed. The actual JAR with PostgreSQL also passed the
local Postman simulation checks for provisioning, directory integration, own
credentials, dependency failures, timeout-after-write and session revocation.
These are local contract-based checks, not verification against the deployed PBX;
its real API token, certificate, server revision and SIP registration remain to
be verified.

The later SSL configuration change passed all 37 tests on H2 and repackaged the
JAR: the original 34 integration cases plus three real HTTPS tests. A test-only
self-signed certificate deliberately mismatches the loopback hostname. Tests
cover rejection by default, successful opt-out, unchanged JVM SSL defaults and
continued hostname verification by an ordinary HTTPS client.

## Run the dependency simulation

The committed mock models `domain-details`, `extension-list` and
`extension-create` from the inspected PHP source; the backend itself runs for
real. No authenticated owner's mock search was possible because this CLI is
signed out. The existing workspace binding is preserved and nothing is published.

Create an isolated empty database for this exercise and package the app first:

```powershell
docker compose exec -T postgres createdb -U root voip_fusion_sim
./mvnw.cmd package
./scripts/run-fusionpbx-verification.ps1
```

The script requires `JAVA_HOME` for JDK 25 and the Postman CLI. It starts the
simulation on port 14010 and backend on port 18082, overrides the PBX connection
with local fixture values, executes `scripts/verify-fusionpbx.mjs`, then stops
both processes and restores its environment variables. Test accounts and their
provisioning outcomes remain in the dedicated `voip_fusion_sim` database.
Logs are under `target/fusion-*` and are not committed. For another database,
pass `-DatabaseUrl`; this exercise writes accounts, so use a dedicated test DB.

Simulation wiring: `postman/simulations/fusionpbx-dev.sim.yaml`. Evidence and
upstream assumptions: `postman/simulations/fusionpbx-dev.context.yaml`. Java's
HTTP client does not consume `HTTP_PROXY` automatically, so the simulation uses
one-port routing and an explicit local API URL. Mock control routes are test-only.

## Scope

Own profile contains email. The directory excludes the caller and exposes the
ID, username, display name, account status, web presence and a safe SIP summary.
New accounts default to USER/ACTIVE. Web presence follows session heartbeats and
uses the nullable `auth_sessions.presence_until` column managed by Hibernate.
See the [API guide](docs/API.md#online-presence) for the heartbeat contract.
