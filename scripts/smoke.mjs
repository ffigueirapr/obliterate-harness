// Teste de fumaça ponta a ponta contra a API rodando (node scripts/smoke.mjs [baseUrl] [--manter]).
// Cria um contexto em branco e um projeto nele, monta schema, nós, arestas e fatos, confere as consultas principais,
// as travas (pares permitidos, tipos, versões, lote atômico, dryRun, best_effort), diretivas, clone com tradução,
// e exercita o MCP (initialize, tools/list, tools/call, resources, prompts). Também confere que a camada narrativa
// antiga não existe mais. Apaga tudo no fim (a menos que --manter).
const BASE = process.argv.find((a) => a.startsWith('http')) ?? 'http://127.0.0.1:8180';
const KEEP = process.argv.includes('--manter');
let failures = 0;

function check(cond, msg) {
  if (cond) console.log('  ok  ' + msg);
  else { failures++; console.log('  FALHOU  ' + msg); }
}

async function api(method, path, body, expectStatus) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'content-type': 'application/json', 'x-lang': 'pt' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (expectStatus !== undefined) {
    if (res.status !== expectStatus) throw new Error(`${method} ${path}: esperado ${expectStatus}, veio ${res.status}: ${text}`);
  } else if (!res.ok) {
    throw new Error(`${method} ${path} -> ${res.status}: ${text}`);
  }
  return data;
}

const ops = (pid, title, operations, mode) => api('POST', `/api/projects/${pid}/changesets`, { title, operations, mode });
const stamp = new Date().toISOString();
const created = { projects: [], contexts: [] };

console.log('# Contexto e projeto');
const ctx = await api('POST', '/api/contexts', { name: 'Smoke ' + stamp, description: 'contexto descartável', locale: 'pt' }, 201);
created.contexts.push(ctx);
check(ctx.id.startsWith('ctx_') && ctx.blueprintProjectId && ctx.kind === 'custom', 'contexto em branco criado ' + ctx.id);
check(JSON.stringify(ctx.tabs) === JSON.stringify(['actors', 'knowledge', 'analysis']), 'abas com os espaços novos (actors, knowledge, analysis)');
const noCtx = await api('POST', '/api/projects', { name: 'Sem contexto', locale: 'pt' }, 400);
check(noCtx.detail.includes('context'), 'projeto sem contexto é recusado');
const project = await api('POST', `/api/contexts/${ctx.id}/projects`, { name: 'Smoke ' + stamp, locale: 'pt' }, 201);
created.projects.push(project);
const pid = project.id;
check(pid.startsWith('p_') && project.context === ctx.id, 'projeto criado no contexto ' + pid);

console.log('# Schema e dados');
const schema = await ops(pid, 'schema', [
  { op: 'upsert_object_type', definition: { name: 'Endereço', fields: [{ key: 'rua', dataType: 'string_short', required: true }, { key: 'cidade', dataType: 'string_short' }] } },
  { op: 'upsert_node_type', definition: { name: 'Área', base: 'Agent', attributes: [{ key: 'canal', dataType: 'string_short' }] } },
  { op: 'upsert_node_type', definition: { name: 'Sistema', base: 'Object', attributes: [
    { key: 'status', dataType: 'enum', options: ['planejado', 'ativo', 'desligado'] },
    { key: 'dono', dataType: 'node_ref', refType: 'Área' },
    { key: 'limite', dataType: 'string_short' },
    { key: 'sede', dataType: 'composite', objectType: 'Endereço' },
    { key: 'apelidos', dataType: 'list', itemType: 'string_short' },
    { key: 'versao', dataType: 'int' },
    { key: 'docs', dataType: 'string_short', role: 'link' },
  ] } },
  { op: 'upsert_node_type', definition: { name: 'Documento', base: 'Media' } },
  { op: 'upsert_rel_type', definition: { name: 'DEPENDE_DE', base: 'CAUSAL', allowed: [{ from: 'Sistema', to: 'Sistema' }] } },
  { op: 'upsert_rel_type', definition: { name: 'ATENDE', base: 'SOCIAL', allowed: [{ from: '@Agent', to: 'Sistema' }] } },
  { op: 'upsert_rel_type', definition: { name: 'DOCUMENTADO_EM', base: 'REFERENCE', allowed: [{ from: '*', to: 'Documento' }] } },
]);
check(schema.status === 'applied' && schema.changes.length === 7, 'schema aplicado (7 ops)');

const data = await ops(pid, 'dados', [
  { op: 'create_node', ref: '$fin', type: 'Área', name: 'Financeiro', space: 'characters' },
  { op: 'create_node', ref: '$billing', type: 'Sistema', name: 'Billing', summary: 'Cobra os clientes todo mês', tags: ['Core'],
    attributes: { status: 'ativo', dono: '$fin', limite: 'Fecha o mês no dia 3', sede: { rua: 'Av. Paulista', cidade: 'SP' }, apelidos: ['BL'], versao: 4,
      docs: 'https://exemplo.com/billing' },
    attributeCriticality: { limite: 'foundational' } },
  { op: 'create_node', ref: '$ledger', type: 'Sistema', name: 'Ledger', summary: 'Livro razão' },
  { op: 'create_node', ref: '$doc', type: 'Documento', name: 'Política de cobrança', space: 'world' },
  { op: 'create_edge', relType: 'DEPENDE_DE', from: '$billing', to: '$ledger', criticality: 'critical', description: 'precisa do razão para fechar o mês' },
  { op: 'create_edge', relType: 'ATENDE', from: '$fin', to: '$billing' },
  { op: 'create_edge', relType: 'DOCUMENTADO_EM', from: '$billing', to: '$doc' },
  { op: 'create_fact', text: 'Estornos acima de 5 mil exigem duas aprovações', rigidity: 'law', criticality: 'foundational', about: ['$billing'] },
]);
check(data.status === 'applied', 'dados aplicados');
const id = data.ids;

const billing = await api('GET', `/api/projects/${pid}/nodes/${id.$billing}`);
check(billing.node.refs.dono[0] === id.$fin, 'node_ref dono virou aresta');
check(billing.node.tags[0] === 'core', 'tags normalizadas');
check(billing.node.space === 'knowledge', 'espaço padrão do tipo-base (knowledge)');
check(billing.facts.some((f) => f.criticality === 'foundational' && f.text.includes('dia 3')), 'atributo foundational espelhado em Fact');
check(billing.facts.some((f) => f.text.includes('DEPENDE_DE')), 'aresta critical espelhada em Fact');
const fin = await api('GET', `/api/projects/${pid}/nodes/${id.$fin}`);
const doc = await api('GET', `/api/projects/${pid}/nodes/${id.$doc}`);
check(fin.node.space === 'actors' && doc.node.space === 'knowledge', 'nomes antigos de espaço aceitos como apelido (characters → actors, world → knowledge)');

const search = await api('GET', `/api/projects/${pid}/nodes?q=cobra`);
check(search.some((n) => n.id === id.$billing), 'busca full-text encontra Billing por "cobra"');
const view = await api('POST', `/api/projects/${pid}/graph/view`, { types: ['Área', 'Sistema'], includeIndirect: true, maxHops: 3 });
check(view.nodes.length === 3, 'graph/view filtra por tipo (3 nós)');
check(view.indirect.some((l) => [l.from, l.to].includes(id.$fin) && [l.from, l.to].includes(id.$ledger)), 'ligação indireta Financeiro ~ Ledger (via Billing)');
const path = await api('GET', `/api/projects/${pid}/graph/path?from=${id.$fin}&to=${id.$ledger}`);
check(path.nodes.length === 3, 'graph/path Financeiro → Billing → Ledger');
const focus = await api('GET', `/api/projects/${pid}/graph/focus?nodeId=${id.$billing}&hops=1`);
check(focus.nodes.length === 4 && focus.distance[id.$ledger] === 1, 'graph/focus diretos (4 nós)');

console.log('# Travas');
const bad1 = await api('POST', `/api/projects/${pid}/changesets`, { operations: [{ op: 'create_edge', relType: 'DEPENDE_DE', from: id.$fin, to: id.$billing }] }, 400);
check(bad1.detail.includes('não permitida') && bad1.detail.includes('ATENDE'), 'rejeita par não permitido e sugere a relação que serve');
const bad2 = await api('POST', `/api/projects/${pid}/changesets`, { operations: [{ op: 'create_node', type: 'Sistema', name: 'X', attributes: { versao: 'velho' } }] }, 400);
check(bad2.detail.includes('inteiro'), 'rejeita atributo com tipo errado');
const bad3 = await api('POST', `/api/projects/${pid}/changesets`, { operations: [{ op: 'update_node', id: id.$billing, expectedVersion: 99, summary: 'x' }] }, 409);
check(bad3.detail.includes('Conflito'), 'conflito de versão');
const bad4 = await api('POST', `/api/projects/${pid}/changesets`, { operations: [
  { op: 'create_node', ref: '$z', type: 'Sistema', name: 'Temporário' },
  { op: 'create_edge', relType: 'DEPENDE_DE', from: id.$fin, to: '$z' },
] }, 400);
const after4 = await api('GET', `/api/projects/${pid}/nodes?q=Temporário`);
check(bad4.detail && after4.length === 0, 'lote atômico: nada gravado quando uma op falha');
const bad5 = await api('POST', `/api/projects/${pid}/changesets`, { operations: [{ op: 'upsert_node_type', definition: { name: 'Capítulo', base: 'Unit' } }] }, 400);
check(bad5.detail.includes('motor narrativo'), 'tipo-base narrativo Unit recusado fora do contexto narrativo');
const bad6 = await api('POST', `/api/projects/${pid}/changesets`, { operations: [{ op: 'upsert_node_type', definition: { name: 'Prazo', base: 'Concept', attributes: [{ key: 'quando', dataType: 'story_time' }] } }] }, 400);
check(bad6.detail.includes('motor narrativo'), 'dataType story_time recusado fora do contexto narrativo');

const dry = await api('POST', `/api/projects/${pid}/changesets`, { mode: 'dry_run', operations: [
  { op: 'create_node', ref: '$x', type: 'Sistema', name: 'Dry' },
  { op: 'create_edge', relType: 'DEPENDE_DE', from: id.$fin, to: id.$billing },
  { op: 'create_node', type: 'Inexistente', name: 'ruim' },
] });
check(dry.status === 'dry_run' && (dry.errors ?? []).length === 2, `dryRun lista todos os erros (${(dry.errors ?? []).length})`);
check(!(await api('GET', `/api/projects/${pid}/nodes?q=Dry`)).some((n) => n.name === 'Dry'), 'dryRun não grava nada');
const be = await ops(pid, 'best effort', [
  { op: 'create_node', ref: '$y', type: 'Sistema', name: 'CRM' },
  { op: 'create_node', type: 'Inexistente', name: 'ruim' },
], 'best_effort');
check(be.ids?.$y && (be.errors ?? []).length === 1, 'best_effort aplica as válidas e reporta o resto');

const upd = await ops(pid, 'tirar foundational', [{ op: 'update_node', id: id.$billing, expectedVersion: 1, attributeCriticality: { limite: 'normal' }, attributes: { versao: 5 } }]);
const billing2 = await api('GET', `/api/projects/${pid}/nodes/${id.$billing}`);
check(upd.status === 'applied' && billing2.node.version === 2 && billing2.node.attributes.versao === 5, 'update com versão');
check(!billing2.facts.some((f) => f.text.includes('dia 3')), 'fato espelhado removido ao voltar para normal');
const audit = await api('GET', `/api/projects/${pid}/audit?entityId=${id.$billing}`);
check(audit.length === 2 && audit[0].before.attributes.versao === 4, 'auditoria com antes/depois');

console.log('# Diretivas');
const d = await ops(pid, 'diretiva', [{ op: 'upsert_directive', text: 'Cite sempre o documento.', definition: { when: ['context'], types: ['Sistema'] } }]);
const dirId = d.changes[0].id;
await ops(pid, 'editar diretiva', [{ op: 'upsert_directive', id: dirId, text: 'Cite sempre o documento de origem.', definition: { when: ['context'], types: ['Sistema'], enabled: false } }]);
const dirs = (await api('GET', `/api/projects/${pid}/directives`)).filter((x) => x.source === 'project');
check(dirs.length === 1 && dirs[0].text === 'Cite sempre o documento de origem.' && dirs[0].enabled === false, 'diretiva editada pelo id e desligada');

console.log('# Motor narrativo só no contexto narrativo');
// the endpoints exist (they serve narrative projects); on this project they simply have nothing to show
for (const [path, label] of [[`/api/projects/${pid}/outline`, 'outline'], ['/api/harness/templates', 'templates'], [`/api/projects/${pid}/threads`, 'threads']]) {
  const r = await fetch(BASE + path);
  check(r.status === 200, `endpoint ${label} responde (200)`);
}

console.log('# MCP');
async function mcp(sessionId, method, params, idn) {
  const res = await fetch(BASE + '/mcp', {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', ...(sessionId ? { 'mcp-session-id': sessionId } : {}) },
    body: JSON.stringify({ jsonrpc: '2.0', id: idn, method, params }),
  });
  const sid = res.headers.get('mcp-session-id') ?? sessionId;
  const text = await res.text();
  const dataLine = text.split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5)).join('');
  return { sid, msg: JSON.parse(dataLine || text) };
}
let rpc = 1;
const init = await mcp(null, 'initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'smoke', version: '1' } }, rpc++);
const instr = init.msg.result?.instructions ?? '';
check(instr.includes('session_begin') && instr.includes('narrative'), 'instructions do harness citam o contexto narrativo');
await fetch(BASE + '/mcp', { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', 'mcp-session-id': init.sid },
  body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) });
const sid = init.sid;
const tool = async (name, args) => {
  const r = await mcp(sid, 'tools/call', { name, arguments: args }, rpc++);
  const text = r.msg.result?.content?.[0]?.text ?? '';
  let body = null;
  try { body = JSON.parse(text); } catch { /* error text */ }
  return { isError: r.msg.result?.isError === true, text, body };
};
const tools = (await mcp(sid, 'tools/list', {}, rpc++)).msg.result.tools;
const names = tools.map((t) => t.name);
const narrativeTools = ['unit_text_set', 'unit_text_append', 'unit_text_get', 'unit_text_history', 'unit_text_diff', 'unit_cast_set', 'unit_validate',
  'manuscript_export', 'writing_context', 'state_at', 'story_outline', 'open_threads', 'milestone_coverage', 'template_list', 'template_apply',
  'template_derive', 'template_upsert', 'template_delete', 'profile_list', 'profile_apply', 'project_settings'];
check(names.includes('propose_changes') && names.includes('context_create') && names.includes('edge_get'), `tools/list: ${names.length} tools`);
check(narrativeTools.every((t) => names.includes(t)), 'tools narrativas no MCP (para o contexto narrativo)');
const schemaJson = JSON.stringify(tools.find((t) => t.name === 'propose_changes').inputSchema);
check(schemaJson.includes('expectedVersion') && /fromUnit/.test(schemaJson), 'schema de propose_changes com os campos narrativos opcionais');

const begin = await tool('session_begin', { projectId: pid, goal: 'smoke test' });
const briefing = begin.body;
check(briefing.sessionId && briefing.foundationalFacts.length >= 1 && briefing.nodeTypes.length === 3, 'session_begin devolve briefing');
check(!('outline' in briefing) && !('openThreads' in briefing) && !('words' in (briefing.stats ?? {})), 'briefing sem estrutura narrativa, ganchos ou palavras');
check(briefing.guidance.some((g) => g.includes('Contexto: Smoke')), 'briefing traz o contexto do projeto');
const s = briefing.sessionId;
const noVersion = await tool('propose_changes', { projectId: pid, title: 'llm', sessionId: s, operations: [{ op: 'update_node', id: id.$ledger, summary: 'sem versão' }] });
check(noVersion.isError && noVersion.text.includes('expectedVersion'), 'LLM sem expectedVersion é barrada');
const p1 = await tool('propose_changes', { projectId: pid, title: 'a', sessionId: s, operations: [{ op: 'create_node', ref: '$erp', type: 'Sistema', name: 'ERP' }] });
const p2 = await tool('propose_changes', { projectId: pid, title: 'b', sessionId: s, operations: [{ op: 'create_edge', ref: '$e', relType: 'DEPENDE_DE', from: '$erp', to: id.$ledger }] });
check(!p2.isError && p2.body.status === 'applied', 'apelido $ da sessão reaproveitado num lote seguinte');
const eg = await tool('edge_get', { projectId: pid, ids: [p2.body.ids.$e] });
check(!eg.isError && eg.text.includes(p2.body.ids.$e) && eg.text.includes('"version"'), 'edge_get devolve a aresta com a versão');
const dr = await tool('propose_changes', { projectId: pid, title: 'dry', dryRun: true, operations: [{ op: 'create_node', type: 'Inexistente', name: 'z' }, { op: 'create_node', type: 'Sistema', name: 'zz' }] });
check(dr.body?.status === 'dry_run' && dr.body.errors.length === 1, 'dryRun via MCP');
const gf = await tool('graph_focus', { projectId: pid, nodeId: id.$ledger, allConnected: true });
check(!gf.isError && gf.body.nodes.length >= 5, 'graph_focus todos ligados via MCP');
await tool('session_checkpoint', { projectId: pid, sessionId: s, summary: 'criei o ERP', todos: ['documentar o ERP'] });
const resume = await tool('session_resume', { projectId: pid });
check(resume.body.lastCheckpoint.summary === 'criei o ERP' && resume.body.sessionRefs?.$erp === p1.body.ids.$erp, 'session_resume traz checkpoint e apelidos');
const noCtxTool = await tool('project_create', { name: 'x' });
check(noCtxTool.isError && noCtxTool.text.includes('contextId'), 'project_create exige contextId');

const res = await mcp(sid, 'resources/read', { uri: 'harness://manual' }, rpc++);
const manual = res.msg.result.contents[0].text;
check(manual.includes('## Contexts') && manual.includes('## Narrative context'), 'manual (en) com contextos e a seção do contexto narrativo');
const resPt = await mcp(sid, 'resources/read', { uri: 'harness://manual/pt' }, rpc++);
check(resPt.msg.result.contents[0].text.includes('## Contextos'), 'manual (pt)');
const prompts = (await mcp(sid, 'prompts/list', {}, rpc++)).msg.result.prompts.map((p) => p.name).sort();
check(JSON.stringify(prompts) === JSON.stringify(['answer_from_graph', 'build_world', 'design_context', 'ingest_document', 'start_project', 'write_unit']), 'prompts genéricos e narrativos: ' + prompts.join(', '));

console.log('# Clone com tradução (contexto corporativo)');
const corp = await api('POST', '/api/contexts/corporate/projects', { name: 'Smoke corp ' + stamp, locale: 'pt' }, 201);
created.projects.push(corp);
const team = await ops(corp.id, 'equipe', [{ op: 'create_node', ref: '$t', type: 'Equipe', name: 'Time de Dados', attributes: { area: 'engenharia' } }]);
const cl = await api('POST', `/api/projects/${corp.id}/clone`, { newName: 'Smoke corp en ' + stamp, translateTo: 'en' }, 201);
created.projects.push({ id: cl.projectId, name: cl.name });
const cs = await api('GET', `/api/projects/${cl.projectId}/schema`);
check(cs.nodeTypes.some((t) => t.name === 'Team') && !cs.relTypes.some((r) => r.name === 'CONTAINS'), 'clone traduzido (Equipe → Team), sem relação CONTAINS');
const teamEn = await api('GET', `/api/projects/${cl.projectId}/nodes/${cl.idMap[team.ids.$t]}`);
check(teamEn.node.type === 'Team' && teamEn.node.attributes.area === 'engineering', 'nó migrado (engenharia → engineering)');

console.log('# Limpeza');
if (KEEP) console.log('  (mantidos: ' + created.projects.map((p) => p.id).join(', ') + ', contexto ' + ctx.id + ')');
else {
  const wrong = await api('DELETE', `/api/projects/${pid}?confirm=nome-errado`, undefined, 400);
  check(wrong.detail.includes('não confere'), 'exclusão exige o nome exato');
  for (const p of created.projects) await api('DELETE', `/api/projects/${p.id}?confirm=${encodeURIComponent(p.name)}`, undefined, 204);
  for (const c of created.contexts) await api('DELETE', `/api/contexts/${c.id}?confirm=${encodeURIComponent(c.name)}`, undefined, 204);
  const left = await api('GET', '/api/projects?all=true');
  check(!left.some((p) => created.projects.some((c) => c.id === p.id) || p.id === ctx.blueprintProjectId), 'projetos e contexto de teste excluídos');
}

console.log(failures === 0 ? '\nTUDO OK' : `\n${failures} FALHA(S)`);
process.exit(failures === 0 ? 0 : 1);
