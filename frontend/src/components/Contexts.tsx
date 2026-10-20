import { useEffect, useState } from 'react';
import { api } from '../api';
import type { ContextView, Project } from '../types';
import { spaceLabel } from '../types';
import { lang, t } from '../i18n';
import { LocaleSelect, Modal } from './Dialogs';

const SPACES = ['world', 'characters', 'story', 'analysis', 'templates'];
const ICONS = ['🏢', '💳', '🤝', '⚖️', '🧪', '🏥', '🛒', '🎓', '🛠️', '📚', '🌍', '🎮', '📖', '🧩'];

/** Label of a space in a context (the context's own name for it, or the default). */
export const ctxSpaceLabel = (ctx: ContextView | undefined, space: string) => ctx?.spaceLabels?.[space] ?? spaceLabel(space);

// ------------------------------------------------------------------ home: choose or create a context

export function ContextHome({ contexts, onOpen, onCreate }: {
  contexts: ContextView[]; onOpen: (id: string) => void; onCreate: () => void;
}) {
  return (
    <div className="home">
      <div className="home-head">
        <h1>{t('Choose a context', 'Escolha um contexto')}</h1>
        <p className="muted">{t(
          'A context is a domain the platform runs: which kinds of nodes exist, how they relate, which rules are binding and how the AI must behave. Projects live inside a context, and any Claude connected through MCP can use them.',
          'Um contexto é um domínio que a plataforma roda: que tipos de nó existem, como se relacionam, quais regras são vinculantes e como a IA deve se comportar. Os projetos vivem dentro de um contexto, e qualquer Claude conectado via MCP pode usá-los.')}</p>
      </div>
      <div className="cards">
        {contexts.map((c) => (
          <button key={c.id} className="ctx-card" style={{ borderTopColor: c.color ?? 'var(--accent)' }} onClick={() => onOpen(c.id)}>
            <span className="ctx-icon" aria-hidden>{c.icon ?? '🧩'}</span>
            <span className="ctx-name">{c.name}</span>
            <span className="ctx-desc">{c.description}</span>
            <span className="ctx-meta">
              {c.kind === 'builtin' ? t('Built-in', 'Embutido') : t('Custom', 'Personalizado')}
              {' · '}{c.projectCount} {c.projectCount === 1 ? t('project', 'projeto') : t('projects', 'projetos')}
              {c.kind === 'custom' && c.locale && ` · ${c.locale.toUpperCase()}`}
            </span>
          </button>
        ))}
        <button className="ctx-card new" onClick={onCreate}>
          <span className="ctx-icon" aria-hidden>＋</span>
          <span className="ctx-name">{t('New context', 'Novo contexto')}</span>
          <span className="ctx-desc">{t('Define your own domain: node types, relations, rules and instructions for the AI. Start blank or from a built-in context.',
            'Defina o seu domínio: tipos de nó, relações, regras e instruções para a IA. Comece em branco ou a partir de um contexto embutido.')}</span>
        </button>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ one context: its projects and definition

export function ContextPage({ ctx, projects, onOpenProject, onNewProject, onEditDefinition, onSettings, onDerive, onBack }: {
  ctx: ContextView; projects: Project[];
  onOpenProject: (id: string) => void; onNewProject: () => void; onEditDefinition: () => void; onSettings: () => void;
  onDerive: () => void; onBack: () => void;
}) {
  return (
    <div className="home">
      <div className="home-head">
        <button className="ghost small" onClick={onBack}>← {t('All contexts', 'Todos os contextos')}</button>
        <h1><span aria-hidden>{ctx.icon}</span> {ctx.name}</h1>
        {ctx.description && <p className="muted">{ctx.description}</p>}
        <div className="row" style={{ flexWrap: 'wrap', gap: 6, justifyContent: 'flex-start' }}>
          <button className="primary fixed" onClick={onNewProject}>{t('+ New project', '+ Novo projeto')}</button>
          {ctx.kind === 'custom' ? (
            <>
              <button className="fixed" onClick={onEditDefinition} title={t('Node types, relations, attributes, rules and directives every new project of this context gets',
                'Tipos de nó, relações, atributos, regras e diretivas que todo projeto novo deste contexto recebe')}>
                ✎ {t('Edit definition (types & rules)', 'Editar definição (tipos e regras)')}</button>
              <button className="fixed" onClick={onSettings}>⚙ {t('Settings', 'Configurações')}</button>
            </>
          ) : (
            <button className="fixed" onClick={onDerive} title={t('Built-in contexts are read-only: create your own copy and adapt it',
              'Contextos embutidos são somente leitura: crie a sua cópia e adapte')}>⎘ {t('Customize (create a context based on this)', 'Personalizar (criar um contexto baseado neste)')}</button>
          )}
        </div>
      </div>

      <div className="ctx-body">
        <section>
          <h4 className="sec">{t('Projects', 'Projetos')}</h4>
          {projects.length === 0 ? (
            <div className="list-item muted">{t('No project in this context yet.', 'Nenhum projeto neste contexto ainda.')}</div>
          ) : (
            <div className="cards small-cards">
              {projects.map((p) => (
                <button key={p.id} className="ctx-card proj" onClick={() => onOpenProject(p.id)}>
                  <span className="ctx-name">{p.name}</span>
                  {p.description && <span className="ctx-desc">{p.description}</span>}
                  <span className="ctx-meta">{(p.locale ?? 'pt').toUpperCase()}{p.profile && ctx.presets && ctx.presets.length > 1 ? ` · ${ctx.presets.find((x) => x.id === p.profile)?.name ?? p.profile}` : ''}
                    {p.createdAt && ` · ${p.createdAt.slice(0, 10)}`}</span>
                </button>
              ))}
            </div>
          )}
        </section>
        <aside className="ctx-side">
          <h4 className="sec">{t('How the AI works here', 'Como a IA trabalha aqui')}</h4>
          <p className="small">{ctx.instructions || <span className="muted">{t('No instructions yet.', 'Sem instruções ainda.')}</span>}</p>
          <h4 className="sec">{t('Spaces', 'Espaços')}</h4>
          <div className="chips">{ctx.tabs.map((s) => <span key={s} className="chip">{ctxSpaceLabel(ctx, s)}</span>)}</div>
          <h4 className="sec">{t('Connect a Claude (MCP)', 'Conectar um Claude (MCP)')}</h4>
          <pre className="json">claude mcp add --transport http obliterate http://localhost:8180/mcp</pre>
          <p className="small muted">{t('Then ask it to use context', 'Depois peça para ele usar o contexto')} <code>{ctx.id}</code>
            {t(' (context_list → project_list → session_begin).', ' (context_list → project_list → session_begin).')}</p>
          {ctx.kind === 'custom' && ctx.baseContext && <p className="small muted">{t('Based on', 'Baseado em')}: <code>{ctx.baseContext}</code></p>}
        </aside>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ dialogs

export function NewContextDialog({ contexts, initialBase, onClose, onCreated }: {
  contexts: ContextView[]; initialBase?: string; onClose: () => void; onCreated: (c: ContextView) => void;
}) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [icon, setIcon] = useState('🏢');
  const [locale, setLocale] = useState<'en' | 'pt'>(lang());
  const [base, setBase] = useState(initialBase ?? 'corporate');
  const baseCtx = contexts.find((c) => c.id === base);
  const [preset, setPreset] = useState<string>('');
  const [instructions, setInstructions] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { setPreset(baseCtx?.defaultPreset ?? ''); }, [base]); // eslint-disable-line
  const create = async () => {
    setBusy(true); setError(null);
    try {
      const c = await api.createContext({ name, description, icon, locale, baseContext: base || undefined, basePreset: preset || undefined,
        instructions: instructions || undefined });
      onCreated(c);
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };
  return (
    <Modal title={t('New context', 'Novo contexto')} onClose={onClose} narrow
      footer={<><button onClick={onClose}>{t('Cancel', 'Cancelar')}</button>
        <button className="primary" disabled={!name.trim() || busy} onClick={create}>{busy ? t('Creating…', 'Criando…') : t('Create', 'Criar')}</button></>}>
      <div className="row">
        <div className="field fixed" style={{ width: 70 }}><label>{t('Icon', 'Ícone')}</label>
          <input value={icon} onChange={(e) => setIcon(e.target.value)} maxLength={4} style={{ textAlign: 'center', fontSize: 18 }} /></div>
        <div className="field"><label>{t('Name', 'Nome')}</label>
          <input autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder={t('e.g. Payments ops brain', 'ex.: Cérebro de operações de pagamento')} /></div>
      </div>
      <div className="chips" style={{ marginBottom: 8 }}>
        {ICONS.map((i) => <button key={i} className={'ghost' + (i === icon ? ' active' : '')} onClick={() => setIcon(i)} aria-label={i}>{i}</button>)}
      </div>
      <div className="field"><label>{t('What is it for?', 'Para que serve?')}</label>
        <textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></div>
      <div className="row">
        <div className="field"><label>{t('Start from', 'Começar a partir de')}</label>
          <select value={base} onChange={(e) => setBase(e.target.value)}>
            <option value="">{t('Blank (I will define every type)', 'Em branco (vou definir todos os tipos)')}</option>
            {contexts.map((c) => <option key={c.id} value={c.id}>{c.icon} {c.name}</option>)}
          </select></div>
        <div className="field fixed" style={{ width: 140 }}><label>{t('Language', 'Idioma')}</label>
          <LocaleSelect value={locale} onChange={setLocale} /></div>
      </div>
      {baseCtx?.presets && baseCtx.presets.length > 1 && (
        <div className="field"><label>{t('Preset', 'Preset')}</label>
          <select value={preset} onChange={(e) => setPreset(e.target.value)}>
            {baseCtx.presets.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select></div>
      )}
      <p className="hint">{base
        ? t('The new context starts with a copy of the types, relations, rules and directives of the base; you adapt them afterwards in "Edit definition".',
          'O novo contexto começa com uma cópia dos tipos, relações, regras e diretivas da base; depois você adapta em "Editar definição".')
        : t('The new context starts empty: define its node types and relations in "Edit definition" (or ask a Claude to do it through MCP).',
          'O novo contexto começa vazio: defina os tipos de nó e relações em "Editar definição" (ou peça para um Claude fazer via MCP).')}</p>
      <div className="field"><label>{t('Instructions for the AI (optional)', 'Instruções para a IA (opcional)')}</label>
        <textarea rows={3} value={instructions} onChange={(e) => setInstructions(e.target.value)}
          placeholder={t('How any AI working in this context must behave. Empty = take the base context\'s.', 'Como qualquer IA trabalhando neste contexto deve se comportar. Vazio = usar as do contexto base.')} /></div>
      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}
    </Modal>
  );
}

export function NewProjectInContextDialog({ ctx, onClose, onCreated }: { ctx: ContextView; onClose: () => void; onCreated: (p: Project) => void }) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [locale, setLocale] = useState<'en' | 'pt'>(ctx.locale ?? lang());
  const [preset, setPreset] = useState(ctx.defaultPreset ?? '');
  const [review, setReview] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const chosen = ctx.presets?.find((p) => p.id === preset);
  const create = async () => {
    setBusy(true); setError(null);
    try {
      onCreated(await api.createProjectIn(ctx.id, { name, description, locale, preset: preset || undefined, requireReviewForLlm: review }));
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };
  return (
    <Modal title={`${t('New project in', 'Novo projeto em')} ${ctx.icon ?? ''} ${ctx.name}`} onClose={onClose} narrow
      footer={<><button onClick={onClose}>{t('Cancel', 'Cancelar')}</button>
        <button className="primary" disabled={!name.trim() || busy} onClick={create}>{t('Create', 'Criar')}</button></>}>
      <div className="field"><label>{t('Name', 'Nome')}</label><input autoFocus value={name} onChange={(e) => setName(e.target.value)} /></div>
      <div className="field"><label>{t('Description', 'Descrição')}</label><textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></div>
      <div className="row">
        {ctx.presets && ctx.presets.length > 1 && (
          <div className="field"><label>{t('Preset', 'Preset')}</label>
            <select value={preset} onChange={(e) => setPreset(e.target.value)}>
              {ctx.presets.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select></div>
        )}
        <div className="field fixed" style={{ width: 140 }}><label>{t('Language', 'Idioma')}</label>
          {ctx.kind === 'custom'
            ? <input value={ctx.locale === 'pt' ? 'Português' : 'English'} disabled title={t('Same as the context', 'Igual ao do contexto')} />
            : <LocaleSelect value={locale} onChange={setLocale} />}
        </div>
      </div>
      {chosen?.description && <p className="hint">{chosen.description}</p>}
      <p className="hint">{t('The project is born with the context\'s node types, relations, rules and directives.',
        'O projeto nasce com os tipos de nó, relações, regras e diretivas do contexto.')}</p>
      <label className="check"><input type="checkbox" checked={review} onChange={(e) => setReview(e.target.checked)} />
        <span>{t('LLM changes stay pending until I approve them', 'Mudanças da LLM ficam pendentes até eu aprovar')}</span></label>
      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}
    </Modal>
  );
}

export function ContextSettingsDialog({ ctx, onClose, onSaved, onDeleted }: {
  ctx: ContextView; onClose: () => void; onSaved: (c: ContextView) => void; onDeleted: () => void;
}) {
  const [name, setName] = useState(ctx.name);
  const [description, setDescription] = useState(ctx.description ?? '');
  const [icon, setIcon] = useState(ctx.icon ?? '🧩');
  const [instructions, setInstructions] = useState(ctx.instructions ?? '');
  const [tabs, setTabs] = useState<string[]>(ctx.tabs);
  const [labels, setLabels] = useState<Record<string, string>>(ctx.spaceLabels ?? {});
  const [error, setError] = useState<string | null>(null);
  const save = async () => {
    try {
      const clean = Object.fromEntries(Object.entries(labels).filter(([, v]) => v.trim()));
      onSaved(await api.updateContext(ctx.id, { name, description, icon, instructions, tabs: SPACES.filter((s) => tabs.includes(s)), spaceLabels: clean }));
    } catch (e) {
      setError((e as Error).message);
    }
  };
  const remove = async () => {
    const typed = prompt(t(`Delete the context "${ctx.name}" and its definition? This cannot be undone.\nType the name to confirm:`,
      `Excluir o contexto "${ctx.name}" e sua definição? Não tem volta.\nDigite o nome para confirmar:`));
    if (typed === null) return;
    try {
      await api.deleteContext(ctx.id, typed);
      onDeleted();
    } catch (e) {
      setError((e as Error).message);
    }
  };
  return (
    <Modal title={t('Context settings', 'Configurações do contexto')} onClose={onClose} narrow
      footer={<><button className="danger" style={{ marginRight: 'auto' }} onClick={remove}>{t('Delete context…', 'Excluir contexto…')}</button>
        <button onClick={onClose}>{t('Cancel', 'Cancelar')}</button><button className="primary" onClick={save}>{t('Save', 'Salvar')}</button></>}>
      <div className="row">
        <div className="field fixed" style={{ width: 70 }}><label>{t('Icon', 'Ícone')}</label>
          <input value={icon} onChange={(e) => setIcon(e.target.value)} maxLength={4} style={{ textAlign: 'center', fontSize: 18 }} /></div>
        <div className="field"><label>{t('Name', 'Nome')}</label><input value={name} onChange={(e) => setName(e.target.value)} /></div>
      </div>
      <div className="field"><label>{t('Description', 'Descrição')}</label><textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></div>
      <div className="field"><label>{t('Instructions for the AI', 'Instruções para a IA')}</label>
        <textarea rows={4} value={instructions} onChange={(e) => setInstructions(e.target.value)} /></div>
      <div className="field"><label>{t('Spaces shown as tabs, and how this context calls them', 'Espaços mostrados como abas, e como este contexto os chama')}</label>
        {SPACES.map((s) => (
          <div className="row" key={s} style={{ marginBottom: 4 }}>
            <label className="check fixed" style={{ width: 150 }}>
              <input type="checkbox" checked={tabs.includes(s)} onChange={(e) => setTabs(e.target.checked ? [...tabs, s] : tabs.filter((x) => x !== s))} />
              <span>{spaceLabel(s)}</span>
            </label>
            <input value={labels[s] ?? ''} placeholder={spaceLabel(s)} onChange={(e) => setLabels({ ...labels, [s]: e.target.value })} />
          </div>
        ))}
        <p className="hint">{t('Node types land in a space by their base (agents → characters; places, objects, concepts, documents → world; units → story).',
          'Os tipos de nó caem num espaço pela base (agentes → personagens; lugares, objetos, conceitos, documentos → mundo; unidades → roteiro).')}</p>
      </div>
      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}
    </Modal>
  );
}
