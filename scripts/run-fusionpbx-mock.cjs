// Runs the existing contract handler locally when the Postman dataset daemon is
// unavailable on Windows. State is disposable; never points at a real PBX.
const records = new Map()
global.pm = { state: {
  get: async (key) => structuredClone(records.get(key)),
  set: async (key, value) => records.set(key, structuredClone(value)),
} }
require('../postman/mocks/fusionpbx/default.js')
