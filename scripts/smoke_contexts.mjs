// End-to-end test of contexts (REST + MCP): built-ins, a corporate project, a custom context based on corporate,
// editing the context's blueprint, a project born from it, and cleanup.
// Usage: node scripts/smoke_contexts.mjs [http://127.0.0.1:8180] [--keep]
const BASE = process.argv.find((a) => a.startsWith('http')) ?? 'http://127.0.0.1:8180';
const KEEP = process.argv.includes('--keep');
let failures = 0;
const ok = (cond, msg) => { console.log(`  ${cond ? 'ok ' : 'FAIL'} ${msg}`); if (!cond) failures++; };

async function call(method, path, body, lang = 'en') {
  const res = await fetch(BASE + path, { method, headers: { 'content-type': 'application/json', 'x-lang': lang }, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status}: ${data?.detail ?? text}`);
  return data;
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

const created = { projects: [], contexts: [] };
try {
  console.log('# Built-in contexts');
  const list = await call('GET', '/api/contexts', undefined, 'pt');
  const corporate = list.find((c) => c.id === 'corporate');
  ok(!list.some((c) => c.id === 'narrative'), 'the old narrative context is gone');
  ok(corporate && corporate.kind === 'builtin' && corporate.tabs.join() === 'actors,knowledge,analysis', 'corporate context has its own tabs');
  ok(corporate.spaceLabels?.actors === 'Pessoas e contas', 'space labels come in the request language');

  console.log('# A corporate project');
  const corp = await call('POST', '/api/contexts/corporate/projects', { name: 'Smoke corp ' + Date.now(), locale: 'en' });
  created.projects.push(corp);
  ok(corp.context === 'corporate' && corp.profile === 'corporate', 'project belongs to the corporate context');
  const schema = await call('GET', `/api/projects/${corp.id}/schema`);
  const typeNames = schema.nodeTypes.map((t) => t.name);
  for (const n of ['Client', 'Team', 'Person', 'Contract', 'Pricing', 'Product', 'Business rule', 'Integration', 'Document', 'Market insight']) {
    ok(typeNames.includes(n), `type ${n}`);
  }
  ok(schema.relTypes.some((r) => r.name === 'SERVES') && schema.relTypes.some((r) => r.name === 'HAS_PRODUCT_ENABLED'), 'relations SERVES and HAS_PRODUCT_ENABLED');
  const doc = schema.nodeTypes.find((t) => t.name === 'Document');
  ok(doc.attributes.some((a) => a.role === 'link'), 'Document has a link attribute');

  console.log('# Corporate data through propose_changes');
  const cs = await call('POST', `/api/projects/${corp.id}/changesets`, {
    title: 'seed', operations: [
      { op: 'create_node', ref: '$acme', type: 'Client', name: 'ACME', attributes: { tier: 'strategic', status: 'active' } },
      { op: 'create_node', ref: '$ana', type: 'Person', name: 'Ana BizDev', attributes: { job_title: 'BizDev manager' } },
      { op: 'create_node', ref: '$rule', type: 'Business rule', name: 'ACME settles in D+2', attributes: { rule: 'Payments for ACME settle in D+2, never D+0.', scope: 'specific_client' } },
      { op: 'create_node', ref: '$doc', type: 'Document', name: 'ACME master agreement', attributes: { link: 'https://drive.example.com/acme-msa', doc_type: 'contract' } },
      { op: 'create_edge', relType: 'SERVES', from: '$ana', to: '$acme', description: 'BizDev owner of the account' },
      { op: 'create_edge', relType: 'GOVERNED_BY', from: '$acme', to: '$rule' },
      { op: 'create_edge', relType: 'DOCUMENTED_IN', from: '$rule', to: '$doc' },
    ],
  });
  ok(cs.status === 'applied', 'client, BizDev, rule and document recorded');
  const facts = await call('GET', `/api/projects/${corp.id}/facts?minCriticality=foundational`);
  ok(facts.some((f) => f.text.includes('D+2')), 'the business rule became a foundational fact');

  console.log('# Custom context based on corporate');
  const ctx = await call('POST', '/api/contexts', { name: 'Smoke payments brain ' + Date.now(), description: 'Payments ops', icon: '💳', locale: 'en', baseContext: 'corporate', instructions: 'Always state the settlement rule.' });
  created.contexts.push(ctx);
  ok(ctx.kind === 'custom' && ctx.blueprintProjectId && ctx.baseContext === 'corporate', 'custom context with a blueprint');
  ok(ctx.tabs.join() === 'actors,knowledge,analysis', 'inherits the base tabs');
  const all = await call('GET', '/api/projects');
  ok(!all.some((p) => p.id === ctx.blueprintProjectId), 'blueprint is hidden from the project list');

  await call('POST', `/api/projects/${ctx.blueprintProjectId}/changesets`, {
    title: 'define', operations: [
      { op: 'upsert_node_type', definition: { name: 'Settlement window', base: 'Concept', attributes: [{ key: 'days', dataType: 'int' }] } },
      { op: 'upsert_directive', text: 'Quote the settlement window of the client.', definition: { when: ['context'], priority: 5 } },
    ],
  });
  const updated = await call('PATCH', `/api/contexts/${ctx.id}`, { spaceLabels: { knowledge: 'Rules & money', world: 'legacy name' } });
  ok(updated.spaceLabels.knowledge === 'Rules & money', 'context settings updated (a legacy space name does not override the current one)');

  const p2 = await call('POST', `/api/contexts/${ctx.id}/projects`, { name: 'Smoke payments project ' + Date.now() });
  created.projects.push(p2);
  const s2 = await call('GET', `/api/projects/${p2.id}/schema`);
  ok(p2.context === ctx.id, 'project belongs to the custom context');
  ok(s2.nodeTypes.some((t) => t.name === 'Settlement window') && s2.nodeTypes.some((t) => t.name === 'Client'), 'project got the blueprint types (own + inherited)');
  const dirs = await call('GET', `/api/projects/${p2.id}/directives`);
  ok(dirs.some((d) => d.text.includes('settlement window')), 'project got the blueprint directive');
  const counted = (await call('GET', `/api/contexts/${ctx.id}`)).projectCount;
  ok(counted === 1, 'context counts its projects');

  console.log('# MCP');
  await mcp('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'smoke', version: '1' } });
  await fetch(BASE + '/mcp', { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', 'mcp-session-id': mcpSession },
    body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) });
  const tools = (await mcp('tools/list', {})).result.tools.map((t) => t.name);
  ok(['context_list', 'context_get', 'context_create', 'context_update'].every((t) => tools.includes(t)), 'context tools exposed');
  const mlist = await tool('context_list', {});
  ok(mlist.some((c) => c.id === ctx.id), 'context_list sees the custom context');
  const byCtx = await tool('project_list', { contextId: ctx.id });
  ok(byCtx.length === 1 && byCtx[0].id === p2.id, 'project_list filters by context');
  const brief = await tool('session_begin', { projectId: p2.id, goal: 'smoke' });
  ok(brief.context?.id === ctx.id, 'briefing carries the context');
  ok(brief.guidance.some((g) => g.includes('Always state the settlement rule')), 'briefing carries the context instructions');
  const mp = await tool('project_create', { name: 'Smoke mcp corp ' + Date.now(), contextId: 'corporate' });
  created.projects.push(mp);
  ok(mp.context === 'corporate', 'project_create with contextId');

  console.log('# Guard rails');
  let refused = false;
  try { await call('PATCH', '/api/contexts/corporate', { name: 'x' }); } catch { refused = true; }
  ok(refused, 'built-in contexts are read-only');
  refused = false;
  try { await call('DELETE', `/api/contexts/${ctx.id}?confirm=${encodeURIComponent(ctx.name)}`); } catch { refused = true; }
  ok(refused, 'a context with projects cannot be deleted');
} catch (e) {
  console.log('  FAIL ' + e.message);
  failures++;
} finally {
  if (!KEEP) {
    console.log('# Cleanup');
    for (const p of created.projects) await call('DELETE', `/api/projects/${p.id}?confirm=${encodeURIComponent(p.name)}`).catch((e) => console.log('  ! ' + e.message));
    for (const c of created.contexts) await call('DELETE', `/api/contexts/${c.id}?confirm=${encodeURIComponent(c.name)}`).catch((e) => console.log('  ! ' + e.message));
    ok(true, `${created.projects.length} projects and ${created.contexts.length} context deleted`);
  }
}
console.log(failures ? `\n${failures} FAILURE(S)` : '\nALL OK');
process.exit(failures ? 1 : 0);
