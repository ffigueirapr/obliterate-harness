import { useEffect, useMemo, useState } from 'react';
import { api } from '../api';
import { AttributeInput } from './AttributeInput';
import type { Fact, GEdge, GNode, IndirectLink, NodeDetail, Operation, Schema } from '../types';
import { SPACES, baseLabel, critLabel, rigidityLabel, spaceLabel } from '../types';
import { fmtDate, t } from '../i18n';

type Run = (title: string, ops: Operation[]) => Promise<boolean>;

const CRITS = ['normal', 'critical', 'foundational'];

export function CritSelect({ value, onChange }: { value?: string; onChange: (v: string) => void }) {
  return (
    <select value={value ?? 'normal'} onChange={(e) => onChange(e.target.value)}>
      {CRITS.map((c) => <option key={c} value={c}>{critLabel(c)}</option>)}
    </select>
  );
}

function TagEditor({ tags, onChange }: { tags: string[]; onChange: (t: string[]) => void }) {
  const [draft, setDraft] = useState('');
  const add = () => {
    const x = draft.trim().toLowerCase();
    if (x && !tags.includes(x)) onChange([...tags, x]);
    setDraft('');
  };
  return (
    <>
      <div className="chips" style={{ marginBottom: 4 }}>
        {tags.map((x) => (
          <span className="chip" key={x}>{x}<button title={t('remove', 'remover')} onClick={() => onChange(tags.filter((y) => y !== x))}>×</button></span>
        ))}
      </div>
      <input placeholder={t('new tag + Enter', 'nova tag + Enter')} value={draft} onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }} onBlur={add} />
    </>
  );
}

// ------------------------------------------------------------------ node

export function NodeEditor({ projectId, nodeId, schema, run, onFocus, reloadKey, onShowContext, onOpenUnit }: {
  projectId: string; nodeId: string; schema: Schema; run: Run; onFocus: (id: string) => void; reloadKey: number;
  onShowContext: (unitId: string) => void; onOpenUnit: (unitId: string) => void;
}) {
  const [detail, setDetail] = useState<NodeDetail | null>(null);
  const [draft, setDraft] = useState<GNode | null>(null);
  const [attrs, setAttrs] = useState<Record<string, unknown>>({});
  const [units, setUnits] = useState<GNode[]>([]);
  const [newFact, setNewFact] = useState({ text: '', rigidity: 'law', criticality: 'foundational', revealAt: '' });
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setError(null);
    api.node(projectId, nodeId).then((d) => {
      if (!alive) return;
      setDetail(d);
      setDraft(d.node);
      const merged: Record<string, unknown> = { ...(d.node.attributes ?? {}) };
      const type = schema.nodeTypes.find((x) => x.name === d.node.type);
      for (const [k, ids] of Object.entries(d.node.refs ?? {})) {
        const def = type?.attributes?.find((a) => a.key === k);
        merged[k] = def?.dataType === 'list' ? ids : ids[0];
      }
      setAttrs(merged);
    }).catch((e) => alive && setError(String(e.message ?? e)));
    return () => { alive = false; };
  }, [projectId, nodeId, reloadKey, schema]);

  useEffect(() => {
    api.nodes(projectId, { bases: ['Unit'], limit: 1000, detail: 'id' }).then(setUnits).catch(() => setUnits([]));
  }, [projectId]);

  const type = useMemo(() => schema.nodeTypes.find((x) => x.name === detail?.node.type), [schema, detail]);
  if (error) return <div className="muted">{error}</div>;
  if (!detail || !draft) return <div className="muted">{t('Loading…', 'Carregando…')}</div>;
  const n = detail.node;
  // manuscript and word count are edited in the unit workbench, not as plain attributes
  const editable = (type?.attributes ?? []).filter((d) => d.role !== 'manuscript' && d.role !== 'word_count');
  const hasManuscript = (type?.attributes ?? []).some((d) => d.role === 'manuscript') || n.base === 'Unit';

  const save = async () => {
    const op: Operation = { op: 'update_node', id: n.id, expectedVersion: n.version };
    if (draft.name !== n.name) op.name = draft.name;
    if ((draft.summary ?? '') !== (n.summary ?? '')) op.summary = draft.summary ?? '';
    if ((draft.description ?? '') !== (n.description ?? '')) op.description = draft.description ?? '';
    if (JSON.stringify(draft.tags ?? []) !== JSON.stringify(n.tags ?? [])) op.tags = draft.tags ?? [];
    if ((draft.criticality ?? 'normal') !== (n.criticality ?? 'normal')) op.criticality = draft.criticality;
    if (draft.space !== n.space) op.space = draft.space;
    if (n.base === 'Unit') {
      if ((draft.parent ?? '') !== (n.parent ?? '')) op.parent = draft.parent ?? '';
      if (draft.order !== n.order) op.order = draft.order;
    }
    const original: Record<string, unknown> = { ...(n.attributes ?? {}) };
    for (const [k, ids] of Object.entries(n.refs ?? {})) {
      const def = type?.attributes?.find((a) => a.key === k);
      original[k] = def?.dataType === 'list' ? ids : ids[0];
    }
    const skip = new Set((type?.attributes ?? []).filter((d) => d.role === 'manuscript' || d.role === 'word_count').map((d) => d.key));
    const changed: Record<string, unknown> = {};
    for (const k of new Set([...Object.keys(original), ...Object.keys(attrs)])) {
      if (skip.has(k)) continue;
      const a = attrs[k] ?? null;
      const b = original[k] ?? null;
      if (JSON.stringify(a) !== JSON.stringify(b)) changed[k] = a;
    }
    if (Object.keys(changed).length) op.attributes = changed;
    const critChanged: Record<string, string> = {};
    for (const k of new Set([...Object.keys(n.attributeCriticality ?? {}), ...Object.keys(draft.attributeCriticality ?? {})])) {
      const a = draft.attributeCriticality?.[k] ?? 'normal';
      const b = n.attributeCriticality?.[k] ?? 'normal';
      if (a !== b) critChanged[k] = a;
    }
    if (Object.keys(critChanged).length) op.attributeCriticality = critChanged;
    if (Object.keys(op).length <= 3) return;
    await run(`${t('Edit', 'Editar')} ${n.name}`, [op]);
  };

  const setAttrCrit = (key: string, v: string) =>
    setDraft({ ...draft, attributeCriticality: { ...(draft.attributeCriticality ?? {}), [key]: v } });

  return (
    <div>
      <div className="section">
        <h4>{n.type} <span className="muted small">{baseLabel(n.base)} · v{n.version}{n.wordCount ? ` · ${n.wordCount} ${t('words', 'palavras')}` : ''}</span></h4>
        <div className="field"><label>{t('Name', 'Nome')}</label><input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></div>
        <div className="field"><label>{t('Summary', 'Resumo')}</label>
          <textarea rows={2} value={draft.summary ?? ''} onChange={(e) => setDraft({ ...draft, summary: e.target.value })} />
          <div className="hint">{t('Short: it is what the LLM gets in briefings and validations.', 'Curto: é o que a LLM recebe em briefings e validações.')}</div>
        </div>
        <div className="field"><label>{t('Description', 'Descrição')}</label>
          <textarea rows={4} value={draft.description ?? ''} onChange={(e) => setDraft({ ...draft, description: e.target.value })} /></div>
        <div className="row">
          <div className="field"><label>{t('Criticality', 'Criticidade')}</label><CritSelect value={draft.criticality} onChange={(v) => setDraft({ ...draft, criticality: v as GNode['criticality'] })} /></div>
          <div className="field"><label>{t('Space', 'Espaço')}</label>
            <select value={draft.space} onChange={(e) => setDraft({ ...draft, space: e.target.value })}>
              {SPACES.map((s) => <option key={s} value={s}>{spaceLabel(s)}</option>)}
            </select></div>
        </div>
        <div className="field"><label>Tags</label><TagEditor tags={draft.tags ?? []} onChange={(x) => setDraft({ ...draft, tags: x })} /></div>
        {n.base === 'Unit' && (
          <div className="row">
            <div className="field"><label>{t('Inside', 'Dentro de')}</label>
              <select value={draft.parent ?? ''} onChange={(e) => setDraft({ ...draft, parent: e.target.value || undefined })}>
                <option value="">{t('(root)', '(raiz)')}</option>
                {units.filter((u) => u.id !== n.id).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
              </select></div>
            <div className="field fixed" style={{ width: 80 }}><label>{t('Order', 'Ordem')}</label>
              <input type="number" value={draft.order ?? ''} onChange={(e) => setDraft({ ...draft, order: Number(e.target.value) })} /></div>
          </div>
        )}
      </div>

      {editable.length > 0 && (
        <div className="section">
          <h4>{t('Attributes', 'Atributos')}</h4>
          {editable.map((def) => (
            <div className="field" key={def.key}>
              <label className="row" style={{ display: 'flex' }}>
                <span>{def.label ?? def.key}{def.required ? ' *' : ''} <span className="muted">({def.dataType}{def.itemType ? `<${def.itemType}>` : ''}{def.role ? ` · ${def.role}` : ''})</span></span>
                <select className="fixed" style={{ width: 'auto', padding: '0 4px', fontSize: 11 }} title={t('Criticality of this attribute', 'Criticidade deste atributo')}
                  value={draft.attributeCriticality?.[def.key] ?? 'normal'} onChange={(e) => setAttrCrit(def.key, e.target.value)}>
                  {CRITS.map((c) => <option key={c} value={c}>{critLabel(c)}</option>)}
                </select>
              </label>
              <AttributeInput projectId={projectId} def={def} value={attrs[def.key]} objectTypes={schema.objectTypes ?? []}
                onChange={(v) => setAttrs({ ...attrs, [def.key]: v })} />
              {def.description && <div className="hint">{def.description}</div>}
            </div>
          ))}
        </div>
      )}

      {n.base === 'Unit' && (
        <div className="row" style={{ marginBottom: 8 }}>
          {hasManuscript && (
            <button onClick={() => onOpenUnit(n.id)} title={t('Prose/script, drafts, cast and validation', 'Prosa/roteiro, rascunhos, elenco e validação')}>
              ✎ {t('Manuscript & cast', 'Manuscrito e elenco')}
            </button>
          )}
          <button onClick={() => onShowContext(n.id)} title={t('The package the LLM gets to write this unit', 'O pacote que a LLM recebe para escrever esta unidade')}>
            {t('Writing context', 'Contexto de escrita')}
          </button>
        </div>
      )}
      <div className="row" style={{ marginBottom: 16 }}>
        <button className="primary" onClick={save}>{t('Save', 'Salvar')}</button>
        <button className="danger fixed" onClick={() => confirm(t(`Delete "${n.name}" and its relations?`, `Excluir "${n.name}" e suas relações?`)) &&
          run(`${t('Delete', 'Excluir')} ${n.name}`, [{ op: 'delete_node', id: n.id, expectedVersion: n.version }])}>{t('Delete', 'Excluir')}</button>
      </div>

      <div className="section">
        <h4>{t('Relations', 'Relações')} ({detail.edges.length})</h4>
        {detail.edges.length === 0 && <div className="muted small">{t('None.', 'Nenhuma.')}</div>}
        {detail.edges.map((e) => {
          const other = e.from === n.id ? { id: e.to, name: e.toName } : { id: e.from, name: e.fromName };
          return (
            <div key={e.id} className="small" style={{ padding: '2px 0' }}>
              {e.from === n.id ? '→' : '←'} <b className={e.criticality ? `crit-${e.criticality}` : ''}>{e.relType}</b>{' '}
              <span className="tree node-link" onClick={() => onFocus(other.id)}>{other.name}</span>
              {(e.fromUnit || e.toUnit) && <span className="muted"> ⏱</span>}
            </div>
          );
        })}
      </div>

      <FactsBlock facts={detail.facts} run={run} units={units} />

      <div className="section">
        <h4>{t('New fact about this node', 'Novo fato sobre este nó')}</h4>
        <textarea rows={2} placeholder={t('E.g. the power only works at night', 'Ex.: o poder só funciona à noite')} value={newFact.text} onChange={(e) => setNewFact({ ...newFact, text: e.target.value })} />
        <div className="row" style={{ marginTop: 4 }}>
          <select value={newFact.rigidity} onChange={(e) => setNewFact({ ...newFact, rigidity: e.target.value })}>
            {['law', 'norm', 'flavor'].map((r) => <option key={r} value={r}>{rigidityLabel(r)}</option>)}
          </select>
          <CritSelect value={newFact.criticality} onChange={(v) => setNewFact({ ...newFact, criticality: v })} />
        </div>
        <div className="field" style={{ marginTop: 4 }}>
          <label>{t('Secret until (the reader learns it in…)', 'Segredo até (o leitor descobre em…)')}</label>
          <select value={newFact.revealAt} onChange={(e) => setNewFact({ ...newFact, revealAt: e.target.value })}>
            <option value="">{t('(not a secret)', '(não é segredo)')}</option>
            {units.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
          </select>
        </div>
        <button disabled={!newFact.text.trim()} onClick={async () => {
          const op: Operation = { op: 'create_fact', text: newFact.text, rigidity: newFact.rigidity, criticality: newFact.criticality, about: [n.id] };
          if (newFact.revealAt) op.revealAt = newFact.revealAt;
          if (await run(t('New fact', 'Novo fato'), [op])) setNewFact({ ...newFact, text: '', revealAt: '' });
        }}>{t('Add', 'Adicionar')}</button>
      </div>
      <div className="muted small">{t('Created by', 'Criado por')} {n.createdBy} · {t('updated by', 'atualizado por')} {n.updatedBy} {t('at', 'em')} {fmtDate(n.updatedAt)}</div>
    </div>
  );
}

function FactsBlock({ facts, run, units }: { facts: Fact[]; run: Run; units: GNode[] }) {
  if (facts.length === 0) return null;
  return (
    <div className="section">
      <h4>{t('Facts', 'Fatos')} ({facts.length})</h4>
      {facts.map((f) => (
        <div key={f.id} className="list-item small">
          <div><span className={`crit-${f.criticality}`}>{f.criticality === 'foundational' ? '◆' : '●'}</span> {f.text}</div>
          {f.revealAt && <div className="muted">🔒 {t('secret until', 'segredo até')} {f.revealAt.name}</div>}
          <div className="row" style={{ marginTop: 4 }}>
            <select value={f.rigidity} onChange={(e) => run(t('Fact rigidity', 'Rigidez do fato'), [{ op: 'update_fact', id: f.id, expectedVersion: f.version, rigidity: e.target.value }])}>
              <option value="law">{t('law (never broken)', 'lei (inviolável)')}</option>
              <option value="norm">{t('norm (rule of thumb)', 'norma (via de regra)')}</option>
              <option value="flavor">{t('flavor', 'ambientação')}</option>
            </select>
            <select title={t('Secret until', 'Segredo até')} value={f.revealAt?.id ?? ''}
              onChange={(e) => run(t('Fact reveal', 'Revelação do fato'), [{ op: 'update_fact', id: f.id, expectedVersion: f.version, revealAt: e.target.value }])}>
              <option value="">{t('not secret', 'não é segredo')}</option>
              {units.map((u) => <option key={u.id} value={u.id}>🔒 {u.name}</option>)}
            </select>
            {f.origin === 'manual'
              ? <button className="danger fixed" onClick={() => run(t('Delete fact', 'Excluir fato'), [{ op: 'delete_fact', id: f.id, expectedVersion: f.version }])}>{t('Delete', 'Excluir')}</button>
              : <span className="muted fixed" title={f.origin}>{t('mirrored', 'espelhado')}</span>}
          </div>
        </div>
      ))}
    </div>
  );
}

// ------------------------------------------------------------------ edge

export function EdgeEditor({ projectId, edge, run }: { projectId: string; edge: GEdge; run: Run }) {
  const [draft, setDraft] = useState(edge);
  const [units, setUnits] = useState<GNode[]>([]);
  useEffect(() => setDraft(edge), [edge]);
  useEffect(() => { api.nodes(projectId, { bases: ['Unit'], limit: 1000, detail: 'id' }).then(setUnits).catch(() => setUnits([])); }, [projectId]);
  if (edge.kind !== 'rel') {
    return <div className="muted">{edge.kind === 'structure'
      ? t('Structure relation: edit it with the unit\'s "Inside".', 'Relação de estrutura: edite pelo "Dentro de" da unidade.')
      : t(`Attribute "${edge.relType}" of the source node: edit it on the node.`, `Atributo "${edge.relType}" do nó de origem: edite pelo nó.`)}</div>;
  }
  const save = () => {
    const op: Operation = { op: 'update_edge', id: edge.id, expectedVersion: edge.version };
    if ((draft.description ?? '') !== (edge.description ?? '')) op.description = draft.description ?? '';
    if ((draft.criticality ?? 'normal') !== (edge.criticality ?? 'normal')) op.criticality = draft.criticality;
    if ((draft.fromUnit ?? '') !== (edge.fromUnit ?? '')) op.fromUnit = draft.fromUnit ?? '';
    if ((draft.toUnit ?? '') !== (edge.toUnit ?? '')) op.toUnit = draft.toUnit ?? '';
    if (JSON.stringify(draft.tags ?? []) !== JSON.stringify(edge.tags ?? [])) op.tags = draft.tags ?? [];
    if (Object.keys(op).length > 3) run(`${t('Edit relation', 'Editar relação')} ${edge.relType}`, [op]);
  };
  const unitSelect = (value: string | undefined, set: (v: string | undefined) => void) => (
    <select value={value ?? ''} onChange={(e) => set(e.target.value || undefined)}>
      <option value="">{t('(always)', '(sempre)')}</option>
      {units.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
    </select>
  );
  return (
    <div>
      <div className="section">
        <h4>{t('Relation', 'Relação')} · v{edge.version}</h4>
        <p><b>{edge.fromName}</b> —{edge.relType}→ <b>{edge.toName}</b></p>
        <div className="field"><label>{t('Description', 'Descrição')}</label>
          <textarea rows={3} value={draft.description ?? ''} onChange={(e) => setDraft({ ...draft, description: e.target.value })} /></div>
        <div className="field"><label>{t('Criticality', 'Criticidade')}</label><CritSelect value={draft.criticality} onChange={(v) => setDraft({ ...draft, criticality: v as GEdge['criticality'] })} /></div>
        <div className="row">
          <div className="field"><label>{t('Holds from', 'Vale a partir de')}</label>{unitSelect(draft.fromUnit, (v) => setDraft({ ...draft, fromUnit: v }))}</div>
          <div className="field"><label>{t('Until (inclusive)', 'Até (inclusive)')}</label>{unitSelect(draft.toUnit, (v) => setDraft({ ...draft, toUnit: v }))}</div>
        </div>
        <div className="field"><label>Tags</label><TagEditor tags={draft.tags ?? []} onChange={(x) => setDraft({ ...draft, tags: x })} /></div>
      </div>
      <div className="row">
        <button className="primary" onClick={save}>{t('Save', 'Salvar')}</button>
        <button className="danger fixed" onClick={() => confirm(t('Delete this relation?', 'Excluir esta relação?')) && run(t('Delete relation', 'Excluir relação'), [{ op: 'delete_edge', id: edge.id, expectedVersion: edge.version }])}>{t('Delete', 'Excluir')}</button>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ bulk

export function BulkEditor({ nodes, run, onClear }: { nodes: GNode[]; run: Run; onClear: () => void }) {
  const [tag, setTag] = useState('');
  const [crit, setCrit] = useState('critical');
  const [space, setSpace] = useState('world');
  const update = (title: string, patch: (n: GNode) => Record<string, unknown>) =>
    run(title, nodes.map((n) => ({ op: 'update_node', id: n.id, expectedVersion: n.version, ...patch(n) })));
  const byType = nodes.reduce<Record<string, number>>((acc, n) => ({ ...acc, [n.type]: (acc[n.type] ?? 0) + 1 }), {});
  return (
    <div>
      <div className="section">
        <h4>{nodes.length} {t('nodes selected', 'nós selecionados')} <button className="ghost small" onClick={onClear}>{t('clear', 'limpar')}</button></h4>
        <div className="small muted">{Object.entries(byType).map(([x, c]) => `${c} ${x}`).join(' · ')}</div>
      </div>
      <div className="section">
        <h4>Tags</h4>
        <div className="row">
          <input placeholder="tag" value={tag} onChange={(e) => setTag(e.target.value)} />
          <button className="fixed" disabled={!tag.trim()} onClick={() => update(`Tag +${tag}`, (n) => ({ tags: [...new Set([...(n.tags ?? []), tag.trim().toLowerCase()])] }))}>{t('Add', 'Adicionar')}</button>
          <button className="fixed" disabled={!tag.trim()} onClick={() => update(`Tag -${tag}`, (n) => ({ tags: (n.tags ?? []).filter((x) => x !== tag.trim().toLowerCase()) }))}>{t('Remove', 'Remover')}</button>
        </div>
      </div>
      <div className="section">
        <h4>{t('Criticality', 'Criticidade')}</h4>
        <div className="row">
          <CritSelect value={crit} onChange={setCrit} />
          <button className="fixed" onClick={() => update(`${t('Criticality', 'Criticidade')} ${crit}`, () => ({ criticality: crit }))}>{t('Apply', 'Aplicar')}</button>
        </div>
      </div>
      <div className="section">
        <h4>{t('Space', 'Espaço')}</h4>
        <div className="row">
          <select value={space} onChange={(e) => setSpace(e.target.value)}>
            {SPACES.map((s) => <option key={s} value={s}>{spaceLabel(s)}</option>)}
          </select>
          <button className="fixed" onClick={() => update(`${t('Move to', 'Mover para')} ${spaceLabel(space)}`, () => ({ space }))}>{t('Move', 'Mover')}</button>
        </div>
      </div>
      <button className="danger" style={{ width: '100%' }} onClick={() => confirm(t(`Delete ${nodes.length} nodes and their relations?`, `Excluir ${nodes.length} nós e suas relações?`)) &&
        run(`${t('Delete', 'Excluir')} ${nodes.length} ${t('nodes', 'nós')}`, nodes.map((n) => ({ op: 'delete_node', id: n.id, expectedVersion: n.version })))}>
        {t('Delete', 'Excluir')} {nodes.length} {t('nodes', 'nós')}
      </button>
      <p className="small muted">{t('All in a single changeset: it applies to all of them or to none.', 'Tudo vai num único changeset: ou aplica em todos, ou em nenhum.')}</p>
    </div>
  );
}

export function IndirectInfo({ projectId, link, onFocus }: { projectId: string; link: IndirectLink; onFocus: (id: string) => void }) {
  const [names, setNames] = useState<Record<string, string>>({});
  useEffect(() => {
    Promise.all(link.pathNodeIds.map((id) => api.node(projectId, id).then((d) => [id, d.node.name] as const).catch(() => [id, id] as const)))
      .then((pairs) => setNames(Object.fromEntries(pairs)));
  }, [projectId, link]);
  const nameOf = (id: string) => names[id] ?? '…';
  return (
    <div className="section">
      <h4>{t('Indirect link', 'Ligação indireta')} · {link.hops} {t('hops', 'saltos')}</h4>
      <ol className="small" style={{ paddingLeft: 18 }}>
        {link.pathNodeIds.map((id, i) => (
          <li key={id}>
            <span className="tree node-link" onClick={() => onFocus(id)}>{nameOf(id)}</span>
            {i < link.pathRelTypes.length && <span className="muted"> —{link.pathRelTypes[i] || 'ref'}→</span>}
          </li>
        ))}
      </ol>
      <p className="small muted">{t('Intermediate nodes may be outside the current filter.', 'Nós intermediários podem estar fora do filtro atual.')}</p>
    </div>
  );
}
