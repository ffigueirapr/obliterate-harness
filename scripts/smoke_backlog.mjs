// Backlog features test (BACKLOG_harness_mcp.md), on an English project. Also checks clone + translation to pt.
// Usage: node scripts/smoke_backlog.mjs [baseUrl] [--keep]   (creates disposable "SmokeB …" projects and deletes them at the end)
const BASE = process.argv.find((a) => a.startsWith('http')) ?? 'http://127.0.0.1:8180';
const KEEP = process.argv.includes('--keep');
let failures = 0;
const check = (cond, msg) => { if (cond) console.log('  ok  ' + msg); else { failures++; console.log('  FAILED  ' + msg); } };

async function api(method, path, body, expectStatus, lang = 'en') {
  const res = await fetch(BASE + path, { method, headers: { 'content-type': 'application/json', 'x-lang': lang }, body: body === undefined ? undefined : JSON.stringify(body) });
  const buf = Buffer.from(await res.arrayBuffer());
  const ct = res.headers.get('content-type') ?? '';
  const data = !buf.length ? null : ct.includes('json') ? JSON.parse(buf.toString('utf8')) : buf;
  if (expectStatus !== undefined ? res.status !== expectStatus : !res.ok) throw new Error(`${method} ${path} -> ${res.status}: ${buf.toString('utf8').slice(0, 400)}`);
  return data;
}
const ops = (pid, title, operations, mode) => api('POST', `/api/projects/${pid}/changesets`, { title, operations, mode });
const created = [];

async function mcp(sid, method, params, n) {
  const res = await fetch(BASE + '/mcp', { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', ...(sid ? { 'mcp-session-id': sid } : {}) },
    body: JSON.stringify({ jsonrpc: '2.0', id: n, method, params }) });
  const text = await res.text();
  const line = text.split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5)).join('');
  return { sid: res.headers.get('mcp-session-id') ?? sid, msg: JSON.parse(line || text) };
}
let sid; let rpc = 1;
const tool = async (name, args) => {
  const r = await mcp(sid, 'tools/call', { name, arguments: args }, ++rpc);
  const text = r.msg.result?.content?.[0]?.text ?? JSON.stringify(r.msg.error);
  let body; try { body = JSON.parse(text); } catch { body = text; }
  return { isError: !!r.msg.result?.isError || !!r.msg.error, body, text };
};

console.log('# English by default');
const proj = await api('POST', '/api/projects', { name: 'SmokeB book ' + new Date().toISOString(), profile: 'livro' }, 201);
created.push(proj);
const pid = proj.id;
check(proj.locale === 'en', 'new project defaults to en');
const schema = await api('GET', `/api/projects/${pid}/schema`);
const typeNames = schema.nodeTypes.map((t) => t.name);
check(['Work', 'Chapter', 'Scene', 'Character', 'Hook'].every((t) => typeNames.includes(t)), 'book profile in English (Work, Chapter, Scene, Character, Hook)');
check(schema.relTypes.some((r) => r.systemKey === 'about') && schema.relTypes.some((r) => r.systemKey === 'appears_in'), 'system keys on relations (about, appears_in)');
const err = await api('POST', `/api/projects/${pid}/changesets`, { operations: [{ op: 'create_node', type: 'Nope', name: 'x' }] }, 400);
check(err.detail.includes('does not exist'), 'errors in English');
const errPt = await api('POST', `/api/projects/${pid}/changesets`, { operations: [{ op: 'create_node', type: 'Nope', name: 'x' }] }, 400, 'pt');
check(errPt.detail.includes('não existe'), 'X-Lang: pt gives Portuguese errors');

console.log('# Structure, cast, location (items 7, 9)');
const w = await ops(pid, 'structure', [
  { op: 'create_node', ref: '$work', type: 'Work', name: 'The Work' },
  ...[1, 2, 3].map((i) => ({ op: 'create_node', ref: `$c${i}`, type: 'Chapter', name: `Chapter ${i}`, parent: '$work', summary: `Summary ${i}` })),
  { op: 'create_node', ref: '$inn', type: 'Place', name: 'Crooked Inn' },
  { op: 'create_node', ref: '$ana', type: 'Character', name: 'Ana', summary: 'A thief', attributes: { secret: 'She is the duke\'s daughter' } },
  { op: 'create_node', ref: '$bruno', type: 'Character', name: 'Bruno', summary: 'Her brother' },
  { op: 'create_node', ref: '$key', type: 'Item', name: 'Obsidian Key' },
  { op: 'create_node', ref: '$s1', type: 'Scene', name: 'Scene 1.1', parent: '$c1', attributes: { goal: 'Steal the key', conflict: 'Guards', outcome: 'yes_but', location: '$inn' }, cast: ['$ana', '$bruno'] },
  { op: 'create_node', ref: '$hook', type: 'Hook', name: 'Who forged the key?', attributes: { kind: 'mystery', status: 'open' } },
  { op: 'create_edge', relType: 'ABOUT', from: '$hook', to: '$key' },
  { op: 'create_edge', relType: 'PLANTS', from: '$c1', to: '$hook' },
  { op: 'create_edge', relType: 'PLANTS', from: '$c1', to: '$key' },
  { op: 'create_fact', text: 'Ana is the duke\'s daughter', criticality: 'foundational', about: ['$ana'], revealAt: '$c3' },
]);
check(w.status === 'applied', 'structure applied');
const id = w.ids;
check(w.changes.every((c) => c.version !== undefined || c.kind === 'fact' || c.op === 'create_fact'), 'changes carry versions (item 12)');
const s1 = await api('GET', `/api/projects/${pid}/nodes/${id.$s1}`);
check(s1.edges.filter((e) => e.relType === 'APPEARS_IN').length === 2, 'cast field creates APPEARS_IN edges');
check(s1.edges.some((e) => e.relType === 'LOCATED_IN' || (s1.node.refs?.location ?? []).includes(id.$inn)), 'scene location attribute');
const threads = await api('GET', `/api/projects/${pid}/threads`);
const hk = threads.find((t) => t.id === id.$hook);
check(hk && (hk.about ?? []).some((a) => a.id === id.$key), 'hook ABOUT relation shows in threads');
check(threads.some((t) => t.id === id.$key && t.clue), 'any node planted becomes a clue');

console.log('# dryRun / best_effort / error list / alternatives (items 3, 13)');
const dry = await api('POST', `/api/projects/${pid}/changesets`, { mode: 'dry_run', operations: [
  { op: 'create_node', ref: '$x', type: 'Item', name: 'Dry item' },
  { op: 'create_edge', relType: 'OWNS', from: id.$key, to: id.$ana },
  { op: 'create_node', type: 'Nope', name: 'bad' },
] });
check(dry.status === 'dry_run' && (dry.errors ?? []).length === 2, `dry run lists every error (${(dry.errors ?? []).length})`);
check((dry.errors ?? []).some((e) => /allow|instead|Relations/i.test(e.message)), 'disallowed pair suggests alternative relations');
const after = await api('GET', `/api/projects/${pid}/nodes?q=Dry`);
check(!after.some((n) => n.name === 'Dry item'), 'dry run writes nothing');
const be = await ops(pid, 'best effort', [
  { op: 'create_node', ref: '$y', type: 'Item', name: 'Good item' },
  { op: 'create_node', type: 'Nope', name: 'bad' },
], 'best_effort');
check(be.ids?.$y && (be.errors ?? []).length === 1, 'best_effort applies the valid ops and reports the rest');

console.log('# Directive edit/disable (item 8)');
const d = await ops(pid, 'directive', [{ op: 'upsert_directive', text: 'Ana never lies.', definition: { when: ['context'], types: ['Character'] } }]);
const dirId = d.changes[0].id;
await ops(pid, 'directive edit', [{ op: 'upsert_directive', id: dirId, text: 'Ana never lies outright.', definition: { when: ['context'], types: ['Character'], enabled: false } }]);
const dirs = await api('GET', `/api/projects/${pid}/directives`);
const mine = dirs.filter((x) => x.source === 'project');
check(mine.length === 1 && mine[0].text === 'Ana never lies outright.', 'directive edited by id (no duplicate)');
const wcOff = await api('GET', `/api/projects/${pid}/writing-context/${id.$c1}`);
check(!(wcOff.guidance ?? []).includes('Ana never lies outright.'), 'disabled directive is not triggered');
check(dirs.some((x) => x.source === 'profile:livro'), 'profile directives labelled profile:<id>');

console.log('# Manuscript (item 5)');
const t1 = await api('PUT', `/api/projects/${pid}/units/${id.$c1}/text`, { text: 'Ana crept into the Crooked Inn.\n\nBruno waited outside with Marcus, and Marcus hummed.', note: 'first pass' });
check(t1.words === 14 && t1.textVersion === 1, `unit text set, server word count (${t1.words})`);
const t2 = await api('PUT', `/api/projects/${pid}/units/${id.$c1}/text`, { text: 'The key was cold.', mode: 'append' });
check(t2.textVersion === 2 && t2.words === 18, 'append creates version 2');
const hist = await api('GET', `/api/projects/${pid}/units/${id.$c1}/text/history`);
check(hist.length === 2, 'text history');
const diff = await api('GET', `/api/projects/${pid}/units/${id.$c1}/text/diff?from=1&to=2`);
check(diff.added >= 1, 'diff between versions');
const c1 = (await api('GET', `/api/projects/${pid}/nodes/${id.$c1}`)).node;
check(c1.wordCount === 18 && c1.attributes.words === 18, 'word count stored on the node');
await api('PUT', `/api/projects/${pid}/units/${id.$c2}/text`, { text: 'Chapter two prose. The last paragraph.' });
for (const fmt of ['md', 'txt', 'html', 'docx', 'epub']) {
  const file = await api('GET', `/api/projects/${pid}/export?format=${fmt}`);
  const bytes = Buffer.isBuffer(file) ? file : Buffer.from(JSON.stringify(file));
  const ok = fmt === 'docx' || fmt === 'epub' ? bytes[0] === 0x50 && bytes[1] === 0x4b : bytes.toString('utf8').includes('Ana crept');
  check(ok, `export ${fmt} (${bytes.length} bytes)`);
}

console.log('# Validation (item 6)');
const v = await api('POST', `/api/projects/${pid}/units/${id.$c1}/validate`, {});
check((v.possibleNewEntities ?? []).some((x) => x.startsWith('Marcus')), 'validate flags an unknown proper noun (Marcus)');
check((v.mentionedNotInCast ?? []).some((r) => r.name === 'Ana'), 'validate flags Ana mentioned but not in chapter cast');
await api('PUT', `/api/projects/${pid}/units/${id.$c1}/cast`, { nodeIds: [id.$ana, id.$bruno], mode: 'replace' });
const v2 = await api('POST', `/api/projects/${pid}/units/${id.$c1}/validate`, {});
check(!(v2.mentionedNotInCast ?? []).some((r) => r.name === 'Ana'), 'unit cast set fixes it');

console.log('# Writing context (item 11)');
await api('PATCH', `/api/projects/${pid}`, { styleSample: 'Short sentences. Cold light. Nobody says what they mean.' });
const wc2 = await api('GET', `/api/projects/${pid}/writing-context/${id.$c2}?tailParagraphs=1&focus=secrets`);
check(wc2.previousTail?.paragraphs?.length === 1 && wc2.previousTail.paragraphs[0].includes('key was cold'), 'tail of the previous chapter');
check(wc2.style?.text?.includes('Cold light'), 'style sample');
check((wc2.secrets ?? []).some((s) => s.text.includes('duke')), 'secrets listed before reveal');
const wc1 = await api('GET', `/api/projects/${pid}/writing-context/${id.$c1}`);
check((wc1.parts ?? []).some((p) => p.attributes?.goal === 'Steal the key'), 'scenes with goal/conflict/outcome');
const wc3 = await api('GET', `/api/projects/${pid}/writing-context/${id.$c3}`);
check((wc3.revealedHere ?? []).some((s) => s.text.includes('duke')), 'secret revealed at its unit');

console.log('# MCP: session refs, edge_get, versions (items 4, 12, 13)');
const init = await mcp(null, 'initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'smokeB', version: '1' } }, 1);
sid = init.sid;
check(init.msg.result.instructions.includes('Operating contract'), 'MCP instructions in English');
await fetch(BASE + '/mcp', { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', 'mcp-session-id': sid }, body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) });
const names = (await mcp(sid, 'tools/list', {}, ++rpc)).msg.result.tools.map((t) => t.name);
const expected = ['project_clone', 'project_localize', 'project_settings', 'template_derive', 'template_upsert', 'template_delete', 'unit_text_set',
  'unit_text_append', 'unit_text_get', 'unit_text_history', 'unit_text_diff', 'unit_cast_set', 'unit_validate', 'manuscript_export', 'edge_get'];
check(expected.every((t) => names.includes(t)), `new tools present (${names.length} total)`);
const b = await tool('session_begin', { projectId: pid, goal: 'write chapter 3' });
check(b.body.locale === 'en' && b.body.localTime && b.body.stats?.words >= 18, 'briefing has locale, local time and word stats');
const s = b.body.sessionId;
const p1 = await tool('propose_changes', { projectId: pid, title: 'a', sessionId: s, operations: [{ op: 'create_node', ref: '$cat', type: 'Character', name: 'Cat' }] });
const p2 = await tool('propose_changes', { projectId: pid, title: 'b', sessionId: s, operations: [{ op: 'create_edge', ref: '$e', relType: 'ALLY_OF', from: '$cat', to: id.$ana }] });
check(!p2.isError && p2.body.status === 'applied', 'session $alias reused in a later batch');
const edgeId = p2.body.ids.$e;
const eg = await tool('edge_get', { projectId: pid, ids: [edgeId] });
check(!eg.isError && JSON.stringify(eg.body).includes(edgeId) && JSON.stringify(eg.body).includes('"version"'), 'edge_get returns the edge with its version');
const r = await tool('session_resume', { projectId: pid, sessionId: s });
check(r.body.sessionRefs?.$cat === p1.body.ids.$cat, 'session_resume lists the session aliases');
const dr = await tool('propose_changes', { projectId: pid, title: 'dry', dryRun: true, operations: [{ op: 'create_node', type: 'Nope', name: 'z' }, { op: 'create_node', type: 'Item', name: 'zz' }] });
check(dr.body.status === 'dry_run' && dr.body.errors.length === 1, 'dryRun via MCP');
const ct = await tool('unit_cast_set', { projectId: pid, unitId: id.$c3, nodeIds: ['$cat', id.$ana], sessionId: s });
check(!ct.isError, 'unit_cast_set with session alias');
const ut = await tool('unit_text_append', { projectId: pid, unitId: id.$c3, text: 'Cat smiled.', sessionId: s });
check(!ut.isError && ut.body.words === 2, 'unit_text_append via MCP');
const ex = await tool('manuscript_export', { projectId: pid, format: 'docx' });
check(!ex.isError && ex.text.includes('/api/projects/' + pid + '/export'), 'manuscript_export gives a download URL');

console.log('# Templates: derive / upsert / coverage vs derived (items 2, 13)');
const der = await tool('template_derive', { fromProjectId: pid, unitType: 'Chapter', name: 'SmokeB derived' });
check(!der.isError && der.body.custom && der.body.beats.length === 3, 'template_derive: one beat per chapter');
const tplId = der.body.id;
const up = await tool('template_upsert', { definition: { name: 'SmokeB handmade', beats: [{ name: 'Start', position: 0 }, { name: 'End', position: 1 }] } });
check(!up.isError && up.body.id && up.body.beats.length === 2, 'template_upsert with optional fields omitted');
const lib = await api('GET', '/api/harness/templates');
check(lib.some((t) => t.id === tplId && t.custom), 'custom templates in the library');
const sb = await tool('session_begin', { projectId: pid, goal: 'mirror the structure of SmokeB derived' });
check(JSON.stringify(sb.body.guidance ?? []).includes(tplId) || JSON.stringify(sb.body).includes('SmokeB derived'), 'session_begin suggests the derived template');
const ap = await api('POST', `/api/projects/${pid}/templates/apply`, { templateId: tplId, plannedUnits: 3 });
check(ap.status === 'applied', 'apply derived template');
const cov = await api('GET', `/api/projects/${pid}/coverage`);
const dcov = cov.find((c) => c.templateName === 'SmokeB derived');
check(dcov && dcov.beats.every((x) => ['ok', 'off_position', 'missing', 'optional_missing', 'future'].includes(x.status)), 'coverage against the derived template (English status codes)');
await tool('template_delete', { templateId: up.body.id });
await tool('template_delete', { templateId: tplId });
check(!(await api('GET', '/api/harness/templates')).some((t) => t.id === tplId), 'template_delete');

console.log('# Clone + translate (items 1, 10)');
const cl = await api('POST', `/api/projects/${pid}/clone`, { newName: 'SmokeB clone pt', translateTo: 'pt' }, 201);
created.push({ id: cl.projectId, name: cl.name });
check(cl.projectId !== pid && cl.idMap[id.$ana], 'clone with id map');
const cp = await api('GET', `/api/projects/${cl.projectId}`);
check(cp.locale === 'pt', 'clone translated to pt');
const cs = await api('GET', `/api/projects/${cl.projectId}/schema`);
const ptNames = cs.nodeTypes.map((t) => t.name);
check(['Obra', 'Capítulo', 'Cena', 'Personagem', 'Gancho'].every((t) => ptNames.includes(t)), 'types translated (Obra, Capítulo, Cena, Personagem, Gancho)');
const anaPt = await api('GET', `/api/projects/${cl.projectId}/nodes/${cl.idMap[id.$ana]}`);
check(anaPt.node.type === 'Personagem' && anaPt.node.attributes.segredo, 'node migrated (Character → Personagem, secret → segredo)');
check(anaPt.edges.some((e) => e.relType === 'APARECE_EM'), 'relations translated (APPEARS_IN → APARECE_EM)');
const s1pt = await api('GET', `/api/projects/${cl.projectId}/nodes/${cl.idMap[id.$s1]}`);
check(s1pt.node.attributes.resultado === 'sim_mas', 'enum value translated (yes_but → sim_mas)');
const factsPt = await api('GET', `/api/projects/${cl.projectId}/facts?minCriticality=critical`);
check(factsPt.some((f) => f.text.includes('duke') && f.revealAt?.id === cl.idMap[id.$c3]), 'facts keep revealAt pointing to the copied unit');
const txtPt = await api('GET', `/api/projects/${cl.projectId}/units/${cl.idMap[id.$c1]}/text`);
check(txtPt.text.includes('Ana crept') && txtPt.textVersion === 2, 'manuscript and its history copied');
const covPt = await api('GET', `/api/projects/${cl.projectId}/coverage`);
check(covPt.length === cov.length, 'templates copied');
const back = await api('POST', `/api/projects/${cl.projectId}/localize`, { locale: 'en' });
check(back.to === 'en', 'localize back to en');
const csBack = await api('GET', `/api/projects/${cl.projectId}/schema`);
check(csBack.nodeTypes.map((t) => t.name).sort().join() === typeNames.sort().join(), 'round trip en → pt → en keeps the type names');

console.log('# Cleanup');
if (KEEP) console.log('  (kept: ' + created.map((p) => p.id).join(', ') + ')');
else {
  for (const p of created) await api('DELETE', `/api/projects/${p.id}?confirm=${encodeURIComponent(p.name)}`, undefined, 204);
  check(!(await api('GET', '/api/projects')).some((p) => created.some((c) => c.id === p.id)), `${created.length} test projects deleted`);
}

console.log(failures === 0 ? '\nALL OK' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
