// End-to-end test of export/import (obliterate-archive) and project renaming (REST + MCP): builds a project in a custom
// context with node_ref attributes, time anchors, mirrored and manual facts and directives, exports it, imports it back and
// compares everything; then the error cases (format, version, broken refs, all or nothing) and the context recreation.
// Usage: node scripts/smoke_archive.mjs [http://127.0.0.1:8180] [--project <id>] [--keep]
//   --project <id>  also round-trips an existing project (export → import as a copy → compare → delete the copy)
const BASE = process.argv.find((a) => a.startsWith('http')) ?? 'http://127.0.0.1:8180';
const KEEP = process.argv.includes('--keep');
const EXISTING = process.argv.includes('--project') ? process.argv[process.argv.indexOf('--project') + 1] : undefined;
let failures = 0;
const ok = (cond, msg) => { console.log(`  ${cond ? 'ok ' : 'FAIL'} ${msg}`); if (!cond) failures++; };

async function raw(method, path, body, headers = {}) {
  const res = await fetch(BASE + path, { method, headers: { 'x-lang': 'en', ...headers }, body });
  const text = await res.text();
  return { status: res.status, headers: res.headers, data: text ? JSON.parse(text) : null };
}
async function call(method, path, body) {
  const r = await raw(method, path, body === undefined ? undefined : JSON.stringify(body), body === undefined ? {} : { 'content-type': 'application/json' });
  if (r.status >= 400) throw new Error(`${method} ${path} → ${r.status}: ${r.data?.detail ?? JSON.stringify(r.data)}`);
  return r.data;
}
/** Expects the call to fail with 400; returns the error detail. */
async function refused(method, path, body, contentType = 'application/json') {
  const r = await raw(method, path, typeof body === 'string' || body instanceof FormData ? body : JSON.stringify(body), contentType ? { 'content-type': contentType } : {});
  return r.status === 400 ? (r.data?.detail ?? '') : null;
}

let mcpSession;
async function mcp(method, params) {
  const headers = { 'content-type': 'application/json', accept: 'application/json, text/event-stream' };
  if (mcpSession) headers['mcp-session-id'] = mcpSession;
  const res = await fetch(BASE + '/mcp', { method: 'POST', headers, body: JSON.stringify({ jsonrpc: '2.0', id: Math.floor(Math.random() * 1e9), method, params }) });
  if (!mcpSession) mcpSession = res.headers.get('mcp-session-id');
  const text = await res.text();
  const line = text.split('\n').find((l) => l.startsWith('data:'));
  return JSON.parse(line ? line.slice(5) : text);
}
async function tool(name, args) {
  const r = await mcp('tools/call', { name, arguments: args });
  if (r.error || r.result?.isError) throw new Error(`${name}: ${JSON.stringify(r.error ?? r.result.content)}`);
  return JSON.parse(r.result.content[0].text);
}
async function toolError(name, args) {
  const r = await mcp('tools/call', { name, arguments: args });
  return r.error || r.result?.isError ? JSON.stringify(r.error ?? r.result.content) : null;
}

/** Comparable view of a project in an archive: by name, with node ids replaced by node names. */
function shape(p) {
  const names = new Map(p.nodes.map((n) => [n.id, n.name]));
  const swap = (v) => (typeof v === 'string' && names.has(v) ? '→' + names.get(v) : Array.isArray(v) ? v.map(swap) : v && typeof v === 'object' ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, swap(x)])) : v);
  const sort = (xs) => xs.map((x) => JSON.stringify(x)).sort();
  return {
    nodeTypes: sort(p.schema.nodeTypes.map(({ id, ...rest }) => rest)),
    relTypes: sort((p.schema.relTypes ?? []).map(({ id, ...rest }) => rest)),
    objectTypes: sort((p.schema.objectTypes ?? []).map(({ id, ...rest }) => rest)),
    nodes: sort(p.nodes.map((n) => ({ type: n.type, name: n.name, summary: n.summary, tags: n.tags, criticality: n.criticality, space: n.space,
      attributes: swap(n.attributes), refs: swap(n.refs), attributeCriticality: n.attributeCriticality, order: n.order, version: n.version }))),
    edges: sort(p.edges.map((e) => ({ kind: e.kind, relType: e.relType, from: names.get(e.from), to: names.get(e.to), attrKey: e.attrKey, description: e.description,
      criticality: e.criticality, fromUnit: names.get(e.fromUnit), toUnit: names.get(e.toUnit), version: e.version }))),
    facts: sort(p.facts.map((f) => ({ text: f.text, rigidity: f.rigidity, criticality: f.criticality, originKind: f.originKind, about: (f.about ?? []).map((a) => names.get(a)).sort(),
      revealAt: names.get(f.revealAt) }))),
    directives: sort(p.directives.map((d) => ({ text: d.text, when: d.when, types: d.types, priority: d.priority, enabled: d.enabled }))),
  };
}
function compare(a, b, label) {
  const sa = shape(a), sb = shape(b);
  for (const k of Object.keys(sa)) {
    const same = JSON.stringify(sa[k]) === JSON.stringify(sb[k]);
    ok(same, `${label}: ${k} identical (${sa[k].length})`);
    if (!same) {
      const only = sa[k].filter((x) => !sb[k].includes(x)).slice(0, 2);
      if (only.length) console.log('       only in source: ' + only.join('\n       '));
    }
  }
}

const created = { projects: [], contexts: [] };
const stamp = Date.now();
try {
  console.log('# Source: a custom context and a project with every kind of record');
  const ctx = await call('POST', '/api/contexts', { name: 'Smoke archive ctx ' + stamp, description: 'archive test', icon: '📦', locale: 'en',
    baseContext: 'narrative', instructions: 'Archive smoke instructions.' }); // narrative base: units, time anchors and secrets live there
  created.contexts.push(ctx);
  const src = await call('POST', `/api/contexts/${ctx.id}/projects`, { name: 'Smoke archive ' + stamp, description: 'source', locale: 'en' });
  created.projects.push(src);
  const pid = src.id;
  const schemaCs = await call('POST', `/api/projects/${pid}/changesets`, { title: 'schema', operations: [
    { op: 'upsert_node_type', definition: { name: 'Team', base: 'Agent' } },
    { op: 'upsert_object_type', definition: { name: 'Sla', fields: [{ key: 'hours', dataType: 'int' }, { key: 'tier', dataType: 'string_short' }] } },
    { op: 'upsert_node_type', definition: { name: 'System', base: 'Device', attributes: [
      { key: 'level', dataType: 'enum', options: ['low', 'high'] },
      { key: 'owner', dataType: 'node_ref', refType: 'Team' },
      { key: 'depends_on', dataType: 'list', itemType: 'node_ref' },
      { key: 'sla', dataType: 'composite', objectType: 'Sla' },
      { key: 'notes', dataType: 'text_long' },
    ] } },
    { op: 'upsert_node_type', definition: { name: 'Phase', base: 'Unit' } },
    { op: 'upsert_rel_type', definition: { name: 'FEEDS', base: 'CAUSAL', allowed: [{ from: 'System', to: 'System' }] } },
  ] });
  ok(schemaCs.status === 'applied', 'schema applied');
  const data = await call('POST', `/api/projects/${pid}/changesets`, { title: 'data', operations: [
    { op: 'create_node', ref: '$team', type: 'Team', name: 'Payments squad', attributes: {} },
    { op: 'create_node', ref: '$p1', type: 'Phase', name: 'Phase 1' },
    { op: 'create_node', ref: '$p2', type: 'Phase', name: 'Phase 2' },
    { op: 'create_node', ref: '$p3', type: 'Phase', name: 'Phase 3', parent: '$p1' },
    { op: 'create_node', ref: '$core', type: 'System', name: 'Core ledger', summary: 'Book of record', tags: ['Core', 'tier1'], criticality: 'critical',
      attributes: { level: 'high', owner: '$team', notes: 'Never write directly.' }, attributeCriticality: { notes: 'foundational' } },
    { op: 'create_node', ref: '$api', type: 'System', name: 'Public API', attributes: { level: 'low', owner: '$team', depends_on: ['$core'] } },
    { op: 'create_edge', relType: 'FEEDS', from: '$api', to: '$core', description: 'writes go through the API', criticality: 'critical', fromUnit: '$p1', toUnit: '$p2' },
    { op: 'create_fact', text: 'The ledger is append-only', rigidity: 'law', criticality: 'foundational', about: ['$core'], revealAt: '$p2' },
    { op: 'upsert_directive', text: 'Always name the owning team.', definition: { when: ['context'], types: ['System'], priority: 5 } },
  ] });
  ok(data.status === 'applied', 'data applied');
  const id = data.ids;
  // a later update: versions travel too
  const core = (await call('GET', `/api/projects/${pid}/nodes/${id.$core}`)).node;
  await call('POST', `/api/projects/${pid}/changesets`, { title: 'update', operations: [
    { op: 'update_node', id: id.$core, expectedVersion: core.version, attributes: { sla: { hours: 4, tier: 'gold' } } }] });

  console.log('# Rename (REST)');
  const renamed = await call('PATCH', `/api/projects/${pid}`, { name: '  Smoke archive renamed ' + stamp + '  ' });
  ok(renamed.name === 'Smoke archive renamed ' + stamp, 'PATCH renames (trimmed)');
  src.name = renamed.name;
  ok((await refused('PATCH', `/api/projects/${pid}`, { name: '   ' }))?.includes('empty'), 'empty name is refused');
  const other = await call('POST', `/api/contexts/${ctx.id}/projects`, { name: 'Smoke archive other ' + stamp, locale: 'en' });
  created.projects.push(other);
  ok((await refused('PATCH', `/api/projects/${other.id}`, { name: renamed.name.toUpperCase() }))?.includes('already'), 'duplicate name is refused (case-insensitive)');
  ok((await call('PATCH', `/api/projects/${pid}`, { description: 'only the description' })).name === renamed.name, 'PATCH without name keeps it');

  console.log('# Rename (MCP project_update)');
  await mcp('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'smoke', version: '1' } });
  await fetch(BASE + '/mcp', { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', 'mcp-session-id': mcpSession },
    body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) });
  const viaMcp = await tool('project_update', { projectId: other.id, name: 'Smoke archive other v2 ' + stamp, description: 'via mcp' });
  ok(viaMcp.name === 'Smoke archive other v2 ' + stamp && viaMcp.description === 'via mcp', 'project_update changes name and description');
  other.name = viaMcp.name;
  ok(!!(await toolError('project_update', { projectId: other.id, name: renamed.name })), 'project_update refuses a duplicate name');

  console.log('# Export (REST)');
  const ex = await raw('GET', `/api/projects/${pid}/export`);
  ok(ex.status === 200 && /attachment/.test(ex.headers.get('content-disposition') ?? '') && /\.json/.test(ex.headers.get('content-disposition')), 'download with a .json file name');
  const arch = ex.data;
  ok(arch.format === 'obliterate-archive' && arch.version === 1 && arch.scope === 'project' && !!arch.exportedAt, 'format, version, scope, exportedAt');
  const p = arch.projects[0];
  ok(arch.projects.length === 1 && p.id === pid && p.name === renamed.name && p.context === ctx.id && p.locale === 'en', 'project metadata with its original id');
  const actx = arch.contexts.find((c) => c.id === ctx.id);
  ok(actx?.kind === 'custom' && actx.instructions === 'Archive smoke instructions.' && actx.definition?.schema?.nodeTypes?.length > 0, 'custom context with its definition');
  ok(p.schema.nodeTypes.some((t) => t.name === 'System' && t.attributes.some((a) => a.dataType === 'node_ref')), 'node types with attributes');
  ok(p.schema.relTypes.some((r) => r.name === 'FEEDS' && r.allowed?.[0]?.from === 'System'), 'relation types with allowed pairs');
  ok(p.schema.objectTypes.some((o) => o.name === 'Sla'), 'object types');
  const xcore = p.nodes.find((n) => n.name === 'Core ledger');
  ok(xcore.id === id.$core && xcore.refs?.owner?.[0] === id.$team && xcore.attributes.sla.hours === 4 && xcore.version === 2, 'nodes keep ids, plain attributes and version');
  ok(xcore.attributeCriticality?.notes === 'foundational', 'attribute criticality');
  const feed = p.edges.find((e) => e.relType === 'FEEDS');
  ok(feed.fromUnit === id.$p1 && feed.toUnit === id.$p2 && feed.description === 'writes go through the API', 'edges with anchors and description');
  ok(p.edges.some((e) => e.kind === 'attr' && e.attrKey === 'owner') && p.edges.some((e) => e.kind === 'structure'), 'attribute and structure edges');
  ok(p.facts.some((f) => f.originKind === 'manual' && f.revealAt === id.$p2) && p.facts.some((f) => f.originKind === 'edge'), 'manual (revealAt) and mirrored facts');
  ok(p.directives.some((d) => d.text === 'Always name the owning team.' && d.priority === 5), 'directives');
  ok(p.counts.nodes === p.nodes.length && p.counts.edges === p.edges.length && p.counts.facts === p.facts.length, 'counts match the records');
  ok(!JSON.stringify(arch).includes('unit_text') && !JSON.stringify(arch).includes('"beats"'), 'nothing from the relational side (text drafts, template library)');

  console.log('# Import (multipart) and round trip');
  const fd = new FormData();
  fd.append('file', new Blob([JSON.stringify(arch)], { type: 'application/json' }), 'archive.json');
  const imp = await raw('POST', '/api/import', fd);
  ok(imp.status === 201, 'import → 201');
  const res = imp.data;
  const copy = res.projects?.[0];
  if (copy) created.projects.push({ id: copy.projectId, name: copy.name });
  ok(copy && copy.projectId !== pid && copy.sourceId === pid, 'new project with a new id');
  ok(copy.name === renamed.name + ' (2)' && res.notes?.some((n) => n.includes('already exists')), 'name clash gets a suffix and a note');
  ok(copy.context === ctx.id && res.contexts?.some((c) => c.contextId === ctx.id && c.action === 'existing'), 'existing context reused');
  for (const k of ['nodes', 'edges', 'facts', 'directives', 'nodeTypes', 'relTypes', 'objectTypes']) ok(copy.counts[k] === p.counts[k], `count ${k}: ${copy.counts[k]} = ${p.counts[k]}`);
  const back = (await call('GET', `/api/projects/${copy.projectId}/export`)).projects[0];
  compare(p, back, 'round trip');
  ok(back.nodes.every((n) => !p.nodes.some((o) => o.id === n.id)), 'every node got a new id');
  const bcore = back.nodes.find((n) => n.name === 'Core ledger');
  const bteam = back.nodes.find((n) => n.name === 'Payments squad');
  ok(bcore.refs?.owner?.[0] === bteam.id && bcore.attributes.sla.tier === 'gold', 'node_ref attributes point to the new nodes; composites kept');
  const bapi = back.nodes.find((n) => n.name === 'Public API');
  ok(bapi.refs?.depends_on?.[0] === bcore.id, 'lists of node_ref remapped');
  const detail = await call('GET', `/api/projects/${copy.projectId}/nodes/${bcore.id}`);
  ok(detail.node.refs?.owner?.[0] === bteam.id, 'the copy works through the regular API (refs)');
  const search = await call('GET', `/api/projects/${copy.projectId}/nodes?q=ledger`);
  ok(search.some((n) => n.id === bcore.id), 'search finds imported nodes');
  const facts = await call('GET', `/api/projects/${copy.projectId}/facts?minCriticality=foundational`);
  ok(facts.some((f) => f.text === 'The ledger is append-only' && f.revealAt?.id === back.nodes.find((n) => n.name === 'Phase 2').id), 'facts keep revealAt on the new unit');
  const edit = await call('POST', `/api/projects/${copy.projectId}/changesets`, { title: 'edit copy', operations: [
    { op: 'update_node', id: bcore.id, expectedVersion: 2, summary: 'edited in the copy' }] });
  ok(edit.status === 'applied', 'imported nodes accept updates with their version');
  ok((await call('GET', `/api/projects/${pid}/nodes/${id.$core}`)).node.summary === 'Book of record', 'the source is untouched');

  console.log('# Import (JSON body) with a new name');
  const named = await call('POST', `/api/import?name=${encodeURIComponent('Smoke archive named ' + stamp)}`, arch);
  created.projects.push({ id: named.projects[0].projectId, name: named.projects[0].name });
  ok(named.projects[0].name === 'Smoke archive named ' + stamp, 'name option');

  console.log('# Validation (nothing is written)');
  const before = (await call('GET', '/api/projects?all=true')).length;
  ok((await refused('POST', '/api/import', 'not json'))?.includes('JSON'), 'invalid JSON');
  ok((await refused('POST', '/api/import', { format: 'other', version: 1 }))?.includes('obliterate-archive'), 'wrong format');
  ok((await refused('POST', '/api/import', { ...arch, version: 99 }))?.includes('newer'), 'newer version');
  ok((await refused('POST', '/api/import', { format: 'obliterate-archive' }))?.includes('version'), 'missing version');
  const broken = structuredClone(arch);
  broken.projects[0].edges[0].to = 'n_missing';
  ok((await refused('POST', '/api/import', broken))?.includes('not in the archive'), 'edge to a missing node');
  const badType = structuredClone(arch);
  badType.projects[0].nodes[0].typeId = 'nt_missing';
  ok((await refused('POST', '/api/import', badType))?.includes('schema'), 'node with a type outside the schema');
  const badCrit = structuredClone(arch);
  badCrit.projects[0].nodes.at(-1).base = 'Nope';
  badCrit.projects[0].schema.nodeTypes[0].base = 'Nope';
  ok((await refused('POST', '/api/import', badCrit)) !== null, 'invalid base kind');
  ok((await call('GET', '/api/projects?all=true')).length === before, 'no project was created by the failed imports');

  console.log('# Context recreation');
  const moved = structuredClone(arch);
  const fake = 'ctx_smoke' + stamp;
  moved.contexts = moved.contexts.map((c) => (c.id === ctx.id ? { ...c, id: fake, name: 'Smoke archive recreated ' + stamp } : c));
  moved.projects[0].context = fake;
  ok((await refused('POST', '/api/import?recreateContexts=false', moved))?.includes('recreateContexts'), 'missing context without recreate is refused');
  const rec = await call('POST', '/api/import', moved);
  const newCtx = rec.contexts.find((c) => c.sourceId === fake);
  ok(newCtx?.action === 'created' && newCtx.contextId !== fake, 'missing context recreated with a new id');
  created.projects.push({ id: rec.projects[0].projectId, name: rec.projects[0].name });
  created.contexts.push({ id: newCtx.contextId, name: newCtx.name });
  const cview = await call('GET', `/api/contexts/${newCtx.contextId}`);
  ok(cview.instructions === 'Archive smoke instructions.' && cview.icon === '📦' && cview.projectCount === 1, 'recreated context keeps instructions and icon');
  const fromCtx = await call('POST', `/api/contexts/${newCtx.contextId}/projects`, { name: 'Smoke archive born ' + stamp });
  created.projects.push(fromCtx);
  const bornSchema = await call('GET', `/api/projects/${fromCtx.id}/schema`);
  ok(bornSchema.nodeTypes.length === actx.definition.schema.nodeTypes.length, 'a new project in the recreated context gets its definition');
  const again = structuredClone(moved);
  const rec2 = await call('POST', '/api/import', again);
  created.projects.push({ id: rec2.projects[0].projectId, name: rec2.projects[0].name });
  ok(rec2.contexts.find((c) => c.sourceId === fake)?.action === 'matched' && rec2.projects[0].context === newCtx.contextId, 'importing again reuses the context with the same name');

  console.log('# Export all');
  const all = await raw('GET', '/api/export');
  ok(all.status === 200 && all.data.scope === 'all' && all.data.projects.some((x) => x.id === pid) && all.data.contexts.some((c) => c.id === ctx.id && c.definition),
    'every project and custom context');
  ok(!all.data.projects.some((x) => x.id === ctx.blueprintProjectId), 'definition projects only inside their context');

  console.log('# MCP project_export / project_import');
  const mex = await tool('project_export', { projectId: pid });
  ok(mex.format === 'obliterate-archive' && mex.projects[0].counts.nodes === p.counts.nodes && mex.downloadUrl.endsWith(`/api/projects/${pid}/export`), 'summary and download URL');
  ok(typeof mex.archive === 'string' && JSON.parse(mex.archive).projects[0].id === pid, 'small archive comes inline');
  const noInline = await tool('project_export', { projectId: pid, inline: false });
  ok(!noInline.archive && noInline.bytes > 0, 'inline=false returns only the summary');
  const mim = await tool('project_import', { archive: mex.archive, name: 'Smoke archive mcp ' + stamp });
  created.projects.push({ id: mim.projects[0].projectId, name: mim.projects[0].name });
  ok(mim.projects[0].counts.nodes === p.counts.nodes && mim.projects[0].name === 'Smoke archive mcp ' + stamp, 'project_import creates the copy');
  ok((await toolError('project_import', { archive: '{"format":"x"}' }))?.includes('obliterate-archive'), 'project_import explains a wrong format');

  if (EXISTING) {
    console.log(`# Round trip of an existing project (${EXISTING})`);
    const ea = await call('GET', `/api/projects/${EXISTING}/export`);
    const ep = ea.projects[0];
    console.log(`  … ${ep.name}: ${ep.counts.nodes} nodes, ${ep.counts.edges} edges, ${ep.counts.facts} facts, ${ep.counts.directives} directives`);
    const er = await call('POST', `/api/import?name=${encodeURIComponent(ep.name + ' — smoke copy ' + stamp)}`, ea);
    created.projects.push({ id: er.projects[0].projectId, name: er.projects[0].name });
    for (const k of ['nodes', 'edges', 'facts', 'directives']) ok(er.projects[0].counts[k] === ep.counts[k], `count ${k}: ${er.projects[0].counts[k]} = ${ep.counts[k]}`);
    compare(ep, (await call('GET', `/api/projects/${er.projects[0].projectId}/export`)).projects[0], 'existing project');
  }
} catch (e) {
  console.log('  FAIL ' + e.message);
  failures++;
} finally {
  if (!KEEP) {
    console.log('# Cleanup');
    for (const p of created.projects) await call('DELETE', `/api/projects/${p.id}?confirm=${encodeURIComponent(p.name)}`).catch((e) => console.log('  ! ' + e.message));
    for (const c of created.contexts.reverse()) await call('DELETE', `/api/contexts/${c.id}?confirm=${encodeURIComponent(c.name)}`).catch((e) => console.log('  ! ' + e.message));
    ok(true, `${created.projects.length} projects and ${created.contexts.length} contexts deleted`);
  }
}
console.log(failures ? `\n${failures} FAILURE(S)` : '\nALL OK');
process.exit(failures ? 1 : 0);
