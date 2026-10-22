import { useState } from 'react';
import { Modal } from './Dialogs';
import { BASE_COLOR } from './GraphCanvas';
import type { AttrDef, NodeTypeDef, ObjectTypeDef, Operation, RelTypeDef, Schema } from '../types';
import { BASES, DATA_TYPES, REL_BASES, baseLabel, critLabel } from '../types';
import { t } from '../i18n';

type Run = (title: string, ops: Operation[]) => Promise<boolean>;
type Sel = { kind: 'node'; def: Partial<NodeTypeDef> } | { kind: 'rel'; def: Partial<RelTypeDef> } | { kind: 'object'; def: Partial<ObjectTypeDef> } | null;

/** Attribute roles the engine understands, whatever the attribute is called. */
const ROLES = ['link'];

export function SchemaEditor({ schema, run, onClose }: { schema: Schema; run: Run; onClose: () => void }) {
  const [sel, setSel] = useState<Sel>(schema.nodeTypes[0] ? { kind: 'node', def: schema.nodeTypes[0] } : null);
  const rels = schema.relTypes.filter((r) => r.name !== 'CONTAINS');
  const isSel = (kind: string, id?: string) => sel?.kind === kind && sel.def.id === id;
  return (
    <Modal title={t('Project schema', 'Schema do projeto')} onClose={onClose}>
      <div className="two-col">
        <div className="type-list">
          <div className="section">
            <h4>{t('Node types', 'Tipos de nó')} <button className="ghost small" onClick={() => setSel({ kind: 'node', def: { base: 'Agent', attributes: [] } })}>{t('+ new', '+ novo')}</button></h4>
            {schema.nodeTypes.map((x) => (
              <button key={x.id} className={isSel('node', x.id) ? 'active' : ''} onClick={() => setSel({ kind: 'node', def: x })}>
                {x.name} <span className="muted small">{x.base}{x.systemKey ? ' ⚙' : ''}</span>
              </button>
            ))}
          </div>
          <div className="section">
            <h4>{t('Relations', 'Relações')} <button className="ghost small" onClick={() => setSel({ kind: 'rel', def: { base: 'SOCIAL', allowed: [] } })}>{t('+ new', '+ nova')}</button></h4>
            {rels.map((x) => (
              <button key={x.id} className={isSel('rel', x.id) ? 'active' : ''} onClick={() => setSel({ kind: 'rel', def: x })}>
                {x.name} <span className="muted small">{x.base}{x.systemKey ? ' ⚙' : ''}</span>
              </button>
            ))}
          </div>
          <div className="section">
            <h4>{t('Objects', 'Objetos')} <button className="ghost small" onClick={() => setSel({ kind: 'object', def: { fields: [] } })}>{t('+ new', '+ novo')}</button></h4>
            {(schema.objectTypes ?? []).map((x) => (
              <button key={x.id} className={isSel('object', x.id) ? 'active' : ''} onClick={() => setSel({ kind: 'object', def: x })}>{x.name}</button>
            ))}
          </div>
        </div>
        <div>
          {!sel && <p className="muted">{t('Start by creating node types (e.g. Team with base Agent, Product with base Object, Document with base Media).',
            'Comece criando tipos de nó (ex.: Equipe com base Agente, Produto com base Objeto, Documento com base Mídia).')}</p>}
          <p className="small muted">{t('⚙ = used by the engine (system key): it finds the type by the key, whatever its name or language.',
            '⚙ = usado pelo motor (chave de sistema): ele acha o tipo pela chave, seja qual for o nome ou idioma.')}</p>
          {sel?.kind === 'node' && <NodeTypeForm key={sel.def.id ?? 'new'} def={sel.def} schema={schema} run={run} onSaved={() => setSel(null)} />}
          {sel?.kind === 'rel' && <RelTypeForm key={sel.def.id ?? 'new'} def={sel.def} schema={schema} run={run} onSaved={() => setSel(null)} />}
          {sel?.kind === 'object' && <ObjectTypeForm key={sel.def.id ?? 'new'} def={sel.def} schema={schema} run={run} onSaved={() => setSel(null)} />}
        </div>
      </div>
    </Modal>
  );
}

function strip<T extends object>(o: T): T {
  return JSON.parse(JSON.stringify(o, (_k, v) => (v === '' || v === null ? undefined : v)));
}

function NodeTypeForm({ def, schema, run, onSaved }: { def: Partial<NodeTypeDef>; schema: Schema; run: Run; onSaved: () => void }) {
  const [d, setD] = useState<Partial<NodeTypeDef>>(def);
  const save = async () => {
    const definition = strip({ name: d.name, base: d.base, description: d.description, color: d.color, strict: d.strict ?? true,
      attributes: d.attributes ?? [], systemKey: d.systemKey });
    if (await run(`${t('Type', 'Tipo')} ${d.name}`, [{ op: 'upsert_node_type', id: d.id, definition }])) onSaved();
  };
  return (
    <div>
      <div className="row">
        <div className="field"><label>{t('Name', 'Nome')}</label><input value={d.name ?? ''} onChange={(e) => setD({ ...d, name: e.target.value })} /></div>
        <div className="field"><label>{t('Base kind', 'Tipo-base')}</label>
          <select value={d.base} onChange={(e) => setD({ ...d, base: e.target.value })}>
            {BASES.map((b) => <option key={b} value={b}>{baseLabel(b)} ({b})</option>)}
          </select></div>
        <div className="field fixed" style={{ width: 70 }}><label>{t('Color', 'Cor')}</label>
          <input type="color" value={d.color ?? BASE_COLOR[d.base ?? 'Note'] ?? '#888888'} onChange={(e) => setD({ ...d, color: e.target.value })} style={{ padding: 0, height: 30 }} /></div>
      </div>
      {d.systemKey && <p className="small muted">⚙ {t('System key', 'Chave de sistema')}: <code>{d.systemKey}</code></p>}
      <div className="field"><label>{t('Description', 'Descrição')}</label><input value={d.description ?? ''} onChange={(e) => setD({ ...d, description: e.target.value })} /></div>
      <label className="check"><input type="checkbox" checked={d.strict ?? true} onChange={(e) => setD({ ...d, strict: e.target.checked })} />
        <span>{t('Reject attributes not in this list (recommended to keep the LLM on track)', 'Rejeitar atributos fora desta lista (recomendado para manter a LLM nos trilhos)')}</span></label>
      <h4 className="small muted" style={{ margin: '12px 0 4px' }}>{t('ATTRIBUTES', 'ATRIBUTOS')}</h4>
      <AttrTable attrs={d.attributes ?? []} schema={schema} allowRefs onChange={(a) => setD({ ...d, attributes: a })} />
      <div className="row" style={{ marginTop: 12 }}>
        <button className="primary" disabled={!d.name?.trim()} onClick={save}>{t('Save type', 'Salvar tipo')}</button>
        {d.id && <button className="danger fixed" onClick={() => confirm(t(`Delete the type ${d.name}? It only works if it has no nodes.`, `Excluir o tipo ${d.name}? Só funciona se não houver nós dele.`)) &&
          run(`${t('Delete type', 'Excluir tipo')} ${d.name}`, [{ op: 'delete_node_type', id: d.id }]).then((ok) => ok && onSaved())}>{t('Delete', 'Excluir')}</button>}
      </div>
    </div>
  );
}

function ObjectTypeForm({ def, schema, run, onSaved }: { def: Partial<ObjectTypeDef>; schema: Schema; run: Run; onSaved: () => void }) {
  const [d, setD] = useState<Partial<ObjectTypeDef>>(def);
  const save = async () => {
    if (await run(`${t('Object', 'Objeto')} ${d.name}`, [{ op: 'upsert_object_type', id: d.id, definition: strip({ name: d.name, description: d.description, fields: d.fields ?? [] }) }])) onSaved();
  };
  return (
    <div>
      <p className="small muted">{t('Custom objects are reusable structures for composite attributes (e.g. an Address with street and city).',
        'Objetos customizados são estruturas reutilizáveis para atributos do tipo composite (ex.: Endereço com rua e cidade).')}</p>
      <div className="field"><label>{t('Name', 'Nome')}</label><input value={d.name ?? ''} onChange={(e) => setD({ ...d, name: e.target.value })} /></div>
      <div className="field"><label>{t('Description', 'Descrição')}</label><input value={d.description ?? ''} onChange={(e) => setD({ ...d, description: e.target.value })} /></div>
      <AttrTable attrs={d.fields ?? []} schema={schema} allowRefs={false} onChange={(a) => setD({ ...d, fields: a })} />
      <button className="primary" style={{ marginTop: 12 }} disabled={!d.name?.trim()} onClick={save}>{t('Save object', 'Salvar objeto')}</button>
    </div>
  );
}

function AttrTable({ attrs, schema, allowRefs, onChange }: { attrs: AttrDef[]; schema: Schema; allowRefs: boolean; onChange: (a: AttrDef[]) => void }) {
  const set = (i: number, patch: Partial<AttrDef>) => onChange(attrs.map((a, j) => (j === i ? { ...a, ...patch } : a)));
  const types = DATA_TYPES.filter((x) => allowRefs || x !== 'node_ref');
  const typeOptions = ['*', ...BASES.map((b) => '@' + b), ...schema.nodeTypes.map((x) => x.name)];
  return (
    <div style={{ overflowX: 'auto' }}>
      <table>
        <thead><tr>
          <th>{t('Key', 'Chave')}</th><th>{t('Label', 'Rótulo')}</th><th>{t('Type', 'Tipo')}</th><th>{t('Detail', 'Detalhe')}</th>
          <th title={t('Required', 'Obrigatório')}>{t('Req.', 'Obr.')}</th><th>{t('Default criticality', 'Criticidade padrão')}</th>
          {allowRefs && <th title={t('What the engine uses it for: link (URL of the source document, opened with a click)', 'Para que o motor usa: link (URL do documento de origem, aberta com um clique)')}>{t('Role', 'Papel')}</th>}
          <th />
        </tr></thead>
        <tbody>
          {attrs.map((a, i) => {
            const needsItem = a.dataType === 'list';
            const effective = needsItem ? a.itemType : a.dataType;
            return (
              <tr key={i}>
                <td><input value={a.key} placeholder={t('eye_color', 'cor_dos_olhos')} onChange={(e) => set(i, { key: e.target.value })} /></td>
                <td><input value={a.label ?? ''} onChange={(e) => set(i, { label: e.target.value })} /></td>
                <td>
                  <select value={a.dataType} onChange={(e) => set(i, { dataType: e.target.value })}>
                    {types.map((x) => <option key={x}>{x}</option>)}
                  </select>
                  {needsItem && (
                    <select value={a.itemType ?? ''} onChange={(e) => set(i, { itemType: e.target.value })} style={{ marginTop: 2 }}>
                      <option value="">{t('items…', 'itens…')}</option>
                      {types.filter((x) => x !== 'list').map((x) => <option key={x}>{x}</option>)}
                    </select>
                  )}
                </td>
                <td>
                  {effective === 'enum' && <input placeholder={t('option1, option2', 'opção1, opção2')} value={(a.options ?? []).join(', ')}
                    onChange={(e) => set(i, { options: e.target.value.split(',').map((s) => s.trim()).filter(Boolean) })} />}
                  {effective === 'node_ref' && (
                    <select value={a.refType ?? '*'} onChange={(e) => set(i, { refType: e.target.value })}>
                      {typeOptions.map((x) => <option key={x}>{x}</option>)}
                    </select>
                  )}
                  {effective === 'composite' && (
                    <select value={a.objectType ?? ''} onChange={(e) => set(i, { objectType: e.target.value })}>
                      <option value="">{t('object…', 'objeto…')}</option>
                      {(schema.objectTypes ?? []).map((o) => <option key={o.id}>{o.name}</option>)}
                    </select>
                  )}
                  {effective === 'string_short' && <input type="number" placeholder={t('max 255', 'máx 255')} value={a.maxLength ?? ''}
                    onChange={(e) => set(i, { maxLength: e.target.value ? Number(e.target.value) : undefined })} />}
                </td>
                <td><input type="checkbox" checked={!!a.required} onChange={(e) => set(i, { required: e.target.checked })} /></td>
                <td>
                  <select value={a.defaultCriticality ?? 'normal'} onChange={(e) => set(i, { defaultCriticality: e.target.value === 'normal' ? undefined : e.target.value })}>
                    {['normal', 'critical', 'foundational'].map((c) => <option key={c} value={c}>{critLabel(c)}</option>)}
                  </select>
                </td>
                {allowRefs && (
                  <td>
                    <select value={a.role ?? ''} onChange={(e) => set(i, { role: e.target.value || undefined })}>
                      <option value="">—</option>
                      {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
                    </select>
                  </td>
                )}
                <td><button className="ghost" title={t('Remove', 'Remover')} onClick={() => onChange(attrs.filter((_, j) => j !== i))}>✕</button></td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <button style={{ marginTop: 6 }} onClick={() => onChange([...attrs, { key: '', dataType: 'string_short' }])}>{t('+ attribute', '+ atributo')}</button>
    </div>
  );
}

function RelTypeForm({ def, schema, run, onSaved }: { def: Partial<RelTypeDef>; schema: Schema; run: Run; onSaved: () => void }) {
  const [d, setD] = useState<Partial<RelTypeDef>>(def);
  const endpoints = ['*', ...BASES.map((b) => '@' + b), ...schema.nodeTypes.map((x) => x.name)];
  const allowed = d.allowed ?? [];
  const save = async () => {
    const definition = strip({ name: d.name, base: d.base, description: d.description, symmetric: d.symmetric ?? false,
      inverseName: d.inverseName, temporal: d.temporal ?? true, allowed, systemKey: d.systemKey });
    if (await run(`${t('Relation', 'Relação')} ${d.name}`, [{ op: 'upsert_rel_type', id: d.id, definition }])) onSaved();
  };
  return (
    <div>
      <div className="row">
        <div className="field"><label>{t('Name', 'Nome')}</label><input value={d.name ?? ''} placeholder={t('HATES', 'ODEIA')} onChange={(e) => setD({ ...d, name: e.target.value })} /></div>
        <div className="field"><label>{t('Base kind', 'Tipo-base')}</label>
          <select value={d.base} onChange={(e) => setD({ ...d, base: e.target.value })}>
            {REL_BASES.map((b) => <option key={b}>{b}</option>)}
          </select></div>
      </div>
      {d.systemKey && <p className="small muted">⚙ {t('System key', 'Chave de sistema')}: <code>{d.systemKey}</code></p>}
      <div className="field"><label>{t('Description', 'Descrição')}</label><input value={d.description ?? ''} onChange={(e) => setD({ ...d, description: e.target.value })} /></div>
      <div className="row">
        <label className="check"><input type="checkbox" checked={!!d.symmetric} onChange={(e) => setD({ ...d, symmetric: e.target.checked })} /><span>{t('Symmetric', 'Simétrica')}</span></label>
        <div className="field"><label>{t('Inverse name', 'Nome inverso')}</label><input value={d.inverseName ?? ''} placeholder={t('is hated by', 'é odiado por')} onChange={(e) => setD({ ...d, inverseName: e.target.value })} /></div>
      </div>
      <h4 className="small muted" style={{ margin: '12px 0 4px' }}>{t('ALLOWED PAIRS (empty = any)', 'PARES PERMITIDOS (vazio = qualquer)')}</h4>
      {allowed.map((p, i) => (
        <div className="row" key={i} style={{ marginBottom: 4 }}>
          <select value={p.from} onChange={(e) => setD({ ...d, allowed: allowed.map((x, j) => (j === i ? { ...x, from: e.target.value } : x)) })}>
            {endpoints.map((x) => <option key={x}>{x}</option>)}
          </select>
          <span className="fixed">→</span>
          <select value={p.to} onChange={(e) => setD({ ...d, allowed: allowed.map((x, j) => (j === i ? { ...x, to: e.target.value } : x)) })}>
            {endpoints.map((x) => <option key={x}>{x}</option>)}
          </select>
          <button className="ghost fixed" onClick={() => setD({ ...d, allowed: allowed.filter((_, j) => j !== i) })}>✕</button>
        </div>
      ))}
      <button onClick={() => setD({ ...d, allowed: [...allowed, { from: '*', to: '*' }] })}>{t('+ pair', '+ par')}</button>
      <p className="small muted">{t('"@Agent" = any type with base Agent; "*" = any type.', '"@Agent" = qualquer tipo com base Agente; "*" = qualquer tipo.')}</p>
      <div className="row" style={{ marginTop: 12 }}>
        <button className="primary" disabled={!d.name?.trim()} onClick={save}>{t('Save relation', 'Salvar relação')}</button>
        {d.id && <button className="danger fixed" onClick={() => confirm(t(`Delete the relation ${d.name}?`, `Excluir a relação ${d.name}?`)) &&
          run(`${t('Delete relation', 'Excluir relação')} ${d.name}`, [{ op: 'delete_rel_type', id: d.id }]).then((ok) => ok && onSaved())}>{t('Delete', 'Excluir')}</button>}
      </div>
    </div>
  );
}
