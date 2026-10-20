// Teste de fumaça ponta a ponta contra a API rodando (node scripts/smoke.mjs [baseUrl]).
// Cria um projeto descartável, monta schema, nós, estrutura narrativa, arestas temporais e fatos,
// e confere as consultas principais. Também exercita o MCP (initialize, tools/list, tools/call).
const BASE = process.argv[2] ?? 'http://127.0.0.1:8180';
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

const ops = (pid, title, operations) => api('POST', `/api/projects/${pid}/changesets`, { title, operations });

console.log('# REST');
const project = await api('POST', '/api/projects', { name: 'Smoke ' + new Date().toISOString(), profile: 'personalizado', locale: 'pt' }, 201); // sem pacote de perfil: o teste monta o próprio schema
const pid = project.id;
check(pid.startsWith('p_'), 'projeto criado ' + pid);

const schema = await ops(pid, 'schema', [
  { op: 'upsert_object_type', definition: { name: 'Ficha', fields: [{ key: 'forca', dataType: 'int', required: true }, { key: 'agilidade', dataType: 'int' }] } },
  { op: 'upsert_node_type', definition: { name: 'Região', base: 'Place', attributes: [{ key: 'clima', dataType: 'enum', options: ['deserto', 'costa', 'montanha'] }] } },
  { op: 'upsert_node_type', definition: { name: 'Personagem', base: 'Agent', attributes: [
    { key: 'idade', dataType: 'int' },
    { key: 'origem', dataType: 'node_ref', refType: 'Região' },
    { key: 'poder', dataType: 'string_short' },
    { key: 'ficha', dataType: 'composite', objectType: 'Ficha' },
    { key: 'apelidos', dataType: 'list', itemType: 'string_short' },
  ] } },
  { op: 'upsert_node_type', definition: { name: 'Item', base: 'Object' } },
  { op: 'upsert_node_type', definition: { name: 'Arco', base: 'Unit' } },
  { op: 'upsert_node_type', definition: { name: 'Capítulo', base: 'Unit', attributes: [{ key: 'status', dataType: 'enum', options: ['rascunho', 'pronto'] }] } },
  { op: 'upsert_rel_type', definition: { name: 'ODEIA', base: 'SOCIAL', allowed: [{ from: '@Agent', to: '@Agent' }] } },
  { op: 'upsert_rel_type', definition: { name: 'POSSUI', base: 'POSSESSION', allowed: [{ from: 'Personagem', to: 'Item' }] } },
  { op: 'upsert_rel_type', definition: { name: 'APARECE_EM', base: 'PARTICIPATION', allowed: [{ from: '*', to: '@Unit' }] } },
]);
check(schema.status === 'applied' && schema.changes.length === 9, 'schema aplicado (9 ops)');

const world = await ops(pid, 'mundo e personagens', [
  { op: 'create_node', ref: '$deserto', type: 'Região', name: 'Mar de Areia', attributes: { clima: 'deserto' } },
  { op: 'create_node', ref: '$arco1', type: 'Arco', name: 'Arco 1' },
  { op: 'create_node', ref: '$c1', type: 'Capítulo', name: 'Capítulo 1', parent: '$arco1', attributes: { status: 'pronto' } },
  { op: 'create_node', ref: '$c2', type: 'Capítulo', name: 'Capítulo 2', parent: '$arco1' },
  { op: 'create_node', ref: '$c3', type: 'Capítulo', name: 'Capítulo 3', parent: '$arco1' },
  { op: 'create_node', ref: '$ana', type: 'Personagem', name: 'Ana', summary: 'Ladra do deserto', tags: ['Protagonista'],
    attributes: { idade: 27, origem: '$deserto', poder: 'Some nas sombras, só à noite', ficha: { forca: 3, agilidade: 9 }, apelidos: ['Sombra'] },
    attributeCriticality: { poder: 'foundational' } },
  { op: 'create_node', ref: '$bruno', type: 'Personagem', name: 'Bruno', summary: 'Irmão de Ana' },
  { op: 'create_node', ref: '$chave', type: 'Item', name: 'Chave de Obsidiana' },
  { op: 'create_edge', relType: 'ODEIA', from: '$ana', to: '$bruno', criticality: 'critical', description: 'culpa o irmão pela morte do pai', fromUnit: '$c1' },
  { op: 'create_edge', ref: '$posse', relType: 'POSSUI', from: '$ana', to: '$chave', fromUnit: '$c1', toUnit: '$c2' },
  { op: 'create_edge', relType: 'APARECE_EM', from: '$ana', to: '$c3' },
  { op: 'create_fact', text: 'Magia não funciona dentro de templos', rigidity: 'law', criticality: 'foundational', about: ['$deserto'] },
]);
check(world.status === 'applied', 'mundo aplicado');
const id = world.ids;

const ana = await api('GET', `/api/projects/${pid}/nodes/${id.$ana}`);
check(ana.node.refs.origem[0] === id.$deserto, 'node_ref origem virou aresta');
check(ana.node.tags[0] === 'protagonista', 'tags normalizadas');
check(ana.facts.some(f => f.criticality === 'foundational' && f.text.includes('só à noite')), 'atributo foundational espelhado em Fact');
check(ana.facts.some(f => f.text.includes('ODEIA')), 'aresta critical espelhada em Fact');

const st3 = await api('GET', `/api/projects/${pid}/state-at?entity=${id.$ana}&unit=${id.$c3}`);
check(st3.ended.some(e => e.relType === 'POSSUI'), 'state_at cap3: posse da chave já terminou');
const st2 = await api('GET', `/api/projects/${pid}/state-at?entity=${id.$ana}&unit=${id.$c2}`);
check(st2.active.some(e => e.relType === 'POSSUI'), 'state_at cap2: posse ativa');

const outline = await api('GET', `/api/projects/${pid}/outline`);
check(outline.length === 1 && outline[0].children.length === 3 && outline[0].children[0].status === 'pronto', 'outline arco > 3 capítulos');

const search = await api('GET', `/api/projects/${pid}/nodes?q=ladra`);
check(search.some(n => n.id === id.$ana), 'busca full-text encontra Ana por "ladra"');

const view = await api('POST', `/api/projects/${pid}/graph/view`, { types: ['Personagem', 'Região', 'Item'], includeIndirect: true, maxHops: 3 });
check(view.nodes.length === 4, 'graph/view filtra por tipo (4 nós)');
check(view.indirect.some(l => [l.from, l.to].includes(id.$bruno) && [l.from, l.to].includes(id.$deserto)), 'ligação indireta Bruno ~ Mar de Areia (via Ana)');

const path = await api('GET', `/api/projects/${pid}/graph/path?from=${id.$bruno}&to=${id.$chave}`);
check(path.nodes.length === 3, 'graph/path Bruno → Ana → Chave');

// guardrails
const bad1 = await api('POST', `/api/projects/${pid}/changesets`, { operations: [{ op: 'create_edge', relType: 'POSSUI', from: id.$chave, to: id.$ana }] }, 400);
check(bad1.detail.includes('não permitida'), 'rejeita relação fora dos pares permitidos');
const bad2 = await api('POST', `/api/projects/${pid}/changesets`, { operations: [{ op: 'create_node', type: 'Personagem', name: 'X', attributes: { idade: 'velho' } }] }, 400);
check(bad2.detail.includes('inteiro'), 'rejeita atributo com tipo errado');
const bad3 = await api('POST', `/api/projects/${pid}/changesets`, { operations: [{ op: 'update_node', id: id.$ana, expectedVersion: 99, summary: 'x' }] }, 409);
check(bad3.detail.includes('Conflito'), 'conflito de versão');
const bad4 = await api('POST', `/api/projects/${pid}/changesets`, { operations: [
  { op: 'create_node', ref: '$z', type: 'Item', name: 'Temporário' },
  { op: 'create_edge', relType: 'POSSUI', from: id.$chave, to: '$z' },
] }, 400);
const after4 = await api('GET', `/api/projects/${pid}/nodes?q=Temporário`);
check(bad4.detail && after4.length === 0, 'lote atômico: nada gravado quando uma op falha');

const upd = await ops(pid, 'tirar foundational', [{ op: 'update_node', id: id.$ana, expectedVersion: 1, attributeCriticality: { poder: 'normal' }, attributes: { idade: 28 } }]);
const ana2 = await api('GET', `/api/projects/${pid}/nodes/${id.$ana}`);
check(upd.status === 'applied' && ana2.node.version === 2 && ana2.node.attributes.idade === 28, 'update com versão');
check(!ana2.facts.some(f => f.text.includes('só à noite')), 'fato espelhado removido ao voltar para normal');

const audit = await api('GET', `/api/projects/${pid}/audit?entityId=${id.$ana}`);
check(audit.length === 2 && audit[0].before.attributes.idade === 27, 'auditoria com antes/depois');

console.log('# MCP');
async function mcp(sessionId, method, params, idn) {
  const res = await fetch(BASE + '/mcp', {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', ...(sessionId ? { 'mcp-session-id': sessionId } : {}) },
    body: JSON.stringify({ jsonrpc: '2.0', id: idn, method, params }),
  });
  const sid = res.headers.get('mcp-session-id') ?? sessionId;
  const text = await res.text();
  const dataLine = text.split('\n').filter(l => l.startsWith('data:')).map(l => l.slice(5)).join('');
  const msg = JSON.parse(dataLine || text);
  return { sid, msg };
}
const init = await mcp(null, 'initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'smoke', version: '1' } }, 1);
check(init.msg.result?.instructions?.includes('session_begin'), 'initialize devolve instructions do harness');
await fetch(BASE + '/mcp', { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', 'mcp-session-id': init.sid },
  body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) });
const tools = await mcp(init.sid, 'tools/list', {}, 2);
const names = tools.msg.result.tools.map(t => t.name);
check(names.includes('propose_changes') && names.length >= 23, 'tools/list: ' + names.length + ' tools');
const propose = tools.msg.result.tools.find(t => t.name === 'propose_changes');
check(JSON.stringify(propose.inputSchema).includes('expectedVersion'), 'schema de propose_changes descreve Operation');

const begin = await mcp(init.sid, 'tools/call', { name: 'session_begin', arguments: { projectId: pid, goal: 'smoke test' } }, 3);
const briefing = JSON.parse(begin.msg.result.content[0].text);
check(briefing.sessionId && briefing.foundationalFacts.length >= 1 && briefing.nodeTypes.length === 5, 'session_begin devolve briefing');

const llmWrite = await mcp(init.sid, 'tools/call', { name: 'propose_changes', arguments: { projectId: pid, title: 'llm', sessionId: briefing.sessionId,
  operations: [{ op: 'update_node', id: id.$bruno, summary: 'sem versão' }] } }, 4);
check(llmWrite.msg.result?.isError === true && JSON.stringify(llmWrite.msg.result).includes('expectedVersion'), 'LLM sem expectedVersion é barrada');

const llmOk = await mcp(init.sid, 'tools/call', { name: 'propose_changes', arguments: { projectId: pid, title: 'llm cria item', sessionId: briefing.sessionId,
  operations: [{ op: 'create_node', ref: '$adaga', type: 'Item', name: 'Adaga' }, { op: 'create_edge', relType: 'POSSUI', from: id.$bruno, to: '$adaga', fromUnit: id.$c2 }] } }, 5);
const llmRes = JSON.parse(llmOk.msg.result.content[0].text);
check(llmRes.status === 'applied' && llmRes.ids.$adaga, 'LLM grava via propose_changes');

const cp = await mcp(init.sid, 'tools/call', { name: 'session_checkpoint', arguments: { projectId: pid, sessionId: briefing.sessionId, summary: 'criei a adaga', todos: ['capítulo 4'] } }, 6);
check(!cp.msg.result.isError, 'session_checkpoint');
const resume = await mcp(init.sid, 'tools/call', { name: 'session_resume', arguments: { projectId: pid } }, 7);
check(JSON.parse(resume.msg.result.content[0].text).lastCheckpoint.summary === 'criei a adaga', 'session_resume traz último checkpoint');

const res = await mcp(init.sid, 'resources/read', { uri: 'harness://manual' }, 8);
check(res.msg.result.contents[0].text.includes('Narrative time'), 'resource harness://manual (en)');
const resPt = await mcp(init.sid, 'resources/read', { uri: 'harness://manual/pt' }, 10);
check(resPt.msg.result.contents[0].text.includes('Tempo narrativo'), 'resource harness://manual/pt');
const prompts = await mcp(init.sid, 'prompts/list', {}, 9);
check(prompts.msg.result.prompts.length === 3, 'prompts/list: 3 prompts');

console.log('# Limpeza');
const wrong = await api('DELETE', `/api/projects/${pid}?confirm=nome-errado`, undefined, 400);
check(wrong.detail.includes('não confere'), 'exclusão exige o nome exato');
await api('DELETE', `/api/projects/${pid}?confirm=${encodeURIComponent(project.name)}`, undefined, 204);
const gone = await api('GET', `/api/projects/${pid}/audit`, undefined, 200);
const stillThere = (await api('GET', '/api/projects')).some((p) => p.id === pid);
check(!stillThere && gone.length === 0, 'projeto de teste excluído (grafo e histórico)');

console.log(failures === 0 ? '\nTUDO OK' : `\n${failures} FALHA(S)`);
process.exit(failures === 0 ? 0 : 1);
