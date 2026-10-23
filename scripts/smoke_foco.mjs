// Teste do foco num nó (diretos x todos ligados) via REST e MCP.
// Uso: node scripts/smoke_foco.mjs [baseUrl] [--manter]   (--manter deixa o projeto para olhar na interface)
const BASE = process.argv.find((a) => a.startsWith('http')) ?? 'http://127.0.0.1:8180';
const keep = process.argv.includes('--manter');
let failures = 0;
const check = (c, m) => { if (c) console.log('  ok  ' + m); else { failures++; console.log('  FALHOU  ' + m); } };
async function api(method, path, body, expect) {
  const res = await fetch(BASE + path, { method, headers: { 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (expect !== undefined ? res.status !== expect : !res.ok) throw new Error(`${method} ${path} -> ${res.status}: ${text}`);
  return data;
}

const proj = await api('POST', '/api/projects', { context: 'narrative', name: 'Teste foco ' + new Date().toISOString().slice(0, 16), profile: 'livro', locale: 'pt' }, 201);
const pid = proj.id;
// cadeia Ana — Bruno — Carla — Davi; Eva isolada; Ana aparece no Cap 1, Bruno no Cap 2 (capítulos dentro da Obra)
const w = await api('POST', `/api/projects/${pid}/changesets`, { title: 'cena', operations: [
  { op: 'create_node', ref: '$obra', type: 'Obra', name: 'Obra' },
  { op: 'create_node', ref: '$c1', type: 'Capítulo', name: 'Capítulo 1', parent: '$obra' },
  { op: 'create_node', ref: '$c2', type: 'Capítulo', name: 'Capítulo 2', parent: '$obra' },
  ...['Ana', 'Bruno', 'Carla', 'Davi', 'Eva'].map((n) => ({ op: 'create_node', ref: '$' + n.toLowerCase(), type: 'Personagem', name: n })),
  { op: 'create_edge', relType: 'ALIADO_DE', from: '$ana', to: '$bruno' },
  { op: 'create_edge', relType: 'INIMIGO_DE', from: '$bruno', to: '$carla' },
  { op: 'create_edge', relType: 'FAMÍLIA_DE', from: '$carla', to: '$davi' },
  { op: 'create_edge', relType: 'APARECE_EM', from: '$ana', to: '$c1' },
  { op: 'create_edge', relType: 'APARECE_EM', from: '$bruno', to: '$c2' },
] });
const id = w.ids;
const names = (r) => r.nodes.map((n) => n.name).sort().join(',');

const direct = await api('GET', `/api/projects/${pid}/graph/focus?nodeId=${id.$ana}`);
check(names(direct) === 'Ana,Bruno,Capítulo 1', `diretos de Ana: ${names(direct)}`);
check(direct.distance[id.$bruno] === 1 && direct.levels === 1, 'distância 1 nos diretos');

const all = await api('GET', `/api/projects/${pid}/graph/focus?nodeId=${id.$ana}&all=true`);
check(names(all) === 'Ana,Bruno,Capítulo 1,Capítulo 2,Carla,Davi', `todos ligados de Ana (sem seguir estrutura): ${names(all)}`);
check(all.distance[id.$davi] === 3 && all.byLevel['3'] === 1, 'Davi a 3 saltos');
check(!all.nodes.some((n) => n.name === 'Eva' || n.name === 'Obra'), 'Eva (isolada) e Obra (só via estrutura) ficam de fora');

const withStruct = await api('GET', `/api/projects/${pid}/graph/focus?nodeId=${id.$ana}&all=true&includeStructure=true`);
check(withStruct.nodes.some((n) => n.name === 'Obra'), 'seguindo estrutura, a Obra entra');

const only = await api('GET', `/api/projects/${pid}/graph/focus?nodeId=${id.$ana}&all=true&relTypes=ALIADO_DE`);
check(names(only) === 'Ana,Bruno', 'filtro por tipo de relação');

const lonely = await api('GET', `/api/projects/${pid}/graph/focus?nodeId=${id.$eva}&all=true`);
check(lonely.nodes.length === 1 && lonely.levels === 0, 'nó isolado: só ele');

// MCP
async function mcp(sid, method, params, n) {
  const res = await fetch(BASE + '/mcp', { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', ...(sid ? { 'mcp-session-id': sid } : {}) },
    body: JSON.stringify({ jsonrpc: '2.0', id: n, method, params }) });
  const text = await res.text();
  return { sid: res.headers.get('mcp-session-id') ?? sid, msg: JSON.parse(text.split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5)).join('') || text) };
}
const init = await mcp(null, 'initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'foco', version: '1' } }, 1);
await fetch(BASE + '/mcp', { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', 'mcp-session-id': init.sid }, body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) });
const r1 = await mcp(init.sid, 'tools/call', { name: 'graph_focus', arguments: { projectId: pid, nodeId: id.$ana } }, 2);
check(names(JSON.parse(r1.msg.result.content[0].text)) === 'Ana,Bruno,Capítulo 1', 'MCP graph_focus: diretos');
const r2 = await mcp(init.sid, 'tools/call', { name: 'graph_focus', arguments: { projectId: pid, nodeId: id.$ana, allConnected: true } }, 3);
check(JSON.parse(r2.msg.result.content[0].text).distance[id.$davi] === 3, 'MCP graph_focus: todos ligados com distância');

if (keep) console.log(`\nProjeto mantido para olhar na interface: ${proj.name} (${pid})`);
else await api('DELETE', `/api/projects/${pid}?confirm=${encodeURIComponent(proj.name)}`, undefined, 204);
console.log(failures === 0 ? '\nTUDO OK' : `\n${failures} FALHA(S)`);
process.exit(failures === 0 ? 0 : 1);
