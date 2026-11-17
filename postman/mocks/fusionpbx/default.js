// Contract: AccelerateNetworks/fusionpbx-rest-api at f32b7a9578e29484671b0e4ab956f7f9300ea1cc.
// Test control routes below are local-only and are not FusionPBX API operations.
const http = require('node:http');
const { randomUUID } = require('node:crypto');
const DOMAIN = '11111111-1111-1111-1111-111111111111';
const TOKEN = '22222222-2222-2222-2222-222222222222:simulation-only-secret';
const STATE = 'fusionpbx.records';
const CONTROL = 'fusionpbx.control';
const STATS = 'fusionpbx.stats';
const seed = () => ({ '1000': { domain_uuid: DOMAIN, extension_uuid: '33333333-3333-3333-3333-333333333333', extension: '1000' } });

function send(res, status, body) {
  if (res.destroyed) return;
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

function readBody(req) {
  if (req.body && typeof req.body === 'object') return Promise.resolve(req.body);
  return new Promise(resolve => {
    let raw = '';
    req.on('data', chunk => { raw += chunk; });
    req.on('end', () => { try { resolve(JSON.parse(raw || '{}')); } catch { resolve({}); } });
  });
}

http.createServer(async (req, res) => {
  const bodyPromise = readBody(req);
  try {
    const body = await bodyPromise;
    const path = req.url.split('?')[0];
    let records = await pm.state.get(STATE) || seed();
    let stats = await pm.state.get(STATS) || {};
    const control = await pm.state.get(CONTROL) || {};
    if (path === '/__control') {
      if (req.method === 'POST') {
        if (body.reset) { records = seed(); stats = {}; await pm.state.set(STATE, records); await pm.state.set(STATS, stats); }
        await pm.state.set(CONTROL, body);
      }
      return send(res, 200, { counts: stats, extensions: Object.keys(records) });
    }
    // @endpoint POST /app/rest_api/rest.php
    if (req.method !== 'POST' || path !== '/app/rest_api/rest.php') return send(res, 501, { error: 'call not modeled' });
    // Enforces the documented Basic Auth intent; upstream's verifier bug is documented separately.
    if (req.headers.authorization !== 'Basic ' + Buffer.from(TOKEN).toString('base64')) return send(res, 401, { error: 'unauthorized' });
    const action = body.action;
    stats[action] = (stats[action] || 0) + 1;
    await pm.state.set(STATS, stats);
    if (control.failAction === action) return send(res, control.status || 503, { error: 'simulated dependency failure' });
    if (action === 'domain-details') {
      if (body.domain_name !== '10.155.199.136') return send(res, 404, { error: 'domain not found' });
      return send(res, 200, { domain_uuid: DOMAIN, domain_name: '10.155.199.136', domain_enabled: true });
    }
    if (body.domain_uuid !== DOMAIN) return send(res, 404, { error: 'domain not found' });
    if (action === 'extension-list') {
      return send(res, 200, Object.values(records).map(row => ({ extension_uuid: row.extension_uuid,
        extension: row.extension, emergency_caller_id_number: row.extension })));
    }
    if (action === 'extension-create') {
      const number = body.extension;
      if (!number) return send(res, 400, { error: 'missing required parameter(s)' });
      if (records[number]) return send(res, 500, { error: 'extension already exists' });
      const created = { domain_uuid: DOMAIN, extension_uuid: randomUUID(), extension: number,
        password: 'simulated-sip-' + randomUUID(), effective_caller_id_name: body.caller_id_name || '' };
      records[number] = created;
      await pm.state.set(STATE, records);
      if (control.timeoutAfterWrite) await new Promise(resolve => setTimeout(resolve, 1200));
      return send(res, 200, control.malformedCreate ? { extension: number } : created);
    }
    return send(res, 501, { error: 'action not modeled' });
  } catch {
    send(res, 500, { error: 'mock handler failure' });
  }
}).listen(process.env.PORT || 14010);
