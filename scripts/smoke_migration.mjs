// Teste do StartupMigration: ele só pode mexer em projetos narrativos. Num projeto de outro contexto nada muda,
// mesmo quando o projeto tem tipos com os nomes antigos do motor (Gancho, SOBRE) e uma versão de schema antiga.
// Reinicia o container do app (a migração roda na subida), por isso use SÓ numa stack de teste.
// Uso: node scripts/smoke_migration.mjs <baseUrl> --neo4j <url http do neo4j> --container <container do app> [--neo4j-auth neo4j:senha] [--keep]
import { execFileSync } from 'node:child_process';

const arg = (name, def) => (process.argv.includes(name) ? process.argv[process.argv.indexOf(name) + 1] : def);
const BASE = process.argv.find((a) => a.startsWith('http') && process.argv[process.argv.indexOf(a) - 1] !== '--neo4j') ?? 'http://127.0.0.1:38180';
const NEO4J = arg('--neo4j');
const CONTAINER = arg('--container');
const AUTH = arg('--neo4j-auth', 'neo4j:harness-dev');
const KEEP = process.argv.includes('--keep');
if (!NEO4J || !CONTAINER) {
  console.error('Faltam --neo4j e --container (este teste reinicia o app: use uma stack de teste).');
  process.exit(2);
}
if (/:(8180|7574)\b/.test(BASE + NEO4J)) {
  console.error('Recusado: parece a instância real (8180/7574). Use uma stack de teste.');
  process.exit(2);
}

let failures = 0;
const ok = (cond, msg) => { if (cond) console.log('  ok  ' + msg); else { failures++; console.log('  FALHOU  ' + msg); } };

async function api(method, path, body, expectStatus) {
  const res = await fetch(BASE + path, { method, headers: { 'content-type': 'application/json', 'x-lang': 'pt' }, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  if (expectStatus !== undefined ? res.status !== expectStatus : !res.ok) throw new Error(`${method} ${path} → ${res.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
}

async function cypher(statement, parameters = {}) {
  const res = await fetch(NEO4J + '/db/neo4j/tx/commit', {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: 'Basic ' + Buffer.from(AUTH).toString('base64') },
    body: JSON.stringify({ statements: [{ statement, parameters }] }),
  });
  const j = await res.json();
  if (j.errors?.length) throw new Error('cypher: ' + JSON.stringify(j.errors));
  const r = j.results[0];
  return r.data.map((d) => Object.fromEntries(r.columns.map((c, i) => [c, d.row[i]])));
}

const keys = (pid) => cypher(`
  MATCH (t) WHERE (t:NodeType OR t:RelType) AND t.projectId = $pid AND t.nameLower IN ['gancho', 'sobre']
  RETURN t.nameLower AS name, t.systemKey AS key ORDER BY name`, { pid });
const schemaVersion = async (pid) => (await cypher('MATCH (p:Project {id:$pid}) RETURN p.harnessSchema AS v', { pid }))[0]?.v ?? null;
const snapshot = async (pid) => {
  const a = await api('GET', `/api/projects/${pid}/export`);
  delete a.exportedAt;
  return JSON.stringify(a);
};
const stamp = Date.now();

console.log('# Preparação');
const corp = await api('POST', '/api/projects', { name: 'Smoke migração corp ' + stamp, context: 'corporate', locale: 'pt' }, 201);
const narr = await api('POST', '/api/projects', { name: 'Smoke migração narrativa ' + stamp, context: 'narrative', profile: 'livro', locale: 'pt' }, 201);
// in the corporate project: types named like the engine's old names (no system key), with a node and an edge
await api('POST', `/api/projects/${corp.id}/changesets`, { title: 'nomes antigos', operations: [
  { op: 'upsert_node_type', definition: { name: 'Gancho', base: 'Concept', attributes: [] } },
  { op: 'upsert_rel_type', definition: { name: 'SOBRE', base: 'REFERENCE', allowed: [] } },
] });
await api('POST', `/api/projects/${corp.id}/changesets`, { title: 'dados', operations: [
  { op: 'create_node', ref: '$g', type: 'Gancho', name: 'Gancho corporativo' },
  { op: 'create_node', ref: '$h', type: 'Gancho', name: 'Outro gancho' },
  { op: 'create_edge', relType: 'SOBRE', from: '$g', to: '$h' },
] });
ok((await keys(corp.id)).length === 2 && (await keys(corp.id)).every((k) => k.key === null), 'corporativo: Gancho e SOBRE sem chave de sistema');
ok((await keys(narr.id)).map((k) => k.key).join() === 'thread,about', 'narrativo: Gancho e SOBRE com as chaves thread/about');

// age both projects: no system keys, old harness schema
await cypher(`MATCH (t) WHERE (t:NodeType OR t:RelType) AND t.projectId = $pid AND t.nameLower IN ['gancho', 'sobre'] REMOVE t.systemKey`, { pid: narr.id });
await cypher('MATCH (p:Project) WHERE p.id IN $ids SET p.harnessSchema = 1', { ids: [corp.id, narr.id] });
const before = await snapshot(corp.id);
const changesetsBefore = (await api('GET', `/api/projects/${corp.id}/changesets`)).length;

console.log('# Reinício do app (roda o StartupMigration)');
execFileSync('docker', ['restart', CONTAINER], { stdio: 'ignore' });
const t0 = Date.now();
let migrated = false;
while (Date.now() - t0 < 120000) {
  await new Promise((r) => setTimeout(r, 2000));
  try {
    if ((await fetch(BASE + '/api/contexts')).ok && (await schemaVersion(narr.id)) >= 2) { migrated = true; break; }
  } catch { /* still starting */ }
}
ok(migrated, `app voltou e migrou o projeto narrativo (${Math.round((Date.now() - t0) / 1000)}s)`);

console.log('# Depois');
ok((await keys(narr.id)).map((k) => k.key).join() === 'thread,about', 'narrativo: chaves de sistema restauradas pelo nome');
ok((await keys(corp.id)).every((k) => k.key === null), 'corporativo: Gancho e SOBRE continuam sem chave de sistema');
ok((await schemaVersion(corp.id)) === 1, 'corporativo: versão de schema não foi tocada');
ok((await api('GET', `/api/projects/${corp.id}/changesets`)).length === changesetsBefore, 'corporativo: nenhum changeset novo (perfil não reaplicado)');
ok((await snapshot(corp.id)) === before, 'corporativo: export idêntico ao de antes do reinício');

if (!KEEP) {
  for (const p of [corp, narr]) await api('DELETE', `/api/projects/${p.id}`).catch(() => undefined);
}
console.log(failures ? `\n${failures} FALHA(S)` : '\nTUDO OK');
process.exit(failures ? 1 : 0);
