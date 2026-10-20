import { useEffect, useState } from 'react';
import { api } from '../api';
import type { AttrDef, GNode, ObjectTypeDef } from '../types';
import { t } from '../i18n';

interface Props {
  projectId: string;
  def: AttrDef;
  value: unknown;
  objectTypes: ObjectTypeDef[];
  onChange: (v: unknown) => void;
}

/** Edit field generated from the (typed) attribute definition. */
export function AttributeInput({ projectId, def, value, objectTypes, onChange }: Props) {
  switch (def.dataType) {
    case 'int':
    case 'decimal':
      return (
        <input type="number" step={def.dataType === 'int' ? 1 : 'any'} value={value === undefined || value === null ? '' : String(value)}
          onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))} />
      );
    case 'bool':
      return <input type="checkbox" checked={value === true} onChange={(e) => onChange(e.target.checked)} />;
    case 'text_long':
      return <textarea rows={4} value={(value as string) ?? ''} onChange={(e) => onChange(e.target.value === '' ? null : e.target.value)} />;
    case 'enum':
      return (
        <select value={(value as string) ?? ''} onChange={(e) => onChange(e.target.value === '' ? null : e.target.value)}>
          <option value="">—</option>
          {def.options?.map((o) => <option key={o} value={o}>{o}</option>)}
        </select>
      );
    case 'json':
      return <JsonInput value={value} onChange={onChange} />;
    case 'node_ref':
      return <NodeRefInput projectId={projectId} refType={def.refType} multiple={false} value={value} onChange={onChange} />;
    case 'list':
      if (def.itemType === 'node_ref') {
        return <NodeRefInput projectId={projectId} refType={def.refType} multiple value={value} onChange={onChange} />;
      }
      if (def.itemType === 'composite') return <JsonInput value={value} onChange={onChange} />;
      return (
        <textarea rows={3} placeholder={t('one item per line', 'um item por linha')} value={Array.isArray(value) ? value.join('\n') : ''}
          onChange={(e) => {
            const items = e.target.value.split('\n').map((s) => s.trim()).filter(Boolean);
            const typed = def.itemType === 'int' || def.itemType === 'decimal' ? items.map(Number) : items;
            onChange(items.length ? typed : null);
          }} />
      );
    case 'composite': {
      const ot = objectTypes.find((o) => o.name.toLowerCase() === def.objectType?.toLowerCase());
      if (!ot) return <JsonInput value={value} onChange={onChange} />;
      const obj = (value as Record<string, unknown>) ?? {};
      return (
        <div style={{ paddingLeft: 8, borderLeft: '2px solid var(--border)' }}>
          {ot.fields?.map((f) => (
            <div className="field" key={f.key}>
              <label>{f.label ?? f.key}{f.required ? ' *' : ''}</label>
              <AttributeInput projectId={projectId} def={f} value={obj[f.key]} objectTypes={objectTypes}
                onChange={(v) => {
                  const next = { ...obj, [f.key]: v };
                  if (v === null || v === undefined) delete next[f.key];
                  onChange(Object.keys(next).length ? next : null);
                }} />
            </div>
          ))}
        </div>
      );
    }
    default: {
      const input = <input value={(value as string) ?? ''} maxLength={def.dataType === 'string_short' ? def.maxLength ?? 255 : undefined}
        placeholder={def.role === 'link' ? 'https://…' : undefined}
        onChange={(e) => onChange(e.target.value === '' ? null : e.target.value)} />;
      if (def.role !== 'link') return input;
      const url = typeof value === 'string' && /^https?:\/\//i.test(value) ? value : null;
      return (
        <div className="row" style={{ gap: 4 }}>
          {input}
          <a className={'button small fixed' + (url ? '' : ' disabled')} href={url ?? undefined} target="_blank" rel="noreferrer"
            title={t('Open the document', 'Abrir o documento')} aria-disabled={!url}>↗</a>
        </div>
      );
    }
  }
}

function JsonInput({ value, onChange }: { value: unknown; onChange: (v: unknown) => void }) {
  const [text, setText] = useState(value === undefined || value === null ? '' : JSON.stringify(value, null, 2));
  const [error, setError] = useState<string | null>(null);
  return (
    <>
      <textarea rows={4} value={text} style={{ fontFamily: 'monospace', fontSize: 11 }}
        onChange={(e) => {
          setText(e.target.value);
          if (e.target.value.trim() === '') { setError(null); onChange(null); return; }
          try { onChange(JSON.parse(e.target.value)); setError(null); } catch { setError(t('Invalid JSON', 'JSON inválido')); }
        }} />
      {error && <div className="small" style={{ color: 'var(--danger)' }}>{error}</div>}
    </>
  );
}

function NodeRefInput({ projectId, refType, multiple, value, onChange }: {
  projectId: string; refType?: string; multiple: boolean; value: unknown; onChange: (v: unknown) => void;
}) {
  const [options, setOptions] = useState<GNode[]>([]);
  useEffect(() => {
    const params = refType?.startsWith('@') ? { bases: [refType.slice(1)] } : refType && refType !== '*' ? { types: [refType] } : {};
    api.nodes(projectId, { ...params, limit: 500, detail: 'id' }).then(setOptions).catch(() => setOptions([]));
  }, [projectId, refType]);
  if (multiple) {
    const selected = Array.isArray(value) ? (value as string[]) : [];
    return (
      <select multiple size={Math.min(6, Math.max(3, options.length))} value={selected}
        onChange={(e) => {
          const ids = Array.from(e.target.selectedOptions).map((o) => o.value);
          onChange(ids.length ? ids : null);
        }}>
        {options.map((n) => <option key={n.id} value={n.id}>{n.name} ({n.type})</option>)}
      </select>
    );
  }
  return (
    <select value={(value as string) ?? ''} onChange={(e) => onChange(e.target.value === '' ? null : e.target.value)}>
      <option value="">—</option>
      {options.map((n) => <option key={n.id} value={n.id}>{n.name} ({n.type})</option>)}
    </select>
  );
}
