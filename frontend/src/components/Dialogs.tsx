import { useEffect, useState, type ReactNode } from 'react';
import { api } from '../api';
import { AttributeInput } from './AttributeInput';
import { CritSelect } from './Inspector';
import type { GNode, Operation, ProfileSummary, Project, Schema } from '../types';
import { SPACES, baseLabel, spaceLabel } from '../types';
import { lang, t } from '../i18n';

type Run = (title: string, ops: Operation[]) => Promise<boolean>;

export function Modal({ title, onClose, children, footer, narrow }: {
  title: string; onClose: () => void; children: ReactNode; footer?: ReactNode; narrow?: boolean;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="modal-back" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={'modal' + (narrow ? ' narrow' : '')} role="dialog" aria-label={title}>
        <header><h3>{title}</h3><button className="ghost" onClick={onClose} aria-label={t('Close', 'Fechar')}>✕</button></header>
        <div className="body">{children}</div>
        {footer && <footer>{footer}</footer>}
      </div>
    </div>
  );
}

export function LocaleSelect({ value, onChange }: { value: string; onChange: (v: 'en' | 'pt') => void }) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value as 'en' | 'pt')}>
      <option value="en">English</option>
      <option value="pt">Português</option>
    </select>
  );
}

export function NewProjectDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [profile, setProfile] = useState('livro');
  const [locale, setLocale] = useState<'en' | 'pt'>(lang());
  const [review, setReview] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [profiles, setProfiles] = useState<ProfileSummary[]>([]);
  useEffect(() => { api.profiles().then(setProfiles).catch(() => setProfiles([])); }, []);
  const chosen = profiles.find((p) => p.id === profile);
  const create = async () => {
    try {
      const p = await api.createProject({ context: 'narrative', name, description, profile, requireReviewForLlm: review, locale });
      onCreated(p.id);
    } catch (e) {
      setError((e as Error).message);
    }
  };
  return (
    <Modal title={t('New project', 'Novo projeto')} onClose={onClose} narrow
      footer={<><button onClick={onClose}>{t('Cancel', 'Cancelar')}</button><button className="primary" disabled={!name.trim()} onClick={create}>{t('Create', 'Criar')}</button></>}>
      <div className="field"><label>{t('Name', 'Nome')}</label><input autoFocus value={name} onChange={(e) => setName(e.target.value)} /></div>
      <div className="field"><label>{t('Description', 'Descrição')}</label><textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></div>
      <div className="row">
        <div className="field"><label>{t('Production profile', 'Perfil de produção')}</label>
          <select value={profile} onChange={(e) => setProfile(e.target.value)}>
            {profiles.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            <option value="personalizado">{t('Custom (empty schema)', 'Personalizado (schema vazio)')}</option>
          </select></div>
        <div className="field fixed" style={{ width: 140 }}><label>{t('Project language', 'Idioma do projeto')}</label>
          <LocaleSelect value={locale} onChange={setLocale} /></div>
      </div>
      {chosen && (
        <div className="hint">
          {chosen.description}<br />
          {t('Structure', 'Estrutura')}: {chosen.units?.join(' › ')} · {t('suggested templates', 'templates sugeridos')}: {chosen.suggestedTemplates?.join(', ')}
        </div>
      )}
      <p className="hint">{t('The language of the schema (types, attributes, relations) and of what the LLM writes. It can be converted later.',
        'Idioma do schema (tipos, atributos, relações) e do que a LLM escreve. Dá para converter depois.')}</p>
      <label className="check"><input type="checkbox" checked={review} onChange={(e) => setReview(e.target.checked)} />
        <span>{t('LLM changes stay pending until I approve them', 'Mudanças da LLM ficam pendentes até eu aprovar')}</span></label>
      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}
    </Modal>
  );
}

export function CloneProjectDialog({ project, onClose, onCloned }: { project: Project; onClose: () => void; onCloned: (id: string, notes: string[]) => void }) {
  const [name, setName] = useState(t('Copy of ', 'Cópia de ') + project.name);
  const [translateTo, setTranslateTo] = useState('');
  const [inc, setInc] = useState({ includeUnits: true, includeFacts: true, includeDirectives: true, includeTemplates: true });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const go = async () => {
    setBusy(true); setError(null);
    try {
      const r = await api.cloneProject(project.id, { newName: name, ...inc, translateTo: translateTo || undefined });
      onCloned(r.projectId, r.notes ?? []);
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };
  const box = (k: keyof typeof inc, label: string) => (
    <label className="check"><input type="checkbox" checked={inc[k]} onChange={(e) => setInc({ ...inc, [k]: e.target.checked })} /><span>{label}</span></label>
  );
  return (
    <Modal title={t('Clone project', 'Clonar projeto')} onClose={onClose} narrow
      footer={<><button onClick={onClose}>{t('Cancel', 'Cancelar')}</button><button className="primary" disabled={!name.trim() || busy} onClick={go}>{busy ? '…' : t('Clone', 'Clonar')}</button></>}>
      <p className="small muted">{t('Copies the schema, nodes, edges (with time anchors), facts, directives and text history with new ids. The original is not touched.',
        'Copia schema, nós, arestas (com âncoras no tempo), fatos, diretivas e histórico de texto com ids novos. O original não é alterado.')}</p>
      <div className="field"><label>{t('Name of the copy', 'Nome da cópia')}</label><input autoFocus value={name} onChange={(e) => setName(e.target.value)} /></div>
      {box('includeUnits', t('Narrative units (chapters, scenes…)', 'Unidades narrativas (capítulos, cenas…)'))}
      {box('includeFacts', t('Manual facts', 'Fatos manuais'))}
      {box('includeDirectives', t('Project directives', 'Diretivas do projeto'))}
      {box('includeTemplates', t('Applied templates and beats', 'Templates aplicados e beats'))}
      <div className="field" style={{ marginTop: 8 }}><label>{t('Translate the copy\'s schema', 'Traduzir o schema da cópia')}</label>
        <select value={translateTo} onChange={(e) => setTranslateTo(e.target.value)}>
          <option value="">{t('No (keep ', 'Não (manter ')}{project.locale ?? 'pt'})</option>
          {project.locale !== 'en' && <option value="en">English</option>}
          {project.locale !== 'pt' && <option value="pt">Português</option>}
        </select>
        <div className="hint">{t('Types, attribute keys, enum values, relations and templates are converted; the content (names, summaries, prose) stays as it is.',
          'Tipos, chaves de atributo, valores de enum, relações e templates são convertidos; o conteúdo (nomes, resumos, prosa) fica como está.')}</div>
      </div>
      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}
    </Modal>
  );
}

export function NewNodeDialog({ projectId, schema, defaultSpace, run, onClose, onCreated }: {
  projectId: string; schema: Schema; defaultSpace?: string; run: Run; onClose: () => void; onCreated: (id: string) => void;
}) {
  const [typeName, setTypeName] = useState(schema.nodeTypes[0]?.name ?? '');
  const [name, setName] = useState('');
  const [summary, setSummary] = useState('');
  const [space, setSpace] = useState<string>('');
  const [criticality, setCriticality] = useState('normal');
  const [attrs, setAttrs] = useState<Record<string, unknown>>({});
  const [parent, setParent] = useState('');
  const [units, setUnits] = useState<GNode[]>([]);
  const type = schema.nodeTypes.find((x) => x.name === typeName);
  useEffect(() => {
    if (type?.base === 'Unit') api.nodes(projectId, { bases: ['Unit'], limit: 1000, detail: 'id' }).then(setUnits).catch(() => setUnits([]));
  }, [projectId, type?.base]);
  useEffect(() => setSpace(''), [typeName]);

  if (schema.nodeTypes.length === 0) {
    return <Modal title={t('New node', 'Novo nó')} onClose={onClose} narrow><p>{t('Create a node type first (Schema button).', 'Crie um tipo de nó primeiro (botão Schema).')}</p></Modal>;
  }
  const create = async () => {
    const clean = Object.fromEntries(Object.entries(attrs).filter(([, v]) => v !== null && v !== undefined));
    const op: Operation = { op: 'create_node', ref: '$new', type: typeName, name, summary: summary || undefined, criticality, attributes: clean };
    if (space) op.space = space;
    else if (defaultSpace && type?.base !== 'Unit') op.space = defaultSpace;
    if (parent) op.parent = parent;
    const ok = await run(`${t('Create', 'Criar')} ${name}`, [op]);
    if (ok) onCreated('');
  };
  // the manuscript is written in the unit's own editor, not here
  const attrDefs = (type?.attributes ?? []).filter((d) => d.role !== 'manuscript' && d.role !== 'word_count');
  return (
    <Modal title={t('New node', 'Novo nó')} onClose={onClose}
      footer={<><button onClick={onClose}>{t('Cancel', 'Cancelar')}</button><button className="primary" disabled={!name.trim() || !typeName} onClick={create}>{t('Create', 'Criar')}</button></>}>
      <div className="row">
        <div className="field"><label>{t('Type', 'Tipo')}</label>
          <select value={typeName} onChange={(e) => { setTypeName(e.target.value); setAttrs({}); }}>
            {schema.nodeTypes.map((x) => <option key={x.id} value={x.name}>{x.name} ({baseLabel(x.base)})</option>)}
          </select></div>
        <div className="field"><label>{t('Space', 'Espaço')}</label>
          <select value={space} onChange={(e) => setSpace(e.target.value)}>
            <option value="">{defaultSpace && type?.base !== 'Unit' ? `${spaceLabel(defaultSpace)} ${t('(current tab)', '(aba atual)')}` : t('Type default', 'Padrão do tipo')}</option>
            {SPACES.map((s) => <option key={s} value={s}>{spaceLabel(s)}</option>)}
          </select></div>
        <div className="field"><label>{t('Criticality', 'Criticidade')}</label><CritSelect value={criticality} onChange={setCriticality} /></div>
      </div>
      <div className="field"><label>{t('Name', 'Nome')}</label><input autoFocus value={name} onChange={(e) => setName(e.target.value)} /></div>
      <div className="field"><label>{t('Summary', 'Resumo')}</label><textarea rows={2} value={summary} onChange={(e) => setSummary(e.target.value)} /></div>
      {type?.base === 'Unit' && (
        <div className="field"><label>{t('Inside', 'Dentro de')}</label>
          <select value={parent} onChange={(e) => setParent(e.target.value)}>
            <option value="">{t('(root)', '(raiz)')}</option>
            {units.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
          </select></div>
      )}
      {attrDefs.map((def) => (
        <div className="field" key={def.key}>
          <label>{def.label ?? def.key}{def.required ? ' *' : ''} <span className="muted">({def.dataType})</span></label>
          <AttributeInput projectId={projectId} def={def} value={attrs[def.key]} objectTypes={schema.objectTypes ?? []}
            onChange={(v) => setAttrs({ ...attrs, [def.key]: v })} />
        </div>
      ))}
    </Modal>
  );
}

export function NewEdgeDialog({ schema, from, to, run, onClose }: {
  schema: Schema; from: GNode; to: GNode; run: Run; onClose: () => void;
}) {
  const [a, setA] = useState(from);
  const [b, setB] = useState(to);
  const allowedFor = (x: GNode, y: GNode) => schema.relTypes.filter((r) => r.name !== 'CONTAINS' && (
    !r.allowed?.length || r.allowed.some((p) => (match(p.from, x) && match(p.to, y)) || (r.symmetric && match(p.from, y) && match(p.to, x)))));
  const match = (pattern: string, n: GNode) => pattern === '*' || (pattern.startsWith('@') ? pattern.slice(1) === n.base : pattern.toLowerCase() === n.type.toLowerCase());
  const options = allowedFor(a, b);
  const [rel, setRel] = useState(options[0]?.name ?? '');
  const [description, setDescription] = useState('');
  const [criticality, setCriticality] = useState('normal');
  useEffect(() => { if (!options.some((o) => o.name === rel)) setRel(options[0]?.name ?? ''); }, [a, b]); // eslint-disable-line
  const create = async () => {
    const ok = await run(`${t('Relation', 'Relação')} ${rel}`, [{ op: 'create_edge', relType: rel, from: a.id, to: b.id, description: description || undefined, criticality }]);
    if (ok) onClose();
  };
  return (
    <Modal title={t('New relation', 'Nova relação')} onClose={onClose} narrow
      footer={<><button onClick={onClose}>{t('Cancel', 'Cancelar')}</button><button className="primary" disabled={!rel} onClick={create}>{t('Create', 'Criar')}</button></>}>
      <p className="row"><b>{a.name}</b><button className="fixed" title={t('Swap', 'Inverter')} onClick={() => { setA(b); setB(a); }}>⇄</button><b style={{ textAlign: 'right' }}>{b.name}</b></p>
      {options.length === 0
        ? <p className="muted">{t('No relation type allows', 'Nenhum tipo de relação permite')} {a.type} → {b.type}. {t('Adjust the allowed pairs in Schema.', 'Ajuste os pares permitidos em Schema.')}</p>
        : (
          <>
            <div className="field"><label>{t('Relation type', 'Tipo de relação')}</label>
              <select value={rel} onChange={(e) => setRel(e.target.value)}>
                {options.map((r) => <option key={r.id} value={r.name}>{r.name} ({r.base})</option>)}
              </select></div>
            <div className="field"><label>{t('Description', 'Descrição')}</label><textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></div>
            <div className="field"><label>{t('Criticality', 'Criticidade')}</label><CritSelect value={criticality} onChange={setCriticality} /></div>
            <p className="small muted">{t('Time anchors (holds from/until which chapter) can be set later by selecting the relation.',
              'Âncoras no tempo (vale de/até qual capítulo) podem ser definidas depois, selecionando a relação.')}</p>
          </>
        )}
    </Modal>
  );
}
