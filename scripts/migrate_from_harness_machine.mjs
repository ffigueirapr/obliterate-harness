// Migration Harness Machine → Obliterate Harness (narrative context).
//
// Reads the Harness Machine graph straight from its Neo4j (same data model: :N nodes, :R edges, :Fact, :Directive,
// :NodeType/:RelType/:ObjectType), builds an obliterate-archive (format version 1, the same shape Obliterate's own export
// writes) and, optionally, imports it into an Obliterate instance and checks that the narrative engine answers the same
// thing on both sides (outline, coverage, hooks, state in time, writing context).
//
// It also builds a "structure library" project: every built-in structure template (Three acts, Hero's journey, Save the
// Cat...) as Template/Beat nodes, in Portuguese with the English texts alongside.
//
// Nothing is written to the Harness Machine. Without --import nothing is written anywhere except the archive file.
//
// Usage:
//   node scripts/migrate_from_harness_machine.mjs                                 # build the archive only (dry)
//   node scripts/migrate_from_harness_machine.mjs --import http://127.0.0.1:8180  # build, import and check
//   node scripts/migrate_from_harness_machine.mjs --check-only <importResult.json> --import http://127.0.0.1:8180
// Options:
//   --projects p_a,p_b   Harness Machine project ids (default: Bronze Age and Harry Potter)
//   --no-library         do not build the structure library project
//   --out <file>         where to write the archive (default: ../../Stories/migracao_obliterate/hm_to_obliterate_<date>.json)
//   --hm-neo4j <url>     Harness Machine Neo4j HTTP endpoint (default http://127.0.0.1:7474, user neo4j / HM_NEO4J_PASSWORD or harness-dev)
//   --hm-api <url>       Harness Machine REST API (default http://127.0.0.1:8080), used for the template library and the checks
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const opt = (name, def) => { const i = args.indexOf(name); return i >= 0 && args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : def; };
const flag = (name) => args.includes(name);

const DEFAULT_PROJECTS = ['p_qdj4dnz0mb', 'p_hzaxfewd90']; // Bronze Age, Harry Potter e a Pedra Filosofal
const PROJECTS = opt('--projects', DEFAULT_PROJECTS.join(',')).split(',').map((s) => s.trim()).filter(Boolean);
const HM_NEO4J = opt('--hm-neo4j', 'http://127.0.0.1:7474');
const HM_API = opt('--hm-api', 'http://127.0.0.1:8080');
const HM_AUTH = 'Basic ' + Buffer.from(`neo4j:${process.env.HM_NEO4J_PASSWORD ?? 'harness-dev'}`).toString('base64');
const OBL = opt('--import', null);
const CHECK_ONLY = opt('--check-only', null);
const WITH_LIBRARY = !flag('--no-library');
const today = new Date().toISOString().slice(0, 10);
// default: <...>/projetos-pessoais/Stories/migracao_obliterate (story data stays out of the platform repos)
const personal = (() => { let d = HERE; while (path.basename(d) !== 'projetos-pessoais' && path.dirname(d) !== d) d = path.dirname(d); return d; })();
const OUT = opt('--out', path.join(personal, 'Stories', 'migracao_obliterate', `hm_to_obliterate_${today}.json`));

// ------------------------------------------------------------------ helpers

async function cypher(statement, parameters = {}) {
  const res = await fetch(`${HM_NEO4J}/db/neo4j/tx/commit`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: HM_AUTH },
    body: JSON.stringify({ statements: [{ statement, parameters, resultDataContents: ['row'] }] }),
  });
  const body = await res.json();
  if (body.errors?.length) throw new Error(`Neo4j: ${body.errors[0].message}\n${statement}`);
  const r = body.results[0];
  return r.data.map((d) => Object.fromEntries(r.columns.map((c, i) => [c, d.row[i]])));
}

async function http(base, method, p, body, lang = 'pt') {
  const res = await fetch(base + p, { method, headers: { 'content-type': 'application/json', 'x-lang': lang }, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${base}${p} → ${res.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
}

const json = (s, def) => { if (s == null || s === '') return def; try { return JSON.parse(s); } catch { return def; } };
const pick = (o, keys) => Object.fromEntries(Object.entries(o).filter(([k]) => !keys.includes(k)));
const clean = (o) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== null && v !== undefined));
const rnd = (prefix) => prefix + '_' + Math.random().toString(36).slice(2, 12).padEnd(10, '0');

// Same lists as ArchiveService: properties named explicitly in the archive; everything else travels in "extra".
const NODE_KEYS = ['id', 'projectId', 'typeId', 'typeName', 'base', 'space', 'name', 'summary', 'description', 'tags', 'criticality', 'attrs', 'attrMeta',
  'attrsText', 'order', 'version', 'createdAt', 'updatedAt', 'createdBy', 'updatedBy'];
const EDGE_KEYS = ['id', 'projectId', 'kind', 'relTypeId', 'name', 'base', 'attrKey', 'description', 'criticality', 'tags', 'fromUnit', 'toUnit', 'version',
  'createdAt', 'updatedAt', 'createdBy'];
const FACT_KEYS = ['id', 'projectId', 'text', 'rigidity', 'criticality', 'originKind', 'originId', 'originKey', 'revealAt', 'version', 'createdAt', 'updatedAt', 'createdBy'];
const DIRECTIVE_KEYS = ['id', 'projectId', 'text', 'when', 'bases', 'types', 'priority', 'enabled', 'version', 'createdAt', 'updatedAt'];
const extra = (o, keys) => { const e = pick(o, keys); return Object.keys(e).length ? e : undefined; };

// ------------------------------------------------------------------ one Harness Machine project → archive entry

async function projectEntry(pid) {
  const [p] = await cypher('MATCH (p:Project {id:$id}) RETURN p', { id: pid });
  if (!p) throw new Error(`Harness Machine project ${pid} not found`);
  const proj = p.p;

  const nodeTypes = (await cypher('MATCH (t:NodeType {projectId:$p}) RETURN t ORDER BY t.nameLower', { p: pid })).map(({ t }) => clean({
    id: t.id, name: t.name, base: t.base, description: t.description, color: t.color, strict: t.strict,
    attributes: json(t.attributes, []), systemKey: t.systemKey,
  }));
  const relTypes = (await cypher('MATCH (t:RelType {projectId:$p}) WHERE t.name <> "CONTAINS" RETURN t ORDER BY t.nameLower', { p: pid })).map(({ t }) => clean({
    id: t.id, name: t.name, base: t.base, description: t.description, symmetric: t.symmetric, inverseName: t.inverseName, temporal: t.temporal,
    allowed: json(t.allowed, []), systemKey: t.systemKey,
  }));
  const objectTypes = (await cypher('MATCH (t:ObjectType {projectId:$p}) RETURN t ORDER BY t.nameLower', { p: pid })).map(({ t }) => clean({
    id: t.id, name: t.name, description: t.description, fields: json(t.fields, []),
  }));

  const refs = {};
  for (const r of await cypher("MATCH (a:N {projectId:$p})-[r:R {kind:'attr'}]->(b:N) RETURN a.id AS a, r.attrKey AS k, b.id AS b ORDER BY r.createdAt, r.id", { p: pid })) {
    ((refs[r.a] ??= {})[r.k] ??= []).push(r.b);
  }
  const nodes = (await cypher('MATCH (n:N {projectId:$p}) RETURN n ORDER BY n.createdAt, n.id', { p: pid })).map(({ n }) => clean({
    id: n.id, typeId: n.typeId, type: n.typeName, base: n.base, space: n.space, name: n.name, summary: n.summary, description: n.description,
    tags: n.tags, criticality: n.criticality, attributes: json(n.attrs, {}), refs: refs[n.id], attributeCriticality: json(n.attrMeta, undefined),
    order: typeof n.order === 'number' ? n.order : undefined, version: n.version, createdAt: n.createdAt, updatedAt: n.updatedAt,
    createdBy: n.createdBy, updatedBy: n.updatedBy, extra: extra(n, NODE_KEYS),
  }));

  const edges = (await cypher('MATCH (a:N {projectId:$p})-[r:R]->(b:N) RETURN a.id AS a, b.id AS b, r ORDER BY r.createdAt, r.id', { p: pid })).map(({ a, b, r }) => clean({
    id: r.id, kind: r.kind ?? 'rel', relTypeId: r.relTypeId, relType: r.name, base: r.base, from: a, to: b, attrKey: r.attrKey,
    description: r.description, criticality: r.criticality, tags: r.tags, fromUnit: r.fromUnit, toUnit: r.toUnit,
    version: r.version, createdAt: r.createdAt, updatedAt: r.updatedAt, createdBy: r.createdBy, extra: extra(r, EDGE_KEYS),
  }));

  const facts = (await cypher('MATCH (f:Fact {projectId:$p}) OPTIONAL MATCH (f)-[:ABOUT]->(n:N) RETURN f, collect(n.id) AS about ORDER BY f.createdAt, f.id', { p: pid }))
    .map(({ f, about }) => clean({
      id: f.id, text: f.text, rigidity: f.rigidity, criticality: f.criticality, originKind: f.originKind, originId: f.originId, originKey: f.originKey,
      about, revealAt: f.revealAt, version: f.version, createdAt: f.createdAt, updatedAt: f.updatedAt, createdBy: f.createdBy, extra: extra(f, FACT_KEYS),
    }));

  const directives = (await cypher('MATCH (d:Directive {projectId:$p}) RETURN d ORDER BY d.createdAt, d.id', { p: pid })).map(({ d }) => clean({
    id: d.id, text: d.text, when: d.when, bases: d.bases, types: d.types, priority: d.priority, enabled: d.enabled,
    version: d.version, createdAt: d.createdAt, updatedAt: d.updatedAt, extra: extra(d, DIRECTIVE_KEYS),
  }));

  return clean({
    id: pid, name: proj.name, description: proj.description, context: 'narrative', locale: proj.locale ?? 'pt', profile: proj.profile,
    createdAt: proj.createdAt,
    settings: clean({ requireReviewForLlm: proj.requireReviewForLlm ?? false, styleSample: proj.styleSample, styleReference: proj.styleReference }),
    schema: { nodeTypes, relTypes, objectTypes },
    nodes, edges, facts, directives,
    counts: { nodeTypes: nodeTypes.length, relTypes: relTypes.length, objectTypes: objectTypes.length, nodes: nodes.length, edges: edges.length,
      facts: facts.length, directives: directives.length },
  });
}

// ------------------------------------------------------------------ structure library project

async function libraryEntry() {
  const list = await http(HM_API, 'GET', '/api/harness/templates', undefined, 'pt');
  const builtin = list.filter((t) => !t.custom);
  const pt = {}, en = {};
  for (const t of builtin) {
    pt[t.id] = await http(HM_API, 'GET', `/api/harness/templates/${t.id}`, undefined, 'pt');
    en[t.id] = await http(HM_API, 'GET', `/api/harness/templates/${t.id}`, undefined, 'en');
  }
  const a = (key, label, dataType, more = {}) => ({ key, label, dataType, required: false, ...more });
  const tTemplate = { id: 'nt_lib_template', name: 'Template', base: 'Note', strict: true, systemKey: 'template',
    description: 'Estrutura narrativa de referência (biblioteca). Aplique a um projeto com template_apply usando o template_id.',
    attributes: [a('template_id', 'Id do template (template_apply)', 'string_short'), a('escopo', 'Escopo (obra ou unidade)', 'string_short'),
      a('midias', 'Perfis onde se encaixa', 'list', { itemType: 'string_short' }), a('fonte', 'Fonte', 'string_short'),
      a('nome_en', 'Nome (inglês)', 'string_short'), a('descricao_en', 'Descrição (inglês)', 'text_long')] };
  const tBeat = { id: 'nt_lib_beat', name: 'Beat', base: 'Note', strict: true, systemKey: 'beat',
    description: 'Marco de um template, com posição esperada na obra.',
    attributes: [a('chave', 'Chave', 'string_short'), a('posicao', 'Posição esperada (0 a 1)', 'decimal'), a('funcao', 'Função dramática', 'text_long'),
      a('criterio', 'Critério de aceite', 'text_long'), a('recursos', 'Recursos esperados', 'list', { itemType: 'string_short' }), a('obrigatorio', 'Obrigatório', 'bool'),
      a('nome_en', 'Nome (inglês)', 'string_short'), a('funcao_en', 'Função (inglês)', 'text_long'), a('criterio_en', 'Critério (inglês)', 'text_long')] };
  const rBeatOf = { id: 'rt_lib_beat_of', name: 'BEAT_DE', base: 'REFERENCE', symmetric: false, temporal: false, systemKey: 'beat_of',
    allowed: [{ from: 'Beat', to: 'Template' }], description: 'O beat pertence a este template.' };

  const nodes = [], edges = [];
  let order = 0;
  for (const t of builtin) {
    const p = pt[t.id], e = en[t.id];
    const tid = rnd('n');
    nodes.push(clean({ id: tid, typeId: tTemplate.id, type: 'Template', base: 'Note', space: 'templates', name: p.name, summary: p.description,
      tags: ['biblioteca'], criticality: 'normal', order: order++,
      attributes: clean({ template_id: p.id, escopo: p.scope, midias: p.media, fonte: p.source, nome_en: e.name, descricao_en: e.description }) }));
    p.beats.forEach((b, i) => {
      const eb = e.beats[i] ?? {}; // the YAML is bilingual and aligned by position
      const bid = rnd('n');
      nodes.push(clean({ id: bid, typeId: tBeat.id, type: 'Beat', base: 'Note', space: 'templates', name: b.name, summary: b.function,
        criticality: 'normal', order: i,
        attributes: clean({ chave: b.key, posicao: b.position, funcao: b.function, criterio: b.criteria, recursos: b.devices,
          obrigatorio: b.required !== false, nome_en: eb.name, funcao_en: eb.function, criterio_en: eb.criteria }) }));
      edges.push({ id: rnd('e'), kind: 'rel', relTypeId: rBeatOf.id, relType: 'BEAT_DE', base: 'REFERENCE', from: bid, to: tid });
    });
  }
  const directives = [{ id: 'dr_lib_1', text: 'Este projeto é uma biblioteca de referência: não aplique templates aqui. Para usar uma estrutura num projeto, chame template_apply com o template_id do nó Template.',
    when: ['session'], priority: 9, enabled: true }];
  return {
    id: 'p_structure_library', name: 'Biblioteca de estruturas', description: 'Templates de estrutura narrativa (Três atos, Jornada do herói, Save the Cat...) com seus beats, em português e inglês. Migrados do Harness Machine.',
    context: 'narrative', locale: 'pt', profile: 'personalizado', createdAt: new Date().toISOString(),
    settings: { requireReviewForLlm: false },
    schema: { nodeTypes: [tTemplate, tBeat], relTypes: [rBeatOf], objectTypes: [] },
    nodes, edges, facts: [], directives,
    counts: { nodeTypes: 2, relTypes: 1, objectTypes: 0, nodes: nodes.length, edges: edges.length, facts: 0, directives: directives.length },
  };
}

// ------------------------------------------------------------------ parity check (Harness Machine vs Obliterate)

function flattenOutline(items, out = []) {
  for (const i of items ?? []) { out.push(i.name); flattenOutline(i.children, out); }
  return out;
}

async function checkProject(hmPid, oblPid, name) {
  const issues = [];
  const okIf = (cond, what) => { if (!cond) issues.push(what); };
  const [hS, oS] = await Promise.all([http(HM_API, 'GET', `/api/projects/${hmPid}/schema`), http(OBL, 'GET', `/api/projects/${oblPid}/schema`)]);
  okIf(hS.nodeTypes.length === oS.nodeTypes.length, `node types ${hS.nodeTypes.length} vs ${oS.nodeTypes.length}`);
  okIf(hS.relTypes.length === oS.relTypes.length, `relation types ${hS.relTypes.length} vs ${oS.relTypes.length}`);
  const keys = (s) => [...s.nodeTypes, ...s.relTypes].map((t) => t.systemKey).filter(Boolean).sort().join();
  okIf(keys(hS) === keys(oS), 'system keys differ');

  const [hV, oV] = await Promise.all([http(HM_API, 'POST', `/api/projects/${hmPid}/graph/view`, { limit: 5000 }), http(OBL, 'POST', `/api/projects/${oblPid}/graph/view`, { limit: 5000 })]);
  okIf(hV.nodes.length === oV.nodes.length, `nodes ${hV.nodes.length} vs ${oV.nodes.length}`);
  okIf(hV.edges.length === oV.edges.length, `edges ${hV.edges.length} vs ${oV.edges.length}`);
  const anchored = (v) => v.edges.filter((e) => e.fromUnit || e.toUnit).length;
  okIf(anchored(hV) === anchored(oV), `edges anchored in time ${anchored(hV)} vs ${anchored(oV)}`);

  const [hO, oO] = await Promise.all([http(HM_API, 'GET', `/api/projects/${hmPid}/outline?depth=20`), http(OBL, 'GET', `/api/projects/${oblPid}/outline?depth=20`)]);
  okIf(flattenOutline(hO).join('|') === flattenOutline(oO).join('|'), 'narrative outline (order of units) differs');

  const [hF, oF] = await Promise.all([http(HM_API, 'GET', `/api/projects/${hmPid}/facts?limit=5000`), http(OBL, 'GET', `/api/projects/${oblPid}/facts?limit=5000`)]);
  okIf(hF.length === oF.length, `facts ${hF.length} vs ${oF.length}`);
  okIf(hF.filter((f) => f.revealAt).length === oF.filter((f) => f.revealAt).length, 'secrets (revealAt) differ');

  const [hC, oC] = await Promise.all([http(HM_API, 'GET', `/api/projects/${hmPid}/coverage`), http(OBL, 'GET', `/api/projects/${oblPid}/coverage`)]);
  const cov = (c) => c.map((x) => x.templateName + ':' + (x.beats ?? []).map((b) => `${b.key}=${b.status}`).join(',')).sort().join(' ; ');
  okIf(cov(hC) === cov(oC), 'template coverage differs');

  const [hT, oT] = await Promise.all([http(HM_API, 'GET', `/api/projects/${hmPid}/threads?includeClosed=true`), http(OBL, 'GET', `/api/projects/${oblPid}/threads?includeClosed=true`)]);
  const thr = (l) => l.map((x) => `${x.name}|${x.status}|${x.unitsSincePlanted ?? ''}`).sort().join(';');
  okIf(thr(hT) === thr(oT), `hooks differ (${hT.length} vs ${oT.length})`);

  const [hD, oD] = await Promise.all([http(HM_API, 'GET', `/api/projects/${hmPid}/directives`), http(OBL, 'GET', `/api/projects/${oblPid}/directives`)]);
  okIf(hD.filter((d) => d.source === 'project').length === oD.filter((d) => d.source === 'project').length, 'project directives differ');

  // state in time and writing context for a few units, matched by name
  const byName = (v) => new Map(v.nodes.map((n) => [n.name + '|' + n.type, n.id]));
  const hN = byName(hV), oN = byName(oV);
  const oName = new Map(oV.nodes.map((n) => [n.id, n]));
  const units = oV.nodes.filter((n) => n.base === 'Unit');
  const sample = units.filter((_, i) => i % Math.max(1, Math.floor(units.length / 4)) === 0).slice(0, 4);
  for (const u of sample) {
    const hu = hN.get(u.name + '|' + u.type);
    if (!hu) { issues.push(`unit ${u.name} not found in Harness Machine by name`); continue; }
    const [hw, ow] = await Promise.all([http(HM_API, 'GET', `/api/projects/${hmPid}/writing-context/${hu}`), http(OBL, 'GET', `/api/projects/${oblPid}/writing-context/${u.id}`)]);
    // compares content, not order: the state lines per entity are capped and ties (same timestamp) break by id, which the
    // import renews, so which lines make the cut can change while the underlying relations are the same
    const sig = (w) => [(w.path ?? []).map((x) => x.name).join('>'), (w.previous ?? []).map((x) => x.name).join('>'),
      (w.inPlay ?? []).map((x) => `${x.name}:${(x.state ?? []).length}`).sort().join(','),
      (w.facts ?? []).map((f) => f.text).sort().join('|'), (w.secrets ?? []).length, (w.openThreads ?? []).map((x) => x.name).sort().join(','),
      (w.beatsHere ?? []).map((b) => b.key).sort().join(','), (w.guidance ?? []).length].join(' # ');
    okIf(sig(hw) === sig(ow), `writing context of "${u.name}" differs`);
  }
  const anchoredEdges = oV.edges.filter((e) => e.fromUnit || e.toUnit).slice(0, 5);
  for (const e of anchoredEdges) {
    const ent = oName.get(e.from), unit = oName.get(e.toUnit ?? e.fromUnit);
    if (!ent || !unit) continue;
    const he = hN.get(ent.name + '|' + ent.type), hu = hN.get(unit.name + '|' + unit.type);
    if (!he || !hu) continue;
    const [hs, os] = await Promise.all([http(HM_API, 'GET', `/api/projects/${hmPid}/state-at?entity=${he}&unit=${hu}`), http(OBL, 'GET', `/api/projects/${oblPid}/state-at?entity=${e.from}&unit=${unit.id}`)]);
    const st = (s) => ['active', 'ended', 'notYet'].map((k) => (s[k] ?? []).map((x) => `${x.fromName}-${x.relType}-${x.toName}`).sort().join(',')).join(' | ');
    okIf(st(hs) === st(os), `state_at(${ent.name}, ${unit.name}) differs`);
  }
  console.log(issues.length ? `  ✗ ${name}: ${issues.length} difference(s)\n    - ${issues.join('\n    - ')}` : `  ✓ ${name}: identical (schema, ${oV.nodes.length} nodes, ${oV.edges.length} edges, outline, facts, coverage, hooks, directives, state in time, writing context)`);
  return issues.length;
}

// ------------------------------------------------------------------ main

let archive;
let importResult;
if (CHECK_ONLY) {
  importResult = JSON.parse(fs.readFileSync(CHECK_ONLY, 'utf8'));
} else {
  console.log(`# Reading Harness Machine (${HM_NEO4J})`);
  const projects = [];
  for (const pid of PROJECTS) {
    const e = await projectEntry(pid);
    console.log(`  ${e.name}: ${JSON.stringify(e.counts)}`);
    projects.push(e);
  }
  if (WITH_LIBRARY) {
    const lib = await libraryEntry();
    console.log(`  ${lib.name}: ${lib.counts.nodes} nodes (${lib.nodes.filter((n) => n.type === 'Template').length} templates)`);
    projects.push(lib);
  }
  archive = { format: 'obliterate-archive', version: 1, exportedAt: new Date().toISOString(), generator: 'harness-machine-migration',
    scope: 'all', contexts: [{ id: 'narrative', kind: 'builtin', name: 'Histórias e mundos' }], projects };
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(archive, null, 2));
  console.log(`# Archive written: ${OUT} (${(fs.statSync(OUT).size / 1024).toFixed(0)} KB)`);
  if (!OBL) {
    console.log('Dry run: nothing imported. Re-run with --import <obliterate url> to import and check.');
    process.exit(0);
  }
  console.log(`# Importing into Obliterate (${OBL})`);
  const form = new FormData();
  form.append('file', new Blob([JSON.stringify(archive)], { type: 'application/json' }), path.basename(OUT));
  const res = await fetch(`${OBL}/api/import`, { method: 'POST', body: form, headers: { 'x-lang': 'pt' } });
  importResult = await res.json();
  if (!res.ok) { console.error('Import failed:', importResult.detail ?? importResult); process.exit(1); }
  fs.writeFileSync(OUT.replace(/\.json$/, '.import_result.json'), JSON.stringify(importResult, null, 2));
  for (const p of importResult.projects) console.log(`  ${p.name} → ${p.projectId} ${JSON.stringify(p.counts)}`);
  for (const n of importResult.notes ?? []) console.log('  note:', n);
}

console.log('# Checking Harness Machine vs Obliterate');
let diffs = 0;
for (const p of importResult.projects) {
  if (p.sourceId === 'p_structure_library') {
    const v = await http(OBL, 'POST', `/api/projects/${p.projectId}/graph/view`, { limit: 5000 });
    const tpl = v.nodes.filter((n) => n.type === 'Template').length;
    const ok = tpl > 0 && v.edges.length === v.nodes.length - tpl;
    console.log(ok ? `  ✓ ${p.name}: ${tpl} templates, ${v.nodes.length - tpl} beats` : `  ✗ ${p.name}: ${tpl} templates, ${v.edges.length} beat links for ${v.nodes.length - tpl} beats`);
    if (!ok) diffs++;
    continue;
  }
  diffs += await checkProject(p.sourceId, p.projectId, p.name);
}
console.log(diffs === 0 ? '\nMIGRATION OK' : `\n${diffs} DIFFERENCE(S): see above`);
process.exit(diffs === 0 ? 0 : 1);
