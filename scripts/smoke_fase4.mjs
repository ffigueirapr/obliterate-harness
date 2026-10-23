// Teste da fase 4: perfis, templates, cobertura de beats, ganchos, diretivas e writing_context.
// Uso: node scripts/smoke_fase4.mjs [baseUrl]  (cria projetos descartáveis "Smoke4 …", em pt: garante o caminho legado)
const BASE = process.argv[2] ?? 'http://127.0.0.1:8180';
let failures = 0;
const check = (cond, msg) => { if (cond) console.log('  ok  ' + msg); else { failures++; console.log('  FALHOU  ' + msg); } };

async function api(method, path, body, expectStatus) {
  const res = await fetch(BASE + path, { method, headers: { 'content-type': 'application/json', 'x-lang': 'pt' }, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (expectStatus !== undefined ? res.status !== expectStatus : !res.ok) throw new Error(`${method} ${path} -> ${res.status}: ${text}`);
  return data;
}
const created = [];
const ops = (pid, title, operations) => api('POST', `/api/projects/${pid}/changesets`, { title, operations });

console.log('# Perfis');
const profiles = await api('GET', '/api/harness/profiles');
check(profiles.length === 8 && !profiles.some((p) => p.id === 'nucleo'), '8 perfis listados (núcleo é abstrato)');
for (const p of profiles) {
  const proj = await api('POST', '/api/projects', { context: 'narrative', name: `Smoke4 perfil ${p.id}`, profile: p.id, locale: 'pt' }, 201);
  created.push(proj);
  const schema = await api('GET', `/api/projects/${proj.id}/schema`);
  const names = schema.nodeTypes.map((t) => t.name);
  const unitsOk = p.units.every((u) => names.includes(u));
  check(unitsOk && names.includes('Personagem') && names.includes('Beat'), `perfil ${p.id}: ${schema.nodeTypes.length} tipos, ${schema.relTypes.length} relações, unidades ${p.units.join('>')}`);
}

console.log('# Livro com Três atos');
const proj = await api('POST', '/api/projects', { context: 'narrative', name: 'Smoke4 livro ' + new Date().toISOString(), profile: 'livro', locale: 'pt' }, 201);
const pid = proj.id;
created.push(proj);
check(proj.profile === 'livro', 'projeto criado com perfil livro');
const again = await api('POST', `/api/projects/${pid}/profile/apply`, { profileId: 'livro' });
check((again.changes ?? []).length === 0, 'reaplicar o perfil não duplica nada');

const w = await ops(pid, 'estrutura', [
  { op: 'create_node', ref: '$obra', type: 'Obra', name: 'A Obra', attributes: { premissa: 'Uma ladra precisa roubar a própria memória.' } },
  ...[1, 2, 3].map((i) => ({ op: 'create_node', ref: `$c${i}`, type: 'Capítulo', name: `Capítulo ${i}`, parent: '$obra', summary: `Resumo do capítulo ${i}` })),
  { op: 'create_node', ref: '$ana', type: 'Personagem', name: 'Ana', summary: 'Ladra', attributes: { papel: 'protagonista' } },
  { op: 'create_node', ref: '$chave', type: 'Item', name: 'Chave', attributes: { regras_de_uso: 'Só abre portas à meia-noite' } },
  { op: 'create_node', ref: '$poder', type: 'Habilidade', name: 'Esquecer', attributes: { custo: 'Perde uma lembrança a cada uso' } },
  { op: 'create_node', ref: '$g', type: 'Gancho', name: 'Quem apagou a memória de Ana?', attributes: { tipo: 'misterio', status: 'aberto' } },
  { op: 'create_edge', relType: 'APARECE_EM', from: '$ana', to: '$c3' },
  { op: 'create_edge', relType: 'POSSUI', from: '$ana', to: '$chave', fromUnit: '$c2' },
  { op: 'create_edge', relType: 'DOMINA', from: '$ana', to: '$poder' },
  { op: 'create_edge', relType: 'PLANTA', from: '$c1', to: '$g' },
]);
const id = w.ids;
const facts = await api('GET', `/api/projects/${pid}/facts?minCriticality=critical`);
check(facts.some((f) => f.text.includes('Perde uma lembrança') && f.rigidity === 'law'), 'defaultCriticality do perfil: custo da habilidade vira lei');

const tpl = await api('POST', `/api/projects/${pid}/templates/apply`, { templateId: 'tres_atos', plannedUnits: 9 });
check(tpl.status === 'applied' && tpl.changes.length === 15, 'template Três atos aplicado (1 template + 7 beats + 7 arestas)');
const applied = await api('GET', `/api/projects/${pid}/templates`);
check(applied.length === 1 && applied[0].unitType === 'Capítulo' && applied[0].beats === 7, 'template ligado a Capítulo (padrão do perfil)');

const beatsBefore = await api('GET', `/api/projects/${pid}/coverage`);
const beat = (key) => beatsBefore[0].beats.find((b) => b.key === key);
await ops(pid, 'mapear', [
  { op: 'create_edge', relType: 'MAPEIA_BEAT', from: id.$c1, to: beat('preparacao').beatId },
  { op: 'create_edge', relType: 'MAPEIA_BEAT', from: id.$c3, to: beat('ponto_medio').beatId },
]);
const cov = (await api('GET', `/api/projects/${pid}/coverage`))[0];
const st = (key) => cov.beats.find((b) => b.key === key).status;
check(st('preparacao') === 'ok', 'cobertura: preparação no lugar');
check(st('ponto_medio') === 'off_position', 'cobertura: ponto médio no cap 3 de 9 está fora de posição');
check(st('incidente_incitante') === 'missing', 'cobertura: incidente incitante faltando (história já passou de 12%)');
check(st('climax') === 'future', 'cobertura: clímax ainda no futuro');
check(cov.unitsWithoutBeat.length === 1 && cov.unitsWithoutBeat[0].name === 'Capítulo 2', 'cobertura: Capítulo 2 sem beat');

const threads = await api('GET', `/api/projects/${pid}/threads`);
check(threads.length === 1 && threads[0].plantedIn[0].name === 'Capítulo 1' && threads[0].unitsSincePlanted === 2, 'gancho aberto plantado no cap 1, há 2 unidades');

await ops(pid, 'diretiva', [{ op: 'upsert_directive', text: 'Ana nunca mente diretamente.', definition: { when: ['context'], types: ['Personagem'], priority: 9 } }]);
const dirs = await api('GET', `/api/projects/${pid}/directives`);
check(dirs.some((d) => d.source === 'project') && dirs.some((d) => d.source === 'profile:livro') && dirs.some((d) => d.source === 'profile:nucleo'), 'diretivas do projeto e dos perfis (livro + núcleo)');

const ctx = await api('GET', `/api/projects/${pid}/writing-context/${id.$c3}`);
check(ctx.unit.name === 'Capítulo 3' && ctx.path[0].name === 'A Obra', 'writing_context: unidade e caminho na estrutura');
check(ctx.previous.length === 2 && ctx.previous[1].name === 'Capítulo 2', 'writing_context: capítulos anteriores em ordem');
check(ctx.beatsHere.some((b) => b.key === 'ponto_medio'), 'writing_context: beat mapeado aqui');
const anaCtx = ctx.inPlay.find((e) => e.name === 'Ana');
check(anaCtx && anaCtx.state.some((s) => s.includes('POSSUI') && s.includes('Chave') && s.includes('Capítulo 2')), 'writing_context: estado de Ana com âncora pelo nome da unidade');
check(ctx.facts.some((f) => f.rigidity === 'law'), 'writing_context: leis em jogo');
check(ctx.guidance.includes('Ana nunca mente diretamente.'), 'writing_context: diretiva do projeto disparada por tipo em jogo');
check(ctx.openThreads.length === 1, 'writing_context: ganchos abertos');
const small = await api('GET', `/api/projects/${pid}/writing-context/${id.$c3}?maxTokens=800`);
const cut = small.trimmed ?? [];
check(small.estimatedTokens <= ctx.estimatedTokens && (small.estimatedTokens <= 800 || cut.length > 0),
  `writing_context respeita orçamento (${ctx.estimatedTokens} → ${small.estimatedTokens} tokens; cortes: ${cut.length})`);

const c3 = (await api('GET', `/api/projects/${pid}/nodes/${id.$c3}`)).node;
await ops(pid, 'texto longo', [{ op: 'update_node', id: id.$c3, expectedVersion: c3.version, description: 'Texto longo. '.repeat(600) }]);
const big = await api('GET', `/api/projects/${pid}/writing-context/${id.$c3}?maxTokens=1000`);
check(big.estimatedTokens <= 1000 && (big.trimmed ?? []).some((t) => t.includes('descrição')), `writing_context corta a descrição longa para caber (${big.estimatedTokens} tokens)`);

await ops(pid, 'pagar', [{ op: 'create_edge', relType: 'PAGA', from: id.$c3, to: id.$g }]);
const t2 = await api('GET', `/api/projects/${pid}/threads`);
check(t2.length === 1 && t2[0].warning, 'gancho com PAGA mas status aberto gera aviso');

const bad = await api('POST', `/api/projects/${pid}/templates/apply`, { templateId: 'tres_atos', unitType: 'Personagem' }, 400);
check(bad.detail.includes('não é uma unidade'), 'template exige tipo com base Unit');

console.log('# MCP');
async function mcp(sid, method, params, n) {
  const res = await fetch(BASE + '/mcp', { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', ...(sid ? { 'mcp-session-id': sid } : {}) },
    body: JSON.stringify({ jsonrpc: '2.0', id: n, method, params }) });
  const text = await res.text();
  const line = text.split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5)).join('');
  return { sid: res.headers.get('mcp-session-id') ?? sid, msg: JSON.parse(line || text) };
}
const init = await mcp(null, 'initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'smoke4', version: '1' } }, 1);
await fetch(BASE + '/mcp', { method: 'POST', headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', 'mcp-session-id': init.sid }, body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) });
const tools = (await mcp(init.sid, 'tools/list', {}, 2)).msg.result.tools.map((t) => t.name);
check(['writing_context', 'template_apply', 'milestone_coverage', 'open_threads', 'profile_list', 'directives_list'].every((t) => tools.includes(t)), `tools novas no MCP (${tools.length} no total)`);
const begin = await mcp(init.sid, 'tools/call', { name: 'session_begin', arguments: { projectId: pid, goal: 'teste' } }, 3);
const briefing = JSON.parse(begin.msg.result.content[0].text);
check(briefing.profileInfo?.id === 'livro' && briefing.templates.length === 1 && briefing.guidance.length > 0, 'briefing traz perfil, templates e guidance');
const wc = await mcp(init.sid, 'tools/call', { name: 'writing_context', arguments: { projectId: pid, unitId: id.$c3, maxTokens: 3000 } }, 4);
check(!wc.msg.result.isError && JSON.parse(wc.msg.result.content[0].text).unit.id === id.$c3, 'writing_context via MCP');
const pc = await mcp(init.sid, 'tools/call', { name: 'propose_changes', arguments: { projectId: pid, title: 'llm', operations: [{ op: 'create_node', type: 'Personagem', name: 'Bruno' }] } }, 5);
check(JSON.parse(pc.msg.result.content[0].text).guidance?.length > 0, 'propose_changes devolve guidance de escrita');

console.log('# Limpeza');
for (const p of created) await api('DELETE', `/api/projects/${p.id}?confirm=${encodeURIComponent(p.name)}`, undefined, 204);
const left = (await api('GET', '/api/projects')).filter((p) => created.some((c) => c.id === p.id));
check(left.length === 0, `${created.length} projetos de teste excluídos`);
await api('DELETE', `/api/projects/${created[0].id}?confirm=x`, undefined, 404);

console.log(failures === 0 ? '\nTUDO OK' : `\n${failures} FALHA(S)`);
process.exit(failures === 0 ? 0 : 1);
