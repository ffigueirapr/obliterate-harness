// Generates the reference of the narrative context straight from a running Obliterate: for every preset (profile) and
// language it creates a throwaway project, reads the schema the engine gave it and deletes the project again.
// Output: docs/narrative/schema_narrativo.json (machine-readable) and docs/narrative/referencia_schema.md (tables).
// Usage: node scripts/gen_narrative_schema.mjs [baseUrl]   (use a test instance: it creates and deletes projects)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const BASE = process.argv[2] ?? 'http://127.0.0.1:8182';
const OUT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../docs/narrative');
const call = async (method, p, body, lang = 'pt') => {
  const res = await fetch(BASE + p, { method, headers: { 'content-type': 'application/json', 'x-lang': lang }, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${p} → ${res.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
};

const clean = (o) => JSON.parse(JSON.stringify(o, (k, v) => (k === 'id' || k === 'projectId' ? undefined : v)));
const out = { generatedAt: new Date().toISOString(), source: 'Obliterate Harness — narrative context', presets: {}, templates: {} };

// One project per preset, created in Portuguese, read, converted in place to English (ids are kept) and read again, so
// both languages line up by id (the API lists types sorted by name, which differs between languages).
const presetsPt = await call('GET', '/api/harness/profiles', undefined, 'pt');
const presetsEn = await call('GET', '/api/harness/profiles', undefined, 'en');
for (const p of presetsPt) {
  const pe = presetsEn.find((x) => x.id === p.id) ?? {};
  const name = `schema-gen ${p.id} ${Date.now()}`;
  const proj = await call('POST', '/api/contexts/narrative/projects', { name, locale: 'pt', preset: p.id }, 'pt');
  try {
    const read = async (lang) => ({ s: await call('GET', `/api/projects/${proj.id}/schema`, undefined, lang), d: await call('GET', `/api/projects/${proj.id}/directives`, undefined, lang) });
    const ptR = await read('pt');
    await call('POST', `/api/projects/${proj.id}/localize`, { locale: 'en' }, 'en');
    const enR = await read('en');
    const byId = (list) => new Map(list.map((x) => [x.id, x]));
    const enTypes = byId(enR.s.nodeTypes), enRels = byId(enR.s.relTypes);
    const sch = (types, rels, objs) => ({ nodeTypes: clean(types), relTypes: clean(rels.filter((r) => r.name !== 'CONTAINS')), objectTypes: clean(objs ?? []) });
    const ptTypes = ptR.s.nodeTypes, ptRels = ptR.s.relTypes.filter((r) => r.name !== 'CONTAINS');
    const dirs = (l) => l.map((d) => ({ text: d.text, when: d.when, bases: d.bases, types: d.types, priority: d.priority, source: d.source }));
    out.presets[p.id] = {
      name: { pt: p.name, en: pe.name }, description: { pt: p.description, en: pe.description }, units: { pt: p.units, en: pe.units },
      suggestedTemplates: p.suggestedTemplates,
      // same order in both languages: entry i of en is the translation of entry i of pt
      schema: { pt: sch(ptTypes, ptRels, ptR.s.objectTypes), en: sch(ptTypes.map((t) => enTypes.get(t.id) ?? t), ptRels.map((r) => enRels.get(r.id) ?? r), enR.s.objectTypes) },
      directives: { pt: dirs(ptR.d), en: dirs(enR.d) },
    };
  } finally {
    await call('DELETE', `/api/projects/${proj.id}?confirm=${encodeURIComponent(name)}`);
  }
}

for (const lang of ['pt', 'en']) {
  for (const t of (await call('GET', '/api/harness/templates', undefined, lang)).filter((x) => !x.custom)) {
    const d = await call('GET', `/api/harness/templates/${t.id}`, undefined, lang);
    (out.templates[t.id] ??= {})[lang] = { name: d.name, description: d.description, scope: d.scope, media: d.media, source: d.source, beats: d.beats };
  }
}
fs.mkdirSync(OUT, { recursive: true });
fs.writeFileSync(path.join(OUT, 'schema_narrativo.json'), JSON.stringify(out, null, 2));

// ---- markdown reference (Portuguese, with the English names alongside)
const md = [];
const esc = (s) => String(s ?? '').replace(/\|/g, '\\|').replace(/\n/g, ' ');
md.push('# Referência do schema narrativo', '', `> Gerado de uma instância do Obliterate em ${out.generatedAt.slice(0, 10)} por \`scripts/gen_narrative_schema.mjs\`. Não edite à mão: rode o script de novo.`,
  '> Versão legível por máquina: [schema_narrativo.json](schema_narrativo.json).', '');
md.push('## Perfis (presets) do contexto narrativo', '', '| Perfil | Nome (pt / en) | Unidades | Templates sugeridos |', '|---|---|---|---|');
for (const [id, p] of Object.entries(out.presets)) md.push(`| \`${id}\` | ${esc(p.name.pt)} / ${esc(p.name.en)} | ${esc((p.units.pt ?? []).join(' › '))} | ${esc((p.suggestedTemplates ?? []).join(', '))} |`);
for (const [id, p] of Object.entries(out.presets)) {
  const pt = p.schema.pt, en = p.schema.en;
  md.push('', `## Perfil \`${id}\` — ${esc(p.name.pt)}`, '', esc(p.description.pt), '', '### Tipos de nó', '');
  pt.nodeTypes.forEach((t, i) => {
    const e = en.nodeTypes[i] ?? {};
    md.push(`#### ${t.name} (en: ${e.name ?? '—'}) · base \`${t.base}\`${t.systemKey ? ` · chave de sistema \`${t.systemKey}\`` : ''}`, '');
    if (t.description) md.push(esc(t.description), '');
    if (t.attributes?.length) {
      md.push('| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |', '|---|---|---|---|---|---|---|');
      t.attributes.forEach((a, j) => {
        const ea = e.attributes?.[j] ?? {};
        const detail = [a.options && `opções: ${a.options.join(', ')}`, a.refType && `ref: ${a.refType}`, a.itemType && `itens: ${a.itemType}`, a.objectType && `objeto: ${a.objectType}`].filter(Boolean).join('; ');
        md.push(`| \`${a.key}\` | \`${ea.key ?? ''}\` | ${esc(a.label)} | ${a.dataType} | ${esc(detail)} | ${a.role ?? ''} | ${a.defaultCriticality ?? ''} |`);
      });
      md.push('');
    }
  });
  md.push('### Relações', '', '| Nome pt | Nome en | Base | Pares permitidos | Chave de sistema |', '|---|---|---|---|---|');
  pt.relTypes.forEach((r, i) => md.push(`| ${r.name} | ${en.relTypes[i]?.name ?? ''} | ${r.base} | ${esc((r.allowed ?? []).map((x) => `${x.from} → ${x.to}`).join('; '))} | ${r.systemKey ?? ''} |`));
  if (p.directives.pt?.length) {
    md.push('', '### Diretivas do perfil', '');
    for (const d of p.directives.pt) md.push(`- **${(d.when ?? []).join(', ')}**${d.types?.length ? ` (tipos: ${d.types.join(', ')})` : ''}${d.bases?.length ? ` (bases: ${d.bases.join(', ')})` : ''}: ${esc(d.text)}`);
  }
}
md.push('', '## Templates de estrutura', '');
for (const [id, t] of Object.entries(out.templates)) {
  md.push(`### ${esc(t.pt.name)} (en: ${esc(t.en.name)}) · \`${id}\` · escopo ${t.pt.scope}`, '', esc(t.pt.description), t.pt.source ? `\nFonte: ${esc(t.pt.source)}` : '', '',
    '| Posição | Beat (pt / en) | Chave | Obrigatório | Critério |', '|---|---|---|---|---|');
  t.pt.beats.forEach((b, i) => md.push(`| ${Math.round(b.position * 100)}% | ${esc(b.name)} / ${esc(t.en.beats[i]?.name)} | \`${b.key}\` | ${b.required === false ? 'não' : 'sim'} | ${esc(b.criteria)} |`));
  md.push('');
}
fs.writeFileSync(path.join(OUT, 'referencia_schema.md'), md.join('\n'));
console.log(`presets: ${Object.keys(out.presets).length}, templates: ${Object.keys(out.templates).length} → ${OUT}`);
