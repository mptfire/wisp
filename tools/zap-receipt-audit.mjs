// Sample live NIP-57 zap receipts from public relays and measure how many
// pass the validation chain enforced by mptfire's PR #678:
//   parseable description -> kind 9734 -> signature valid -> p-tag match
//   -> e-tag consistency -> invoice has description hash -> hash == sha256(description)
// Run: node sample.mjs [seconds]
import { schnorr } from '@noble/curves/secp256k1';
import { sha256 } from '@noble/hashes/sha256';
import { bytesToHex, hexToBytes } from '@noble/hashes/utils';

const DURATION_MS = (Number(process.argv[2]) || 90) * 1000;
const RELAYS = [
  'wss://relay.damus.io',
  'wss://nos.lol',
  'wss://relay.primal.net',
  'wss://nostr.wine',
  'wss://relay.nostr.band',
];
const CHARSET = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l';

// ── bolt11 minimal decode: hrp amount + TLV type-23 walk (checksum unchecked) ──
function charsetIndex(c) { return CHARSET.indexOf(c); }
function decodeInvoice(invoice) {
  const lower = invoice.toLowerCase().replace(/^lightning:/, '');
  const pos = lower.lastIndexOf('1');
  if (pos < 1) return null;
  const data = [];
  for (let i = pos + 1; i < lower.length; i++) {
    const idx = charsetIndex(lower[i]);
    if (idx < 0) return null;
    data.push(idx);
  }
  if (data.length < 7 + 104 + 6) return null;
  const dataLen = data.length - 6;
  const sigStart = dataLen - 104;
  let descriptionHash = null;
  let offset = 7;
  while (offset < sigStart) {
    if (offset + 3 > sigStart) break;
    const tag = data[offset];
    const len = (data[offset + 1] << 5) | data[offset + 2];
    offset += 3;
    if (offset + len > sigStart) break;
    if (tag === 23 && len === 52) {
      // 5-bit -> 8-bit
      let acc = 0, bits = 0;
      const out = [];
      for (let i = 0; i < len; i++) {
        acc = (acc << 5) | data[offset + i];
        bits += 5;
        while (bits >= 8) { bits -= 8; out.push((acc >> bits) & 0xff); }
      }
      descriptionHash = bytesToHex(new Uint8Array(out));
    }
    offset += len;
  }
  return { descriptionHash };
}

// ── NIP-01 canonical id serialization (mirrors Event.kt computeId) ──
function escapeJson(s) {
  let out = '';
  for (const c of s) {
    if (c === '\\') out += '\\\\';
    else if (c === '"') out += '\\"';
    else if (c === '\b') out += '\\b';
    else if (c === '\f') out += '\\f';
    else if (c === '\n') out += '\\n';
    else if (c === '\r') out += '\\r';
    else if (c === '\t') out += '\\t';
    else if (c.codePointAt(0) < 0x20) out += '\\u00' + c.codePointAt(0).toString(16).padStart(2, '0');
    else out += c;
  }
  return out;
}
function serialize(event) {
  return JSON.stringify([0, event.pubkey, event.created_at, event.kind, event.tags, event.content], (k, v) => v);
}
// JSON.stringify escapes differ from NIP-01 for some inputs; build manually.
function canonicalId(event) {
  const tags = event.tags.map(t => '[' + t.map(x => '"' + escapeJson(String(x)) + '"').join(',') + ']').join(',');
  return bytesToHex(sha256(new TextEncoder().encode(
    `[0,"${event.pubkey}",${event.created_at},${event.kind},[${tags}],"${escapeJson(event.content)}"]`
  )));
}

const tag = (e, n) => e.tags?.find(t => Array.isArray(t) && t.length >= 2 && t[0] === n)?.[1];

const stats = {
  receipts: 0, dup: 0,
  descMissing: 0, descUnparseable: 0, notZapRequest: 0,
  idMismatch: 0, sigInvalid: 0,
  pMissing: 0, pMismatch: 0, eMismatch: 0,
  bolt11Missing: 0, bolt11Undecodable: 0, hashMissing: 0, hashMismatch: 0,
  valid: 0,
};
const seen = new Set();
const failures = [];

function evaluate(receipt) {
  stats.receipts++;
  const id = receipt.id;
  if (seen.has(id)) { stats.dup++; return; }
  seen.add(id);

  const description = tag(receipt, 'description');
  if (!description) { stats.descMissing++; return record('descMissing', id); }
  let request;
  try { request = JSON.parse(description); } catch { stats.descUnparseable++; return record('descUnparseable', id); }
  if (request.kind !== 9734) { stats.notZapRequest++; return record('notZapRequest', id); }

  let cid
  try { cid = canonicalId(request) } catch (e) { stats.evalErrors = (stats.evalErrors||0)+1; if (!global.firstEvalError) { global.firstEvalError = String(e && e.stack || e) }; return record('evalError', id) }
  if (cid !== request.id) { stats.idMismatch++; return record('idMismatch', id) }
  let sigBytes, idBytes, pubBytes, verifyResult
  try {
    sigBytes = hexToBytes(String(request.sig ?? ''))
    idBytes = hexToBytes(String(request.id ?? ''))
    pubBytes = hexToBytes(String(request.pubkey ?? ''))
  } catch (e) {
    stats.hexErrors = (stats.hexErrors || 0) + 1
    if (!global.firstEvalError) global.firstEvalError = 'hex: ' + String(e && e.message || e) + ' sig=' + typeof request.sig
    return record('hexError', id)
  }
  try {
    verifyResult = schnorr.verify(sigBytes, idBytes, pubBytes)
  } catch (e) {
    stats.verifyThrows = (stats.verifyThrows || 0) + 1
    if (!global.firstEvalError) global.firstEvalError = 'verify-sync: ' + String(e && e.message || e)
    return record('verifyThrow', id)
  }
  Promise.resolve(schnorr.verify(sigBytes, idBytes, pubBytes))
    .then(ok => {
      if (!ok) { stats.sigInvalid++; return record('sigInvalid', id); }
      void ok;
      const reqP = tag(request, 'p'), recP = tag(receipt, 'p');
      if (!recP) { stats.pMissing++; return record('pMissing', id); }
      if (!reqP || reqP.toLowerCase() !== recP.toLowerCase()) { stats.pMismatch++; return record('pMismatch', id); }
      for (const [nm, side] of [['p', receipt], ['e', receipt], ['description', receipt], ['bolt11', receipt]]) {
        const cnt = (side.tags || []).filter(t => Array.isArray(t) && t.length >= 2 && t[0] === nm).length;
        if (cnt > 1) { stats['dup' + nm + 'InReceipt'] = (stats['dup' + nm + 'InReceipt'] || 0) + 1; return record('dup' + nm + 'Receipt', id); }
      }
      for (const nm of ['p', 'e', 'amount']) {
        const cnt = (request.tags || []).filter(t => Array.isArray(t) && t.length >= 2 && t[0] === nm).length;
        if (cnt > 1) { stats['dup' + nm + 'InRequest'] = (stats['dup' + nm + 'InRequest'] || 0) + 1; return record('dup' + nm + 'Request', id); }
      }
      const reqE = tag(request, 'e'), recE = tag(receipt, 'e');
      if ((reqE == null) !== (recE == null)) {
        const dir = reqE == null ? 'retarget' : 'omitted';
        stats['ePresence_' + dir] = (stats['ePresence_' + dir] || 0) + 1;
        if (dir === 'retarget') return record('ePresenceMismatch', id);
      }
      if (reqE && reqE.toLowerCase() !== recE.toLowerCase()) { stats.eMismatch++; return record('eMismatch', id); }
      const recPMirror = tag(receipt, 'P');
      if (recPMirror && recPMirror.toLowerCase() !== request.pubkey.toLowerCase()) { stats.pMirrorMismatch++; return record('pMirrorMismatch', id); }
      if (!tag(request, 'amount')) stats.requestWithoutAmount = (stats.requestWithoutAmount || 0) + 1;
      const bolt11 = tag(receipt, 'bolt11');
      if (!bolt11) { stats.bolt11Missing++; return record('bolt11Missing', id); }
      const inv = decodeInvoice(bolt11);
      if (!inv) { stats.bolt11Undecodable++; return record('bolt11Undecodable', id); }
      if (!inv.descriptionHash) { stats.hashMissing++; return record('hashMissing', id); }
      const expected = bytesToHex(sha256(new TextEncoder().encode(description)));
      if (inv.descriptionHash.toLowerCase() !== expected.toLowerCase()) { stats.hashMismatch++; return record('hashMismatch', id); }
      stats.valid++;
    })
    .then(() => { if (!stats.receipts) {} }, (e) => { stats.evalErrors = (stats.evalErrors||0)+1; if (!global.firstEvalError) { global.firstEvalError = String(e && e.stack || e) }; record('evalError', id); });
}

function record(reason, id) { failures.push([reason, id.slice(0, 8)]); }

let socketsOpen = 0;
for (const url of RELAYS) {
  try {
    const ws = new WebSocket(url);
    ws.onopen = () => {
      socketsOpen++;
      ws.send(JSON.stringify(['REQ', 'zapaudit-' + Date.now(), { kinds: [9735], limit: 400 }]));
    };
    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data);
        if (msg[0] === 'EVENT' && msg[2]?.kind === 9735) evaluate(msg[2]);
        if (msg[0] === 'EOSE') ws.close();
      } catch {}
    };
    ws.onerror = () => {};
  } catch { console.error('skip relay', url); }
}

setTimeout(() => {
  console.log(JSON.stringify(stats, null, 2));
  const byReason = {};
  for (const [r] of failures) byReason[r] = (byReason[r] || 0) + 1;
  console.log('failure breakdown:', JSON.stringify(byReason));
  if (global.firstEvalError) console.log('first eval error:', global.firstEvalError)
  console.log('hexErrors:', stats.hexErrors || 0, 'verifyThrows:', stats.verifyThrows || 0)
  const eligible = stats.receipts - stats.dup - stats.descMissing - stats.descUnparseable - stats.notZapRequest;
  console.log(`eligible receipts: ${eligible}, fully valid: ${stats.valid} (${eligible ? (100 * stats.valid / eligible).toFixed(1) : 0}%)`);
  process.exit(0);
}, DURATION_MS);
