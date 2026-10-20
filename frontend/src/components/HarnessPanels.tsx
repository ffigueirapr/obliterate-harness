import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../api';
import { Modal } from './Dialogs';
import type { AppliedTemplate, BeatDef, BeatStatus, Coverage, Directive, GNode, Operation, Project, Schema, TemplateSummary, Thread, WritingContext } from '../types';
import { BASES, baseLabel, rigidityLabel } from '../types';
import { t } from '../i18n';

type Run = (title: string, ops: Operation[]) => Promise<boolean>;

const statusLabel = (s: BeatStatus['status']) => ({
  ok: t('in place', 'no lugar'), off_position: t('off position', 'fora de posição'), missing: t('missing', 'faltando'),
  optional_missing: t('optional, absent', 'opcional ausente'), future: t('future', 'futuro'),
})[s] ?? s;
const STATUS_COLOR: Record<string, string> = {
  ok: 'var(--ok)', off_position: 'var(--warn)', missing: 'var(--danger)', optional_missing: 'var(--muted)', future: 'var(--muted)',
};
const pct = (v: number) => `${Math.round(v * 100)}%`;

// ------------------------------------------------------------------ structure (templates + coverage)

export function StructurePanel({ project, projects, schema, run, onClose, onFocus }: {
  project: Project; projects: Project[]; schema: Schema; run: Run; onClose: () => void; onFocus: (id: string) => void;
}) {
  const pid = project.id;
  const [library, setLibrary] = useState<TemplateSummary[]>([]);
  const [suggested, setSuggested] = useState<string[]>([]);
  const [applied, setApplied] = useState<AppliedTemplate[]>([]);
  const [coverage, setCoverage] = useState<Coverage[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState({ templateId: '', unitType: '', plannedUnits: '', scopeUnitId: '' });
  const [units, setUnits] = useState<GNode[]>([]);
  const [derive, setDerive] = useState({ fromProjectId: pid, unitType: '', name: '', includeChildren: true });
  const [deriveTypes, setDeriveTypes] = useState<string[]>([]);
  const [handmade, setHandmade] = useState<{ id?: string; name: string; description: string; beats: BeatDef[] } | null>(null);

  const mapsToBeat = schema.relTypes.find((r) => r.systemKey === 'maps_to_beat')?.name;

  const load = useCallback(() => {
    api.appliedTemplates(pid).then(setApplied).catch((e) => setError(e.message));
    api.coverage(pid).then(setCoverage).catch((e) => setError(e.message));
  }, [pid]);
  const loadLibrary = useCallback(() => { api.templateLibrary().then(setLibrary).catch(() => setLibrary([])); }, []);

  useEffect(() => {
    load();
    loadLibrary();
    api.profiles().then((ps) => setSuggested(ps.find((p) => p.id === project.profile)?.suggestedTemplates ?? [])).catch(() => setSuggested([]));
    api.nodes(pid, { bases: ['Unit'], limit: 2000, detail: 'summary' }).then(setUnits).catch(() => setUnits([]));
  }, [pid, project.profile, load, loadLibrary]);

  useEffect(() => {
    api.schema(derive.fromProjectId).then((s) => {
      const names = s.nodeTypes.filter((x) => x.base === 'Unit').map((x) => x.name);
      setDeriveTypes(names);
      if (!names.includes(derive.unitType)) setDerive((d) => ({ ...d, unitType: names.find((n) => /chap|cap/i.test(n)) ?? names[0] ?? '' }));
    }).catch(() => setDeriveTypes([]));
  }, [derive.fromProjectId]); // eslint-disable-line

  const unitTypes = schema.nodeTypes.filter((x) => x.base === 'Unit');
  const chosen = library.find((x) => x.id === form.templateId);
  const sortedLibrary = useMemo(() => [...library].sort((a, b) => Number(suggested.includes(b.id)) - Number(suggested.includes(a.id))), [library, suggested]);
  const customs = library.filter((x) => x.custom);

  const guard = async (fn: () => Promise<unknown>) => {
    setError(null);
    try { await fn(); } catch (e) { setError((e as Error).message); }
  };

  const apply = () => guard(async () => {
    await api.applyTemplate(pid, {
      templateId: form.templateId,
      unitType: form.unitType || undefined,
      plannedUnits: form.plannedUnits ? Number(form.plannedUnits) : undefined,
      scopeUnitId: form.scopeUnitId || undefined,
    });
    setForm({ templateId: '', unitType: '', plannedUnits: '', scopeUnitId: '' });
    load();
  });

  const mapUnit = async (beat: BeatStatus, unitId: string) => {
    if (!mapsToBeat) { setError(t('This project has no maps-to-beat relation (system key maps_to_beat).', 'Este projeto não tem relação de mapear beat (chave de sistema maps_to_beat).')); return; }
    if (await run(`${t('Map beat', 'Mapear beat')} ${beat.name}`, [{ op: 'create_edge', relType: mapsToBeat, from: unitId, to: beat.beatId }])) load();
  };

  const doDerive = () => guard(async () => {
    const tpl = await api.deriveTemplate({ fromProjectId: derive.fromProjectId, unitType: derive.unitType, name: derive.name || undefined, includeChildren: derive.includeChildren });
    loadLibrary();
    setForm({ ...form, templateId: tpl.id ?? '' });
    setDerive({ ...derive, name: '' });
  });

  const editCustom = (id: string) => guard(async () => {
    const d = await api.template(id);
    setHandmade({ id: d.id, name: d.name, description: d.description ?? '', beats: d.beats });
  });

  const saveHandmade = () => guard(async () => {
    if (!handmade) return;
    await api.upsertTemplate({ id: handmade.id, name: handmade.name, description: handmade.description || undefined, beats: handmade.beats });
    setHandmade(null);
    loadLibrary();
  });

  return (
    <Modal title={t('Structure: templates and coverage', 'Estrutura: templates e cobertura')} onClose={onClose}>
      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}
      {coverage.length === 0 && <p className="muted">{t('No template applied yet. Templates give the LLM (and you) the milestones the story has to hit.',
        'Nenhum template aplicado ainda. Templates dão à LLM (e a você) os marcos que a história precisa cumprir.')}</p>}
      {coverage.map((c) => {
        const tpl = applied.find((a) => a.id === c.templateNodeId);
        const candidates = units.filter((u) => u.type === c.unitType);
        return (
          <div key={c.templateNodeId} className="list-item">
            <div className="row">
              <div>
                <b>{c.templateName}</b>{' '}
                <span className="muted small">{t('measures', 'mede')} {c.unitType}{tpl?.scopeUnitId ? ` ${t('inside', 'dentro de')} ${units.find((u) => u.id === tpl.scopeUnitId)?.name ?? tpl.scopeUnitId}` : ''} · {c.unitsWritten} {t('of', 'de')} {c.unitsPlanned} {t('units', 'unidades')}</span>
              </div>
            </div>
            <div style={{ height: 6, background: 'var(--panel-2)', borderRadius: 3, margin: '6px 0' }} aria-label={`${t('Progress', 'Progresso')} ${pct(c.progress)}`}>
              <div style={{ width: pct(c.progress), height: '100%', background: 'var(--accent)', borderRadius: 3 }} />
            </div>
            {c.summary?.map((s) => <div key={s} className="small muted">{s}</div>)}
            <table style={{ marginTop: 8 }}>
              <thead><tr><th>{t('Position', 'Posição')}</th><th>Beat</th><th>{t('Status', 'Situação')}</th><th>{t('Units', 'Unidades')}</th><th>{t('Map', 'Mapear')}</th></tr></thead>
              <tbody>
                {c.beats?.map((b) => (
                  <tr key={b.beatId}>
                    <td className="small">{pct(b.expected)}</td>
                    <td title={[b.function, b.criteria && `${t('Criteria', 'Critério')}: ${b.criteria}`].filter(Boolean).join('\n')}>
                      {b.name}{!b.required && <span className="muted small"> ({t('optional', 'opcional')})</span>}
                    </td>
                    <td><span className="small" style={{ color: STATUS_COLOR[b.status], fontWeight: 600 }}>{statusLabel(b.status)}</span>
                      {b.note && <div className="small muted">{b.note}</div>}</td>
                    <td className="small">{b.units?.map((u) => (
                      <span key={u.id} className="tree node-link" onClick={() => { onFocus(u.id); onClose(); }}>{u.name} ({pct(u.position)}) </span>
                    ))}</td>
                    <td>
                      <select value="" onChange={(e) => e.target.value && mapUnit(b, e.target.value)} aria-label={`${t('Map a unit to beat', 'Mapear unidade ao beat')} ${b.name}`}>
                        <option value="">{t('unit…', 'unidade…')}</option>
                        {candidates.filter((u) => !b.units?.some((x) => x.id === u.id)).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
                      </select>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {c.unitsWithoutBeat && c.unitsWithoutBeat.length > 0 && (
              <div className="small" style={{ marginTop: 6 }}>
                <span className="muted">{t('Without beat', 'Sem beat')}: </span>
                {c.unitsWithoutBeat.map((u) => <span key={u.id} className="tree node-link" onClick={() => { onFocus(u.id); onClose(); }}>{u.name} </span>)}
              </div>
            )}
          </div>
        );
      })}

      <div className="section" style={{ marginTop: 16 }}>
        <h4>{t('Apply template', 'Aplicar template')}</h4>
        <div className="row">
          <div className="field"><label>Template</label>
            <select value={form.templateId} onChange={(e) => setForm({ ...form, templateId: e.target.value, scopeUnitId: '' })}>
              <option value="">{t('choose…', 'escolha…')}</option>
              {sortedLibrary.map((x) => <option key={x.id} value={x.id}>{suggested.includes(x.id) ? '★ ' : x.custom ? '✎ ' : ''}{x.name} ({x.beats} beats)</option>)}
            </select></div>
          <div className="field"><label>{t('Measured unit', 'Unidade medida')}</label>
            <select value={form.unitType} onChange={(e) => setForm({ ...form, unitType: e.target.value })}>
              <option value="">{t('profile default', 'padrão do perfil')}</option>
              {unitTypes.map((x) => <option key={x.id} value={x.name}>{x.name}</option>)}
            </select></div>
          <div className="field fixed" style={{ width: 120 }}><label>{t('Planned total', 'Total previsto')}</label>
            <input type="number" min={1} value={form.plannedUnits} onChange={(e) => setForm({ ...form, plannedUnits: e.target.value })} /></div>
        </div>
        {chosen && <p className="small muted">{chosen.description}{chosen.source ? ` · ${t('source', 'fonte')}: ${chosen.source}` : ''}</p>}
        {chosen?.scope === 'unidade' && (
          <div className="field"><label>{t('Inside which unit (e.g. the chapter whose scenes are measured)', 'Dentro de qual unidade (ex.: o capítulo cujas cenas serão medidas)')}</label>
            <select value={form.scopeUnitId} onChange={(e) => setForm({ ...form, scopeUnitId: e.target.value })}>
              <option value="">{t('(whole project)', '(projeto inteiro)')}</option>
              {units.map((u) => <option key={u.id} value={u.id}>{u.name} ({u.type})</option>)}
            </select></div>
        )}
        <button className="primary" disabled={!form.templateId} onClick={apply}>{t('Apply', 'Aplicar')}</button>
        <p className="small muted">{t('★ = suggested for the profile, ✎ = your own template. "Planned total" improves the beat positions while the work is unfinished.',
          '★ = sugerido para o perfil, ✎ = template seu. “Total previsto” melhora o cálculo de posição dos beats enquanto a obra está incompleta.')}</p>
      </div>

      <div className="section" style={{ marginTop: 16 }}>
        <h4>{t('Your templates', 'Seus templates')}</h4>
        <p className="small muted">{t('Turn a work you dissected into a template (one beat per chapter, at its position), or write one by hand. Coverage then compares your story with it.',
          'Transforme uma obra dissecada em template (um beat por capítulo, na posição dele) ou escreva um à mão. A cobertura passa a comparar sua história com ele.')}</p>
        <div className="row">
          <div className="field"><label>{t('From project', 'Do projeto')}</label>
            <select value={derive.fromProjectId} onChange={(e) => setDerive({ ...derive, fromProjectId: e.target.value })}>
              {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select></div>
          <div className="field"><label>{t('Unit that becomes a beat', 'Unidade que vira beat')}</label>
            <select value={derive.unitType} onChange={(e) => setDerive({ ...derive, unitType: e.target.value })}>
              {deriveTypes.map((x) => <option key={x} value={x}>{x}</option>)}
            </select></div>
          <div className="field"><label>{t('Name (optional)', 'Nome (opcional)')}</label>
            <input value={derive.name} onChange={(e) => setDerive({ ...derive, name: e.target.value })} /></div>
        </div>
        <label className="check small"><input type="checkbox" checked={derive.includeChildren} onChange={(e) => setDerive({ ...derive, includeChildren: e.target.checked })} />
          <span>{t('Include the parts (e.g. scenes) in each beat\'s criteria', 'Incluir as partes (ex.: cenas) no critério de cada beat')}</span></label>
        <div className="row" style={{ marginTop: 6 }}>
          <button disabled={!derive.unitType} onClick={doDerive}>{t('Derive template', 'Derivar template')}</button>
          <button className="fixed" onClick={() => setHandmade({ name: '', description: '', beats: [{ name: '', position: 0 }, { name: '', position: 1 }] })}>{t('+ by hand', '+ à mão')}</button>
        </div>
        {customs.map((x) => (
          <div key={x.id} className="row small" style={{ padding: '4px 0' }}>
            <span>✎ <b>{x.name}</b> <span className="muted">{x.beats} beats{x.source ? ` · ${x.source}` : ''}</span></span>
            <button className="fixed ghost small" onClick={() => editCustom(x.id)}>{t('edit', 'editar')}</button>
            <button className="fixed ghost small" onClick={() => confirm(t(`Delete the template "${x.name}"? Copies already applied to projects stay.`, `Excluir o template "${x.name}"? Cópias já aplicadas em projetos continuam.`))
              && guard(async () => { await api.deleteTemplate(x.id); loadLibrary(); })}>{t('delete', 'excluir')}</button>
          </div>
        ))}
        {handmade && (
          <div className="list-item" style={{ marginTop: 8 }}>
            <div className="row">
              <div className="field"><label>{t('Name', 'Nome')}</label><input value={handmade.name} onChange={(e) => setHandmade({ ...handmade, name: e.target.value })} /></div>
              <div className="field"><label>{t('Description', 'Descrição')}</label><input value={handmade.description} onChange={(e) => setHandmade({ ...handmade, description: e.target.value })} /></div>
            </div>
            <table>
              <thead><tr><th>Beat</th><th>{t('Position (0–1)', 'Posição (0–1)')}</th><th>{t('Criteria', 'Critério')}</th><th>{t('Req.', 'Obr.')}</th><th /></tr></thead>
              <tbody>
                {handmade.beats.map((b, i) => {
                  const set = (patch: Partial<BeatDef>) => setHandmade({ ...handmade, beats: handmade.beats.map((x, j) => (j === i ? { ...x, ...patch } : x)) });
                  return (
                    <tr key={i}>
                      <td><input value={b.name} onChange={(e) => set({ name: e.target.value })} /></td>
                      <td style={{ width: 90 }}><input type="number" min={0} max={1} step={0.01} value={b.position} onChange={(e) => set({ position: Number(e.target.value) })} /></td>
                      <td><input value={b.criteria ?? ''} onChange={(e) => set({ criteria: e.target.value || undefined })} /></td>
                      <td><input type="checkbox" checked={b.required !== false} onChange={(e) => set({ required: e.target.checked })} /></td>
                      <td><button className="ghost" onClick={() => setHandmade({ ...handmade, beats: handmade.beats.filter((_, j) => j !== i) })}>✕</button></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <div className="row" style={{ marginTop: 6 }}>
              <button className="fixed" onClick={() => setHandmade({ ...handmade, beats: [...handmade.beats, { name: '', position: 1 }] })}>+ beat</button>
              <button className="primary" disabled={!handmade.name.trim() || handmade.beats.some((b) => !b.name.trim())} onClick={saveHandmade}>{t('Save template', 'Salvar template')}</button>
              <button className="fixed" onClick={() => setHandmade(null)}>{t('Cancel', 'Cancelar')}</button>
            </div>
          </div>
        )}
      </div>
    </Modal>
  );
}

// ------------------------------------------------------------------ hooks and clues

const CLOSED = ['paid', 'abandoned', 'pago', 'abandonado'];

export function ThreadsPanel({ projectId, schema, run, onClose, onFocus }: { projectId: string; schema: Schema; run: Run; onClose: () => void; onFocus: (id: string) => void }) {
  const [closed, setClosed] = useState(false);
  const [threads, setThreads] = useState<Thread[]>([]);
  const load = useCallback(() => { api.threads(projectId, closed).then(setThreads).catch(() => setThreads([])); }, [projectId, closed]);
  useEffect(() => { load(); }, [load]);
  const hookType = schema.nodeTypes.find((x) => x.systemKey === 'thread');
  const paidValue = hookType?.attributes?.find((a) => a.key === 'status')?.options?.find((o) => o === 'paid' || o === 'pago') ?? 'paid';
  const plants = schema.relTypes.find((r) => r.systemKey === 'plants')?.name ?? 'PLANTS';
  const pays = schema.relTypes.find((r) => r.systemKey === 'pays')?.name ?? 'PAYS_OFF';
  const about = schema.relTypes.find((r) => r.systemKey === 'about')?.name ?? 'ABOUT';
  const markPaid = async (x: Thread) => {
    const d = await api.node(projectId, x.id);
    if (await run(`${t('Hook paid', 'Gancho pago')}: ${x.name}`, [{ op: 'update_node', id: x.id, expectedVersion: d.node.version, attributes: { status: paidValue } }])) load();
  };
  const link = (id: string, name: string) => <span className="tree node-link" onClick={() => { onFocus(id); onClose(); }}>{name}</span>;
  return (
    <Modal title={t('Hooks and clues', 'Ganchos e pistas')} onClose={onClose}>
      <p className="small muted">{t(`Setups that ask for a payoff: ${hookType?.name ?? 'Hook'} nodes, or any node planted as a clue. Plant with ${plants} and pay with ${pays} (unit → node); say what a hook is about with ${about}.`,
        `Setups que pedem payoff: nós ${hookType?.name ?? 'Gancho'}, ou qualquer nó plantado como pista. Plante com ${plants} e pague com ${pays} (unidade → nó); diga do que o gancho trata com ${about}.`)}</p>
      <label className="check" style={{ marginBottom: 12 }}><input type="checkbox" checked={closed} onChange={(e) => setClosed(e.target.checked)} /><span>{t('Show paid and abandoned', 'Mostrar pagos e abandonados')}</span></label>
      {threads.length === 0 && <p className="muted">{closed ? t('No hooks.', 'Nenhum gancho.') : t('No open hooks.', 'Nenhum gancho aberto.')}</p>}
      {threads.map((x) => (
        <div key={x.id} className="list-item">
          <div className="row">
            <div>
              <b>{link(x.id, x.name)}</b>{' '}
              <span className="muted small">{x.clue ? `🔎 ${t('clue', 'pista')} · ${x.type}` : x.kind ?? x.type}{x.status ? ` · ${x.status}` : ''}</span>
            </div>
            {!x.clue && !CLOSED.includes(x.status) && <button className="fixed" onClick={() => markPaid(x)}>{t('Mark as paid', 'Marcar como pago')}</button>}
          </div>
          {x.summary && <div className="small">{x.summary}</div>}
          {x.about && x.about.length > 0 && (
            <div className="small"><span className="muted">{t('about', 'sobre')}: </span>{x.about.map((a, i) => <span key={a.id}>{i > 0 && ', '}{link(a.id, a.name)}</span>)}</div>
          )}
          <div className="small muted">
            {x.plantedIn?.length ? `${t('Planted in', 'Plantado em')} ${x.plantedIn.map((u) => u.name).join(', ')}` : t(`No ${plants} unit`, `Sem unidade ${plants}`)}
            {x.unitsSincePlanted != null && ` · ${t(`${x.unitsSincePlanted} unit(s) ago`, `há ${x.unitsSincePlanted} unidade(s)`)}`}
            {x.paidIn?.length ? ` · ${t('paid in', 'pago em')} ${x.paidIn.map((u) => u.name).join(', ')}` : ''}
          </div>
          {x.warning && <div className="small" style={{ color: 'var(--warn)' }}>⚠ {x.warning}</div>}
        </div>
      ))}
    </Modal>
  );
}

// ------------------------------------------------------------------ directives

const whenLabels = () => [['always', t('always', 'sempre')], ['session', t('session start', 'início de sessão')], ['context', t('building context', 'ao montar contexto')],
  ['write', t('on write', 'ao gravar')], ['validate', t('on validate', 'ao validar')]];

export function DirectivesPanel({ projectId, schema, run, onClose }: { projectId: string; schema: Schema; run: Run; onClose: () => void }) {
  const [list, setList] = useState<Directive[]>([]);
  const empty = { id: '', text: '', when: ['context'], types: [] as string[], bases: [] as string[], priority: 5, enabled: true };
  const [draft, setDraft] = useState(empty);
  const load = useCallback(() => { api.directives(projectId).then(setList).catch(() => setList([])); }, [projectId]);
  useEffect(() => { load(); }, [load]);
  const save = async () => {
    const op: Operation = { op: 'upsert_directive', text: draft.text,
      definition: { when: draft.when, types: draft.types, bases: draft.bases, priority: draft.priority, enabled: draft.enabled } };
    if (draft.id) op.id = draft.id;
    if (await run(t('Directive', 'Diretiva'), [op])) { setDraft(empty); load(); }
  };
  const setEnabled = async (d: Directive, enabled: boolean) => {
    const op: Operation = { op: 'upsert_directive', id: d.id, text: d.text,
      definition: { when: d.when ?? [], types: d.types ?? [], bases: d.bases ?? [], priority: d.priority, enabled } };
    if (await run(enabled ? t('Enable directive', 'Ativar diretiva') : t('Disable directive', 'Desativar diretiva'), [op])) load();
  };
  const toggle = (l: string[], v: string) => (l.includes(v) ? l.filter((x) => x !== v) : [...l, v]);
  return (
    <Modal title={t('Directives for the LLM', 'Diretivas para a LLM')} onClose={onClose}>
      <p className="small muted">{t('Short instructions the platform adds to the LLM\'s responses when the trigger matches. The profile\'s are fixed; you edit the project\'s here (or turn them off without deleting).',
        'Instruções curtas que a plataforma injeta nas respostas da LLM quando o gatilho bate. As do perfil são fixas; as do projeto você edita aqui (ou desliga sem apagar).')}</p>
      {list.map((d) => (
        <div key={d.source + d.id} className="list-item" style={d.enabled ? undefined : { opacity: 0.6 }}>
          <div className="row">
            <div>{d.text}</div>
            {d.source === 'project' && (
              <>
                <button className="fixed ghost small" onClick={() => setEnabled(d, !d.enabled)}>{d.enabled ? t('turn off', 'desligar') : t('turn on', 'ligar')}</button>
                <button className="fixed ghost small" onClick={() => setDraft({ id: d.id, text: d.text, when: d.when ?? [], types: d.types ?? [], bases: d.bases ?? [], priority: d.priority, enabled: d.enabled })}>{t('edit', 'editar')}</button>
                <button className="fixed ghost small" onClick={() => confirm(t('Delete this directive?', 'Excluir esta diretiva?')) && run(t('Delete directive', 'Excluir diretiva'), [{ op: 'delete_directive', id: d.id }]).then(load)}>{t('delete', 'excluir')}</button>
              </>
            )}
          </div>
          <div className="small muted">
            {d.source === 'project' ? t('project', 'projeto') : d.source} · {t('when', 'quando')}: {(d.when ?? []).join(', ')}
            {d.types?.length ? ` · ${t('types', 'tipos')}: ${d.types.join(', ')}` : ''}{d.bases?.length ? ` · bases: ${d.bases.join(', ')}` : ''} · {t('priority', 'prioridade')} {d.priority}
            {!d.enabled && ` · ${t('off', 'desligada')}`}
          </div>
        </div>
      ))}
      <div className="section" style={{ marginTop: 16 }}>
        <h4>{draft.id ? t('Edit directive', 'Editar diretiva') : t('New directive', 'Nova diretiva')}</h4>
        <textarea rows={2} placeholder={t('E.g. Ana never lies outright; she omits.', 'Ex.: Ana nunca mente diretamente; ela omite.')} value={draft.text} onChange={(e) => setDraft({ ...draft, text: e.target.value })} />
        <div className="chips" style={{ margin: '6px 0' }}>
          {whenLabels().map(([v, l]) => <button key={v} className={'chip ' + (draft.when.includes(v) ? 'active' : '')} onClick={() => setDraft({ ...draft, when: toggle(draft.when, v) })}>{l}</button>)}
        </div>
        <div className="row">
          <div className="field"><label>{t('Only when these types are in play', 'Só quando estes tipos estão em jogo')}</label>
            <select multiple size={4} value={draft.types} onChange={(e) => setDraft({ ...draft, types: Array.from(e.target.selectedOptions).map((o) => o.value) })}>
              {schema.nodeTypes.map((x) => <option key={x.id} value={x.name}>{x.name}</option>)}
            </select></div>
          <div className="field"><label>{t('Or these base kinds', 'Ou estes tipos-base')}</label>
            <select multiple size={4} value={draft.bases} onChange={(e) => setDraft({ ...draft, bases: Array.from(e.target.selectedOptions).map((o) => o.value) })}>
              {BASES.map((b) => <option key={b} value={b}>{baseLabel(b)}</option>)}
            </select></div>
          <div className="field fixed" style={{ width: 90 }}><label>{t('Priority', 'Prioridade')}</label>
            <input type="number" value={draft.priority} onChange={(e) => setDraft({ ...draft, priority: Number(e.target.value) })} /></div>
        </div>
        <label className="check"><input type="checkbox" checked={draft.enabled} onChange={(e) => setDraft({ ...draft, enabled: e.target.checked })} /><span>{t('On', 'Ativa')}</span></label>
        <div className="row" style={{ marginTop: 8 }}>
          <button className="primary" disabled={!draft.text.trim() || draft.when.length === 0} onClick={save}>{draft.id ? t('Save', 'Salvar') : t('Create', 'Criar')}</button>
          {draft.id && <button className="fixed" onClick={() => setDraft(empty)}>{t('Cancel', 'Cancelar')}</button>}
        </div>
      </div>
    </Modal>
  );
}

// ------------------------------------------------------------------ writing context preview

export function WritingContextPanel({ projectId, unitId, onClose, onFocus }: { projectId: string; unitId: string; onClose: () => void; onFocus: (id: string) => void }) {
  const [budget, setBudget] = useState(6000);
  const [tail, setTail] = useState(3);
  const [secretsFirst, setSecretsFirst] = useState(false);
  const [ctx, setCtx] = useState<WritingContext | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const h = setTimeout(() => api.writingContext(projectId, unitId, { maxTokens: budget, tailParagraphs: tail, focus: secretsFirst ? ['secrets'] : undefined })
      .then((c) => { setCtx(c); setError(null); }).catch((e) => setError(e.message)), 200);
    return () => clearTimeout(h);
  }, [projectId, unitId, budget, tail, secretsFirst]);
  const link = (id: string, name: string) => <span className="tree node-link" onClick={() => { onFocus(id); onClose(); }}>{name}</span>;
  const secrets = ctx?.secrets?.length ? (
    <div className="section"><h4>🔒 {t('Secrets: do not reveal', 'Segredos: não revelar')} ({ctx.secrets.length})</h4>
      {ctx.secrets.map((s, i) => <div key={i} className="small">{s.text} {s.revealAt && <span className="muted">— {t('until', 'até')} {link(s.revealAt.id, s.revealAt.name)}</span>}</div>)}
    </div>
  ) : null;
  return (
    <Modal title={`${t('Context to write', 'Contexto para escrever')}: ${ctx?.unit.name ?? '…'}`} onClose={onClose}>
      <p className="small muted">{t('This is exactly the package the LLM gets from the writing_context tool. If something important is missing here, it is not recorded in the graph yet.',
        'É exatamente o pacote que a LLM recebe com a tool writing_context. Se algo importante não aparece aqui, falta registrar no grafo.')}</p>
      <div className="row">
        <div className="field">
          <label>{t('Budget', 'Orçamento')}: {budget} tokens {ctx && `· ${t('used', 'usado')} ≈ ${ctx.estimatedTokens}`}</label>
          <input type="range" min={800} max={20000} step={200} value={budget} onChange={(e) => setBudget(Number(e.target.value))} />
        </div>
        <div className="field fixed" style={{ width: 170 }}>
          <label>{t('Previous text paragraphs', 'Parágrafos do texto anterior')}</label>
          <input type="number" min={0} max={20} value={tail} onChange={(e) => setTail(Number(e.target.value))} />
        </div>
      </div>
      <label className="check small"><input type="checkbox" checked={secretsFirst} onChange={(e) => setSecretsFirst(e.target.checked)} /><span>{t('Secrets first (focus: secrets)', 'Segredos primeiro (focus: secrets)')}</span></label>
      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}
      {ctx && (
        <>
          {ctx.trimmed && ctx.trimmed.length > 0 && <p className="small" style={{ color: 'var(--warn)' }}>{t('Cut to fit', 'Cortado para caber')}: {ctx.trimmed.join('; ')}</p>}
          {secretsFirst && secrets}
          {ctx.guidance && ctx.guidance.length > 0 && (
            <div className="section"><h4>{t('Directives', 'Diretivas')}</h4><ul className="small" style={{ paddingLeft: 18, margin: 0 }}>{ctx.guidance.map((g) => <li key={g}>{g}</li>)}</ul></div>
          )}
          <div className="section"><h4>{t('Where it is', 'Onde está')}</h4>
            <div className="small">{[...(ctx.path ?? []), ctx.unit].map((u, i, a) => <span key={u.id}>{link(u.id, u.name)}{i < a.length - 1 ? ' › ' : ''}</span>)}</div>
            {ctx.previous?.map((u) => <div key={u.id} className="small"><span className="muted">{t('before', 'antes')}:</span> {link(u.id, u.name)} {u.summary && <span className="muted">— {u.summary}</span>}</div>)}
            {ctx.next && <div className="small"><span className="muted">{t('after', 'depois')}:</span> {link(ctx.next.id, ctx.next.name)}</div>}
          </div>
          {ctx.previousTail && ctx.previousTail.paragraphs.length > 0 && (
            <div className="section"><h4>{t('End of', 'Final de')} {ctx.previousTail.unitName}</h4>
              <blockquote className="small tail">{ctx.previousTail.paragraphs.map((p, i) => <p key={i}>{p}</p>)}</blockquote>
            </div>
          )}
          {ctx.parts && ctx.parts.length > 0 && (
            <div className="section"><h4>{t('Parts', 'Partes')} ({ctx.parts.length})</h4>
              {ctx.parts.map((p) => (
                <div key={p.id} className="list-item small">
                  <b>{link(p.id, p.name)}</b> <span className="muted">{p.type}{p.wordCount ? ` · ${p.wordCount} ${t('words', 'palavras')}` : ''}</span> {p.summary && <span>— {p.summary}</span>}
                  {p.attributes && Object.entries(p.attributes).map(([k, v]) => <div key={k} className="muted">· {k}: {String(v)}</div>)}
                </div>
              ))}
            </div>
          )}
          {(ctx.beatsHere?.length || ctx.beatsExpectedHere?.length) ? (
            <div className="section"><h4>Beats</h4>
              {ctx.beatsHere?.map((b) => <div key={b.beatId} className="small">✓ {b.name} <span className="muted">— {b.criteria}</span></div>)}
              {ctx.beatsExpectedHere?.map((b) => <div key={b.beatId} className="small">→ {t('expected here', 'esperado aqui')}: {b.name} <span className="muted">— {b.criteria}</span></div>)}
            </div>
          ) : null}
          <div className="section"><h4>{t('In play', 'Em jogo')} ({ctx.inPlay?.length ?? 0})</h4>
            {ctx.inPlay?.length ? ctx.inPlay.map((e) => (
              <div key={e.id} className="list-item small">
                <b>{link(e.id, e.name)}</b> <span className="muted">{e.type}</span> {e.summary && <span>— {e.summary}</span>}
                {e.state?.map((s) => <div key={s} className="muted">· {s}</div>)}
              </div>
            )) : <div className="small muted">{t('Nothing linked to this unit yet (set its cast, location…).', 'Nada ligado a esta unidade ainda (defina elenco, local…).')}</div>}
          </div>
          <div className="section"><h4>{t('Facts and laws', 'Fatos e leis')} ({ctx.facts?.length ?? 0})</h4>
            {ctx.facts?.map((f) => <div key={f.id} className="small"><b>{rigidityLabel(f.rigidity)}</b> · {f.text}</div>)}
          </div>
          {!secretsFirst && secrets}
          {ctx.revealedHere && ctx.revealedHere.length > 0 && (
            <div className="section"><h4>🔓 {t('Revealed in this unit', 'Revelados nesta unidade')}</h4>
              {ctx.revealedHere.map((s, i) => <div key={i} className="small">{s.text}</div>)}
            </div>
          )}
          {ctx.openThreads && ctx.openThreads.length > 0 && (
            <div className="section"><h4>{t('Open hooks', 'Ganchos abertos')}</h4>
              {ctx.openThreads.map((x) => <div key={x.id} className="small">{link(x.id, x.name)} <span className="muted">{x.unitsSincePlanted != null ? t(`${x.unitsSincePlanted} unit(s) ago`, `há ${x.unitsSincePlanted} unidade(s)`) : ''}</span></div>)}
            </div>
          )}
          {ctx.style && (
            <div className="section"><h4>{t('Style', 'Estilo')} <span className="muted small">({ctx.style.source})</span></h4>
              <blockquote className="small tail">{ctx.style.text}</blockquote>
            </div>
          )}
        </>
      )}
    </Modal>
  );
}
