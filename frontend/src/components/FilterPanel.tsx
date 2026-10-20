import { useEffect, useState } from 'react';
import { api } from '../api';
import { BASE_COLOR } from './GraphCanvas';
import type { GNode, Schema, ViewFilter } from '../types';
import { BASES, baseLabel } from '../types';
import { t } from '../i18n';

interface Props {
  projectId: string;
  schema: Schema;
  filter: ViewFilter;
  setFilter: (f: ViewFilter) => void;
  knownTags: string[];
  onFocus: (id: string) => void;
}

const toggle = (list: string[], v: string) => (list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);

export function FilterPanel({ projectId, schema, filter, setFilter, knownTags, onFocus }: Props) {
  const [q, setQ] = useState('');
  const [results, setResults] = useState<GNode[]>([]);
  const [tagDraft, setTagDraft] = useState('');

  useEffect(() => {
    if (!q.trim()) { setResults([]); return; }
    const h = setTimeout(() => api.nodes(projectId, { q, limit: 15 }).then(setResults).catch(() => setResults([])), 250);
    return () => clearTimeout(h);
  }, [q, projectId]);

  const counts = schema.nodeCountByType ?? {};
  const relNames = schema.relTypes.filter((r) => r.name !== 'CONTAINS').map((r) => r.name);
  const active = filter.types.length + filter.bases.length + filter.tags.length + (filter.minCriticality ? 1 : 0);

  return (
    <aside className="sidebar">
      <div className="section">
        <h4>{t('Search', 'Buscar')}</h4>
        <input placeholder={t('name, summary, attribute…', 'nome, resumo, atributo…')} value={q} onChange={(e) => setQ(e.target.value)} />
        {results.map((n) => (
          <div key={n.id} className="check small" onClick={() => onFocus(n.id)}>
            <span className="dot" style={{ background: BASE_COLOR[n.base ?? 'Note'] }} />
            <span>{n.name}</span><span className="count">{n.type}</span>
          </div>
        ))}
      </div>

      <div className="section">
        <h4>{t('Filters', 'Filtros')} {active > 0 && <button className="ghost small" onClick={() => setFilter({ ...filter, types: [], bases: [], tags: [], minCriticality: '' })}>{t('clear', 'limpar')} ({active})</button>}</h4>
      </div>

      <div className="section">
        <h4>{t('Node types', 'Tipos de nó')}</h4>
        {schema.nodeTypes.length === 0 && <div className="muted small">{t('No types yet. Create them in Schema.', 'Nenhum tipo ainda. Crie em Schema.')}</div>}
        {schema.nodeTypes.map((x) => (
          <label key={x.id} className="check">
            <input type="checkbox" checked={filter.types.includes(x.name)} onChange={() => setFilter({ ...filter, types: toggle(filter.types, x.name) })} />
            <span className="dot" style={{ background: x.color || BASE_COLOR[x.base] }} />
            <span>{x.name}</span><span className="count">{counts[x.name] ?? 0}</span>
          </label>
        ))}
      </div>

      <div className="section">
        <h4>{t('Base kinds', 'Tipos-base')}</h4>
        {BASES.map((b) => (
          <label key={b} className="check">
            <input type="checkbox" checked={filter.bases.includes(b)} onChange={() => setFilter({ ...filter, bases: toggle(filter.bases, b) })} />
            <span className="dot" style={{ background: BASE_COLOR[b] }} />
            <span>{baseLabel(b)}</span>
          </label>
        ))}
      </div>

      <div className="section">
        <h4>Tags</h4>
        <div className="chips" style={{ marginBottom: 6 }}>
          {[...new Set([...knownTags, ...filter.tags])].sort().map((x) => (
            <button key={x} className={'chip ' + (filter.tags.includes(x) ? 'active' : '')} onClick={() => setFilter({ ...filter, tags: toggle(filter.tags, x) })}>{x}</button>
          ))}
        </div>
        <input placeholder={t('filter by tag + Enter', 'filtrar por tag + Enter')} value={tagDraft} onChange={(e) => setTagDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && tagDraft.trim()) {
              setFilter({ ...filter, tags: [...new Set([...filter.tags, tagDraft.trim().toLowerCase()])] });
              setTagDraft('');
            }
          }} />
      </div>

      <div className="section">
        <h4>{t('Criticality', 'Criticidade')}</h4>
        <select value={filter.minCriticality} onChange={(e) => setFilter({ ...filter, minCriticality: e.target.value })}>
          <option value="">{t('All', 'Todas')}</option>
          <option value="critical">{t('Critical or foundational', 'Crítico ou fundacional')}</option>
          <option value="foundational">{t('Foundational only', 'Só fundacional')}</option>
        </select>
      </div>

      <div className="section">
        <h4>{t('Links', 'Ligações')}</h4>
        <label className="check">
          <input type="checkbox" checked={filter.includeIndirect} onChange={(e) => setFilter({ ...filter, includeIndirect: e.target.checked })} />
          <span>{t('Show indirect', 'Mostrar indiretas')}</span>
        </label>
        {filter.includeIndirect && (
          <>
            <div className="field">
              <label>{t(`Up to ${filter.maxHops} hops`, `Até ${filter.maxHops} saltos`)}</label>
              <input type="range" min={2} max={5} value={filter.maxHops} onChange={(e) => setFilter({ ...filter, maxHops: Number(e.target.value) })} />
            </div>
            <div className="field">
              <label>{t('Only through', 'Só através de')}</label>
              {relNames.map((r) => (
                <label key={r} className="check small">
                  <input type="checkbox" checked={filter.relTypes.includes(r)} onChange={() => setFilter({ ...filter, relTypes: toggle(filter.relTypes, r) })} />
                  <span>{r}</span>
                </label>
              ))}
              <div className="hint">{t('None checked = any relation.', 'Nenhuma marcada = qualquer relação.')}</div>
            </div>
          </>
        )}
      </div>
    </aside>
  );
}
