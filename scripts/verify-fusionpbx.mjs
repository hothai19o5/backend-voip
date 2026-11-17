import assert from 'node:assert/strict';

// Run the real backend against the local simulation, using an isolated dev database.
const backend = process.argv[2] || 'http://127.0.0.1:18082';
const dependency = process.argv[3] || 'http://127.0.0.1:14010/fusionpbx';
const suffix = Date.now().toString(36);

async function request(base, path, { body, token, method } = {}) {
  const response = await fetch(base + path, {
    method: method || (body ? 'POST' : 'GET'),
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(10000),
  });
  return { status: response.status, body: response.status === 204 ? null : await response.json() };
}
const registerBody = name => ({ username: name, email: `${name}@example.com`, password: 'simulation-password', displayName: name });
const control = body => request(dependency, '/__control', { body });
const owner = `sim_owner_${suffix}`;
const peer = `sim_peer_${suffix}`;

await control({ reset: true });
const registered = await request(backend, '/api/v1/auth/register', { body: registerBody(owner) });
assert.equal(registered.status, 201);
assert.equal(registered.body.sip.provisioningStatus, 'READY');
assert.notEqual(registered.body.sip.extension, '1000');
assert.equal((await request(backend, '/api/v1/auth/register', { body: registerBody(peer) })).status, 201);
const login = await request(backend, '/api/v1/auth/login', { body: { username: owner, password: 'simulation-password' } });
assert.equal(login.status, 200);
const token = login.body.accessToken;
const directory = await request(backend, `/api/v1/users?q=${peer}`, { token });
assert.equal(directory.status, 200);
assert.equal(directory.body.totalElements, 1);
assert.equal(directory.body.content[0].sip.extensionPresent, true);
assert.equal(directory.body.content[0].sip.password, undefined);
const account = await request(backend, '/api/v1/sip/account', { token });
assert.equal(account.status, 200);
assert.ok(account.body.password.startsWith('simulated-sip-'));
assert.equal(account.body.apiToken, undefined);
console.log('PASS registration, login, PBX-enriched directory and own SIP credentials');

await control({ failAction: 'extension-list', status: 503 });
const unavailable = await request(backend, '/api/v1/users', { token });
assert.equal(unavailable.status, 502);
assert.equal(unavailable.body.code, 'TELEPHONY_REJECTED');
await control({ failAction: 'domain-details', status: 401 });
const rejected = await request(backend, '/api/v1/auth/register', { body: registerBody(`sim_auth_${suffix}`) });
assert.equal(rejected.status, 502);
assert.equal(rejected.body.code, 'TELEPHONY_AUTH_FAILED');
console.log('PASS dependency failures surface without returning a local-only directory');

await control({ timeoutAfterWrite: true });
const ambiguousName = `sim_timeout_${suffix}`;
const before = (await request(dependency, '/__control')).body.counts['extension-create'];
const timedOut = await request(backend, '/api/v1/auth/register', { body: registerBody(ambiguousName) });
assert.equal(timedOut.status, 504);
assert.equal(timedOut.body.code, 'TELEPHONY_TIMEOUT');
assert.equal((await request(backend, '/api/v1/auth/register', { body: registerBody(ambiguousName) })).status, 409);
const pending = await request(backend, '/api/v1/auth/login', { body: { username: ambiguousName, password: 'simulation-password' } });
assert.equal(pending.status, 503);
assert.equal(pending.body.code, 'PROVISIONING_PENDING');
const state = (await request(dependency, '/__control')).body;
assert.equal(state.counts['extension-create'], before + 1);
console.log('PASS timeout after remote write retains pending setup without another create');
await control({});
assert.equal((await request(backend, '/api/v1/auth/logout', { token, method: 'POST' })).status, 204);
assert.equal((await request(backend, '/api/v1/users/me', { token })).status, 401);
console.log('PASS logout revokes app session');
