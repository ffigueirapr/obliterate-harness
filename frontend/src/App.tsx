import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from './api';
import { BASE_COLOR, GraphCanvas, type GraphCanvasHandle, type SelectMode } from './components/GraphCanvas';
import { FilterPanel } from './components/FilterPanel';
import { BulkEditor, EdgeEditor, IndirectInfo, NodeEditor } from './components/Inspector';
import { CloneProjectDialog, NewEdgeDialog, NewNodeDialog } from './components/Dialogs';
import { SchemaEditor } from './components/SchemaEditor';
import { AuditPanel, ChangesetsPanel, FactsPanel } from './components/Panels';
import { DirectivesPanel } from './components/DirectivesPanel';
import { ContextHome, ContextPage, ContextSettingsDialog, NewContextDialog, NewProjectInContextDialog, ctxSpaceLabel } from './components/Contexts';
import { ArchiveActions, ProjectName, RenameButton, RenameInput, importSummary } from './components/Archive';
import { LAYOUT_KINDS, layoutHint, layoutLabel, type LayoutKind } from './components/graphLayout';
import type { ContextView, FocusState, ImportResult, IndirectLink, Operation, Project, Schema, ViewFilter, ViewResult } from './types';
import { baseLabel } from './types';
import { lang, setLang, t, type Lang } from './i18n';

const EMPTY_FILTER: ViewFilter = { types: [], bases: [], tags: [], spaces: [], minCriticality: '', includeIndirect: false, maxHops: 3, relTypes: [] };
type Tab = 'all' | 'actors' | 'knowledge' | 'analysis';
type ModalKind = 'schema' | 'changesets' | 'facts' | 'audit' | 'newNode' | 'newEdge' | 'newProject' | 'clone'
  | 'directives' | 'newContext' | 'ctxSettings' | null;
const ctxOf = (p?: Project) => p?.context;

function remember(key: string, value?: string) {
  try {
    if (value === undefined) return localStorage.getItem(key) ?? undefined;
    localStorage.setItem(key, value);
  } catch {
    /* storage unavailable */
  }
  return undefined;
}

export function App() {
  const [uiLang, setUiLang] = useState<Lang>(lang());
  const [projects, setProjects] = useState<Project[]>([]);
  const [contexts, setContexts] = useState<ContextView[]>([]);
  const [contextId, setContextId] = useState<string | undefined>(() => remember('hm.context') || undefined);
  const [deriveBase, setDeriveBase] = useState<string | undefined>();
  const [projectId, setProjectId] = useState<string | undefined>(() => remember('hm.project') || undefined);
  const [schema, setSchema] = useState<Schema>({ nodeTypes: [], relTypes: [] });
  const [view, setView] = useState<ViewResult | null>(null);
  const [tab, setTab] = useState<Tab>('all');
  const [filter, setFilter] = useState<ViewFilter>(EMPTY_FILTER);
  const [mode, setMode] = useState<SelectMode>('pan');
  const [selNodes, setSelNodes] = useState<string[]>([]);
  const [selEdges, setSelEdges] = useState<string[]>([]);
  const [selIndirect, setSelIndirect] = useState<IndirectLink | null>(null);
  const [focusState, setFocusState] = useState<FocusState | null>(null);
  const [focusInfo, setFocusInfo] = useState<{ levels: number; byLevel: Record<string, number>; truncated: boolean } | null>(null);
  const [distance, setDistance] = useState<Record<string, number> | undefined>();
  const [modal, setModal] = useState<ModalKind>(null);
  const [toast, setToast] = useState<{ text: string; error?: boolean } | null>(null);
  const [version, setVersion] = useState(0);
  const [pending, setPending] = useState(0);
  const [renamingTop, setRenamingTop] = useState(false);
  const canvas = useRef<GraphCanvasHandle>(null);

  const notify = useCallback((text: string, error = false) => {
    setToast({ text, error });
    setTimeout(() => setToast(null), error ? 7000 : 3000);
  }, []);

  const switchLang = (l: Lang) => {
    setLang(l);
    setUiLang(l);
    setVersion((v) => v + 1); // reload what the server words in the UI language
  };
  useEffect(() => { document.documentElement.lang = uiLang === 'pt' ? 'pt-BR' : 'en'; }, [uiLang]);

  const reloadProjects = useCallback(() => api.projects().then((ps) => { setProjects(ps); return ps; }), []);
  const reloadContexts = useCallback(() => api.contexts().then((cs) => { setContexts(cs); return cs; }), []);

  useEffect(() => {
    Promise.all([reloadProjects(), reloadContexts()]).then(([ps, cs]) => {
      const p = ps.find((x) => x.id === projectId);
      if (projectId && !p) setProjectId(undefined);
      if (p) setContextId(ctxOf(p));
      else if (contextId && !cs.some((c) => c.id === contextId)) setContextId(undefined);
    }).catch((e) => notify(t('API unavailable: ', 'API indisponível: ') + e.message, true));
  }, []); // eslint-disable-line

  useEffect(() => { remember('hm.project', projectId ?? ''); }, [projectId]);
  useEffect(() => { remember('hm.context', contextId ?? ''); }, [contextId]);
  // context names and labels follow the interface language
  useEffect(() => { reloadContexts().catch(() => undefined); }, [uiLang, reloadContexts]);

  // schema and pending changesets
  useEffect(() => {
    if (!projectId) return;
    api.schema(projectId).then(setSchema).catch((e) => notify(e.message, true));
    api.changesets(projectId, 'pending').then((r) => setPending(r.length)).catch(() => setPending(0));
  }, [projectId, version, notify]);

  // graph view: focus on a node (it and what is linked to it) or the filtered view
  useEffect(() => {
    if (!projectId) return;
    const h = setTimeout(() => {
      if (focusState) {
        api.focus(projectId, focusState.nodeId, focusState.all).then((r) => {
          setDistance(r.distance);
          setFocusInfo({ levels: r.levels, byLevel: r.byLevel ?? {}, truncated: r.truncated });
          setView({ nodes: r.nodes, edges: r.edges, indirect: [], contextIds: [], total: r.nodes.length, truncated: r.truncated });
        }).catch((e) => { notify(e.message, true); setFocusState(null); });
        return;
      }
      setDistance(undefined);
      const f = { ...filter, spaces: tab === 'all' ? [] : [tab] };
      api.view(projectId, f, false).then(setView).catch((e) => notify(e.message, true));
    }, 150);
    return () => clearTimeout(h);
  }, [projectId, filter, tab, version, notify, focusState]);

  useEffect(() => {
    setSelNodes([]); setSelEdges([]); setSelIndirect(null); setFocusState(null);
  }, [projectId]);

  // Esc leaves the focus (when no dialog is open)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !modal && focusState) setFocusState(null);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [modal, focusState]);

  const run = useCallback(async (title: string, ops: Operation[]) => {
    if (!projectId) return false;
    try {
      const res = await api.propose(projectId, title, ops);
      notify(res.status === 'applied' ? `✓ ${title}` : `${title}: ${t('pending review', 'pendente de revisão')}`);
      setVersion((v) => v + 1);
      return true;
    } catch (e) {
      notify((e as Error).message, true);
      return false;
    }
  }, [projectId, notify]);

  const nodesById = useMemo(() => new Map((view?.nodes ?? []).map((n) => [n.id, n])), [view]);
  const knownTags = useMemo(() => [...new Set((view?.nodes ?? []).flatMap((n) => n.tags ?? []))], [view]);
  const project = projects.find((p) => p.id === projectId);
  const ctx = contexts.find((c) => c.id === (project ? ctxOf(project) : contextId));
  const isBlueprint = !!project?.blueprint;
  const tabs: Tab[] = ['all', ...((ctx?.tabs ?? ['actors', 'knowledge', 'analysis']) as Tab[])];
  const ctxProjects = useMemo(() => projects.filter((p) => !p.blueprint && ctxOf(p) === (ctx?.id ?? contextId)), [projects, ctx, contextId]);
  useEffect(() => { if (!tabs.includes(tab)) setTab('all'); }, [ctx?.id]); // eslint-disable-line
  const openProject = (id: string) => { setTab('all'); setFilter(EMPTY_FILTER); setProjectId(id); };
  const goContext = (id?: string) => { setProjectId(undefined); setContextId(id); };

  const focus = useCallback((id: string) => {
    if (!nodesById.has(id)) {
      // outside the current view: leave focus and clear filters so it shows up
      setFocusState(null);
      setFilter(EMPTY_FILTER);
      setTab('all');
      setTimeout(() => canvas.current?.focus(id), 700);
    } else {
      canvas.current?.focus(id);
    }
  }, [nodesById]);

  const onSelectionChange = useCallback((n: string[], e: string[], ind: IndirectLink | null) => {
    setSelNodes(n); setSelEdges(e); setSelIndirect(ind);
  }, []);

  const selectedNodes = selNodes.map((id) => nodesById.get(id)).filter((n): n is NonNullable<typeof n> => !!n);
  const startFocus = useCallback((id: string, all: boolean) => {
    const n = nodesById.get(id);
    setFocusState({ nodeId: id, name: n?.name ?? id, all });
  }, [nodesById]);
  const selectedEdge = selEdges.length === 1 && selNodes.length === 0 ? view?.edges.find((e) => e.id === selEdges[0]) : undefined;

  const convertLanguage = async () => {
    if (!project) return;
    const target = project.locale === 'en' ? 'pt' : 'en';
    const msg = t(`Convert the schema of "${project.name}" to ${target === 'en' ? 'English' : 'Portuguese'}? Types, attribute keys, enum values and relations are renamed and the data migrated. The content (names, summaries, texts) is not translated.\n\nTip: "Clone…" with translation keeps the original untouched.`,
      `Converter o schema de "${project.name}" para ${target === 'en' ? 'inglês' : 'português'}? Tipos, chaves de atributo, valores de enum e relações são renomeados e os dados migrados. O conteúdo (nomes, resumos, textos) não é traduzido.\n\nDica: "Clonar…" com tradução mantém o original intacto.`);
    if (!confirm(msg)) return;
    try {
      const r = await api.localizeProject(project.id, target);
      await reloadProjects();
      setVersion((v) => v + 1);
      notify(t(`Converted to ${r.to}: ${r.nodesMigrated} nodes migrated`, `Convertido para ${r.to}: ${r.nodesMigrated} nós migrados`));
    } catch (e) {
      notify((e as Error).message, true);
    }
  };

  const onRenamed = (p: Project) => {
    setProjects((ps) => ps.map((x) => (x.id === p.id ? p : x)));
    notify(t(`Renamed to "${p.name}"`, `Renomeado para "${p.name}"`));
  };
  const onImported = async (r: ImportResult) => {
    await Promise.all([reloadProjects(), reloadContexts()]);
    notify(importSummary(r));
    if (r.projects.length === 1) {
      const p = r.projects[0];
      if (p.context) setContextId(p.context);
      openProject(p.projectId);
    }
  };

  return (
    <div className="app">
      <header className="topbar">
        <button className="brand ghost" onClick={() => goContext(undefined)} title={t('Contexts', 'Contextos')}>Obliterate Harness</button>
        {ctx && (
          <button className="crumb" onClick={() => goContext(ctx.id)} title={t('Projects of this context', 'Projetos deste contexto')}>
            <span aria-hidden>{ctx.icon}</span> {ctx.name}
          </button>
        )}
        {projectId && (isBlueprint ? (
          <span className="chip blueprint" title={t('Changes here define every new project of the context', 'Mudanças aqui definem todo projeto novo do contexto')}>
            ✎ {t('Definition of the context', 'Definição do contexto')}</span>
        ) : renamingTop && project ? (
          <RenameInput project={project} onError={(m) => notify(m, true)} onDone={(p) => { setRenamingTop(false); if (p) onRenamed(p); }} />
        ) : (
          <select value={projectId} onChange={(e) => e.target.value === '__new' ? setModal('newProject') : openProject(e.target.value)} aria-label={t('Project', 'Projeto')}>
            {ctxProjects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            <option value="__new">{t('+ New project…', '+ Novo projeto…')}</option>
          </select>
        ))}
        {projectId && !isBlueprint && !renamingTop && project && <RenameButton onClick={() => setRenamingTop(true)} />}
        {projectId && (
          <nav className="tabs">
            {tabs.map((x) => (
              <button key={x} className={tab === x ? 'active' : ''} onClick={() => setTab(x)}>{x === 'all' ? t('All', 'Tudo') : ctxSpaceLabel(ctx, x)}</button>
            ))}
          </nav>
        )}
        <span className="spacer" />
        {projectId && (
          <>
            <button onClick={() => setModal('facts')}>{t('Facts', 'Fatos')}</button>
            <button onClick={() => setModal('directives')}>{t('Directives', 'Diretivas')}</button>
            <button onClick={() => setModal('schema')}>Schema</button>
            <button onClick={() => setModal('changesets')}>Changesets{pending > 0 && <span className="badge">{pending}</span>}</button>
            <button onClick={() => setModal('audit')}>{t('History', 'Histórico')}</button>
          </>
        )}
        <div className="group lang-toggle" role="group" aria-label={t('Interface language', 'Idioma da interface')}>
          <button className={uiLang === 'en' ? 'active' : ''} onClick={() => switchLang('en')}>EN</button>
          <button className={uiLang === 'pt' ? 'active' : ''} onClick={() => switchLang('pt')}>PT</button>
        </div>
      </header>

      {!projectId ? (
        ctx ? (
          <ContextPage ctx={ctx} projects={ctxProjects} onOpenProject={openProject} onNewProject={() => setModal('newProject')}
            onEditDefinition={() => ctx.blueprintProjectId && openProject(ctx.blueprintProjectId)}
            onSettings={() => setModal('ctxSettings')}
            onDerive={() => { setDeriveBase(ctx.id); setModal('newContext'); }}
            onBack={() => goContext(undefined)}
            onRenamed={onRenamed} onImported={onImported} onError={(m) => notify(m, true)} />
        ) : (
          <ContextHome contexts={contexts} onOpen={(id) => goContext(id)} onCreate={() => { setDeriveBase(undefined); setModal('newContext'); }}
            onImported={onImported} />
        )
      ) : (
        <div className="main">
          <FilterPanel projectId={projectId} schema={schema} filter={filter} setFilter={setFilter} knownTags={knownTags} onFocus={focus} />

          <section className="canvas-wrap">
            <div className="toolbar">
              <div className="group" role="group" aria-label={t('Mode', 'Modo')}>
                <button className={mode === 'pan' ? 'active' : ''} onClick={() => setMode('pan')} title={t('Dragging moves the view (Shift+drag selects an area; middle button always pans)', 'Arrastar move a tela (Shift+arrastar seleciona em área; botão do meio sempre move)')}>✋ {t('Pan', 'Mover')}</button>
                <button className={mode === 'box' ? 'active' : ''} onClick={() => setMode('box')} title={t('Dragging selects a rectangle', 'Arrastar seleciona em retângulo')}>▭ {t('Box', 'Área')}</button>
                <button className={mode === 'lasso' ? 'active' : ''} onClick={() => setMode('lasso')} title={t('Dragging draws a selection lasso', 'Arrastar desenha um laço de seleção')}>◌ {t('Lasso', 'Laço')}</button>
              </div>
              <div className="group">
                <button onClick={() => canvas.current?.fit()}>{t('Fit', 'Enquadrar')}</button>
                <button onClick={() => canvas.current?.untangle()} title={t('Pushes apart overlapping nodes and labels without changing the arrangement', 'Afasta nós e rótulos encavalados sem mudar o arranjo')}>{t('Untangle', 'Desencavalar')}</button>
                <select className="layout-select" value="" aria-label={t('Rearrange', 'Reorganizar')}
                  onChange={(e) => { if (e.target.value) canvas.current?.relayout(e.target.value as LayoutKind); }}>
                  <option value="">{t('Rearrange…', 'Reorganizar…')}</option>
                  {LAYOUT_KINDS.map((k) => <option key={k} value={k} title={layoutHint(k)}>{layoutLabel(k)}</option>)}
                </select>
              </div>
              <button className="primary" onClick={() => setModal('newNode')}>{t('+ Node', '+ Nó')}</button>
              <button disabled={selectedNodes.length !== 2} onClick={() => setModal('newEdge')} title={t('Select 2 nodes', 'Selecione 2 nós')}>{t('+ Relation', '+ Relação')}</button>
              {selectedNodes.length === 1 && (
                <div className="group" role="group" aria-label={t('Focus', 'Foco')}>
                  <button onClick={() => startFocus(selectedNodes[0].id, false)} title={t('Shows only the node and what is directly linked to it (shortcut: double click)', 'Mostra só o nó e quem está ligado diretamente a ele (atalho: duplo clique)')}>◎ {t('Linked', 'Ligados')}</button>
                  <button onClick={() => startFocus(selectedNodes[0].id, true)} title={t('Shows everything linked to the node, even through others', 'Mostra tudo que está ligado ao nó, mesmo através de outros')}>◎ {t('All linked', 'Todos ligados')}</button>
                </div>
              )}
            </div>

            {focusState && (
              <div className="focus-bar" role="status">
                <span>{t('Focus on', 'Foco em')} <b>{focusState.name}</b> · {focusState.all ? t('everything linked', 'todos os ligados') : t('directly linked', 'ligados diretamente')}
                  {focusInfo && focusState.all && focusInfo.levels > 0 && (
                    <span className="muted"> · {Object.entries(focusInfo.byLevel).map(([lvl, c]) => t(`${c} at ${lvl} hop${lvl === '1' ? '' : 's'}`, `${c} a ${lvl} salto${lvl === '1' ? '' : 's'}`)).join(', ')}</span>
                  )}
                  {focusInfo?.truncated && <span style={{ color: 'var(--warn)' }}> · {t('node limit reached', 'limite de nós atingido')}</span>}
                </span>
                <button className={focusState.all ? '' : 'active'} onClick={() => setFocusState({ ...focusState, all: false })}>{t('Direct', 'Diretos')}</button>
                <button className={focusState.all ? 'active' : ''} onClick={() => setFocusState({ ...focusState, all: true })}>{t('All linked', 'Todos ligados')}</button>
                <button onClick={() => canvas.current?.relayout('aneis')} title={t('Arranges in rings by distance to the center', 'Organiza em anéis pela distância até o centro')}>{layoutLabel('aneis')}</button>
                <button className="primary" onClick={() => setFocusState(null)}>{t('Leave focus (Esc)', 'Sair do foco (Esc)')}</button>
              </div>
            )}

            {projectId && view && (
              <GraphCanvas ref={canvas} projectId={projectId} nodes={view.nodes} edges={view.edges} indirect={view.indirect}
                contextIds={view.contextIds ?? []} nodeTypes={schema.nodeTypes} mode={mode} onSelectionChange={onSelectionChange}
                distance={focusState ? distance : undefined} centerId={focusState?.nodeId}
                onNodeDoubleTap={(id) => startFocus(id, false)} />
            )}
            {view && view.nodes.length === 0 && (
              <div className="empty" style={{ position: 'absolute', inset: '80px 0 auto', zIndex: 1 }}>
                {schema.nodeTypes.length === 0
                  ? <>{t('Define your project\'s types in', 'Defina os tipos do seu projeto em')} <button onClick={() => setModal('schema')}>Schema</button> {t('or ask the LLM through MCP.', 'ou peça para a LLM via MCP.')}</>
                  : t('No nodes in this filter.', 'Nenhum nó neste filtro.')}
              </div>
            )}
            {view && (
              <div className="status">
                {view.nodes.length} {t('nodes', 'nós')}{view.truncated ? ` (${t('of', 'de')} ${view.total})` : ''} · {view.edges.length} {t('relations', 'relações')}
                {view.indirect.length > 0 && ` · ${view.indirect.length} ${t('indirect', 'indiretas')}`}
                {selNodes.length > 0 && ` · ${selNodes.length} ${t('selected', 'selecionados')}`}
              </div>
            )}
            <div className="legend" aria-label={t('Legend', 'Legenda')}>
              {Object.entries(BASE_COLOR).slice(0, 8).map(([b, c]) => <span key={b}><i className="dot" style={{ background: c }} />{baseLabel(b)}</span>)}
            </div>
          </section>

          <aside className="inspector">
            {selectedNodes.length === 1 && (
              <NodeEditor projectId={projectId} nodeId={selectedNodes[0].id} schema={schema} run={run} onFocus={focus} reloadKey={version} />
            )}
            {selectedNodes.length > 1 && <BulkEditor nodes={selectedNodes} run={run} onClear={() => canvas.current?.selectIds([])} />}
            {selectedEdge && <EdgeEditor projectId={projectId} edge={selectedEdge} run={run} />}
            {selIndirect && selNodes.length === 0 && <IndirectInfo projectId={projectId} link={selIndirect} onFocus={focus} />}
            {selectedNodes.length === 0 && !selectedEdge && !selIndirect && (
              <div>
                {isBlueprint && ctx && (
                  <div className="section blueprint-note">
                    <h4>{t('Context definition', 'Definição do contexto')}</h4>
                    <p className="small">{t(`This is the definition of "${ctx.name}". Node types, relations and attributes (Schema), rules (Facts) and AI instructions (Directives) you set here are copied into every new project of the context. Example nodes are fine for testing but are not copied.`,
                      `Esta é a definição de "${ctx.name}". Tipos de nó, relações e atributos (Schema), regras (Fatos) e instruções para a IA (Diretivas) definidos aqui são copiados para todo projeto novo do contexto. Nós de exemplo servem para testar, mas não são copiados.`)}</p>
                    <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
                      <button className="primary small fixed" onClick={() => setModal('schema')}>Schema</button>
                      <button className="small fixed" onClick={() => setModal('directives')}>{t('Directives', 'Diretivas')}</button>
                      <button className="small fixed" onClick={() => goContext(ctx.id)}>{t('Back to the context', 'Voltar ao contexto')}</button>
                    </div>
                  </div>
                )}
                <div className="section">
                  {project && !isBlueprint ? <ProjectName as="h4" project={project} onRenamed={onRenamed} onError={(m) => notify(m, true)} /> : <h4>{project?.name}</h4>}
                  {project?.description && <p>{project.description}</p>}
                  <p className="small muted">
                    {project?.profile && <>{t('Preset', 'Preset')}: {project.profile} · </>}{t('Language', 'Idioma')}: <b>{project?.locale === 'en' ? 'English' : 'Português'}</b> · LLM {project?.requireReviewForLlm ? t('needs approval', 'precisa de aprovação') : t('writes directly (audited)', 'grava direto (auditado)')}
                  </p>
                  <label className="check small">
                    <input type="checkbox" checked={!!project?.requireReviewForLlm} onChange={async (e) => {
                      const p = await api.updateProject(projectId, { requireReviewForLlm: e.target.checked });
                      setProjects(projects.map((x) => (x.id === p.id ? p : x)));
                    }} />
                    <span>{t('Require my approval for LLM changes', 'Exigir minha aprovação nas mudanças da LLM')}</span>
                  </label>
                  {!isBlueprint && <div className="row" style={{ marginTop: 8, flexWrap: 'wrap', gap: 6 }}>
                    <button className="small" onClick={() => setModal('clone')}>{t('Clone…', 'Clonar…')}</button>
                    <ArchiveActions projectId={projectId} small onImported={onImported} />
                    <button className="small" onClick={convertLanguage} title={t('Converts the schema to the other language', 'Converte o schema para o outro idioma')}>
                      {project?.locale === 'en' ? t('Convert to Portuguese…', 'Converter para português…') : t('Convert to English…', 'Converter para inglês…')}
                    </button>
                  </div>}
                  {!isBlueprint && <button className="danger small" style={{ marginTop: 8 }} onClick={async () => {
                    if (!project) return;
                    const typed = prompt(t(`This deletes the whole project (graph, history, sessions) and cannot be undone.\nType the project name to confirm:\n${project.name}`,
                      `Isto apaga o projeto inteiro (grafo, histórico, sessões) e não tem volta.\nDigite o nome do projeto para confirmar:\n${project.name}`));
                    if (typed === null) return;
                    if (typed !== project.name) { notify(t('Name does not match; nothing was deleted.', 'Nome não confere; nada foi apagado.'), true); return; }
                    try {
                      await api.deleteProject(project.id, typed);
                      await Promise.all([reloadProjects(), reloadContexts()]);
                      goContext(ctx?.id);
                      notify(t(`Project "${project.name}" deleted`, `Projeto "${project.name}" excluído`));
                    } catch (e) {
                      notify((e as Error).message, true);
                    }
                  }}>{t('Delete project…', 'Excluir projeto…')}</button>}
                </div>
                <div className="section">
                  <h4>{t('How to use', 'Como usar')}</h4>
                  <ul className="small muted" style={{ paddingLeft: 16, margin: 0 }}>
                    <li>{t('Click a node to edit it; Shift/Ctrl+click adds to the selection.', 'Clique num nó para editar; Shift/Ctrl+clique soma à seleção.')}</li>
                    <li>{t('Box and Lasso modes select many nodes at once for bulk editing or deleting.', 'Modos Área e Laço selecionam vários nós de uma vez para edição ou exclusão em lote.')}</li>
                    <li>{t('Select 2 nodes and use "+ Relation".', 'Selecione 2 nós e use “+ Relação”.')}</li>
                    <li>{t('"Show indirect" reveals nodes linked through others (dashed line).', '“Mostrar indiretas” revela nós ligados através de outros (linha tracejada).')}</li>
                  </ul>
                </div>
                <div className="section">
                  <h4>{t('Connect the LLM (MCP)', 'Conectar a LLM (MCP)')}</h4>
                  <pre className="json">claude mcp add --transport http obliterate http://localhost:8180/mcp</pre>
                  <p className="small muted">{t('This project\'s id', 'Id deste projeto')}: <code>{projectId}</code>{ctx && <> · {t('context', 'contexto')}: <code>{ctx.id}</code></>}</p>
                </div>
              </div>
            )}
          </aside>
        </div>
      )}

      {modal === 'newProject' && ctx && (
        <NewProjectInContextDialog ctx={ctx} onClose={() => setModal(null)} onCreated={async (p) => {
          setModal(null);
          await Promise.all([reloadProjects(), reloadContexts()]);
          openProject(p.id);
        }} />
      )}
      {modal === 'newContext' && (
        <NewContextDialog contexts={contexts} initialBase={deriveBase} onClose={() => setModal(null)} onCreated={async (c) => {
          setModal(null);
          await Promise.all([reloadProjects(), reloadContexts()]);
          goContext(c.id);
          notify(t(`Context "${c.name}" created. Next: "Edit definition" to shape its types and rules.`,
            `Contexto "${c.name}" criado. Próximo passo: "Editar definição" para moldar tipos e regras.`));
        }} />
      )}
      {modal === 'ctxSettings' && ctx && (
        <ContextSettingsDialog ctx={ctx} onClose={() => setModal(null)}
          onSaved={async () => { setModal(null); await Promise.all([reloadContexts(), reloadProjects()]); notify(t('Context saved', 'Contexto salvo')); }}
          onDeleted={async () => { setModal(null); await Promise.all([reloadContexts(), reloadProjects()]); goContext(undefined); notify(t('Context deleted', 'Contexto excluído')); }} />
      )}
      {modal === 'clone' && project && (
        <CloneProjectDialog project={project} onClose={() => setModal(null)} onCloned={async (id, notes) => {
          setModal(null);
          await reloadProjects();
          setProjectId(id);
          notify(t('Project cloned', 'Projeto clonado') + (notes.length ? ` · ${notes.join(' · ')}` : ''));
        }} />
      )}
      {modal === 'schema' && projectId && <SchemaEditor schema={schema} run={run} onClose={() => setModal(null)} />}
      {modal === 'changesets' && projectId && <ChangesetsPanel projectId={projectId} onClose={() => setModal(null)} onChanged={() => setVersion((v) => v + 1)} />}
      {modal === 'facts' && projectId && <FactsPanel projectId={projectId} onClose={() => setModal(null)} onFocus={focus} />}
      {modal === 'directives' && projectId && <DirectivesPanel projectId={projectId} schema={schema} run={run} onClose={() => setModal(null)} />}
      {modal === 'audit' && projectId && <AuditPanel projectId={projectId} onClose={() => setModal(null)} />}
      {modal === 'newNode' && projectId && (
        <NewNodeDialog projectId={projectId} schema={schema} defaultSpace={tab === 'all' ? undefined : tab} run={run}
          onClose={() => setModal(null)} onCreated={() => setModal(null)} />
      )}
      {modal === 'newEdge' && selectedNodes.length === 2 && (
        <NewEdgeDialog schema={schema} from={selectedNodes[0]} to={selectedNodes[1]} run={run} onClose={() => setModal(null)} />
      )}
      {toast && <div className={'toast' + (toast.error ? ' error' : '')} role="status">{toast.text}</div>}
    </div>
  );
}
