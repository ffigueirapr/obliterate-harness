import { Fragment, useEffect, useState } from 'react';
import { api, type ChangeMode } from '../api';
import { Modal } from './Dialogs';
import type { AuditEntry, ChangesetRow, Fact, OutlineItem, Proposal } from '../types';
import { rigidityLabel } from '../types';
import { fmtDate, t } from '../i18n';

const who = (actor: string) => (actor === 'llm' ? '🤖 LLM' : actor === 'human' ? t('👤 you', '👤 você') : actor);

export function ChangesetsPanel({ projectId, onClose, onChanged }: { projectId: string; onClose: () => void; onChanged: () => void }) {
  const [status, setStatus] = useState('pending');
  const [rows, setRows] = useState<ChangesetRow[]>([]);
  const [open, setOpen] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = () => api.changesets(projectId, status === 'batch' ? undefined : status || undefined).then(setRows).catch((e) => setError(e.message));
  useEffect(() => { if (status !== 'batch') load(); }, [projectId, status]); // eslint-disable-line
  const act = async (fn: () => Promise<unknown>) => {
    setError(null);
    try { await fn(); await load(); onChanged(); } catch (e) { setError((e as Error).message); }
  };
  const tabs: [string, string][] = [['pending', t('Pending', 'Pendentes')], ['applied', t('Applied', 'Aplicados')], ['rejected', t('Rejected', 'Rejeitados')],
    ['', t('All', 'Todos')], ['batch', t('Batch (JSON)', 'Lote (JSON)')]];
  return (
    <Modal title="Changesets" onClose={onClose}>
      <div className="tabs" style={{ marginBottom: 12 }}>
        {tabs.map(([v, l]) => <button key={v} className={status === v ? 'active' : ''} onClick={() => setStatus(v)}>{l}</button>)}
      </div>
      {status === 'batch' ? <BatchConsole projectId={projectId} onChanged={onChanged} /> : (
        <>
          {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}
          {rows.length === 0 && <p className="muted">{t('Nothing here.', 'Nada aqui.')}</p>}
          {rows.map((r) => (
            <div key={r.id} className="list-item">
              <div className="row">
                <div>
                  <b>{r.title ?? t('(untitled)', '(sem título)')}</b>{' '}
                  <span className="muted small">{who(r.actor)} · {fmtDate(r.createdAt)} · {r.operations.length} ops · {r.status}</span>
                </div>
                <button className="fixed ghost small" onClick={() => setOpen(open === r.id ? null : r.id)}>{open === r.id ? t('hide', 'ocultar') : t('details', 'detalhes')}</button>
                {r.status === 'pending' && (
                  <>
                    <button className="fixed primary" onClick={() => act(() => api.approve(projectId, r.id))}>{t('Approve', 'Aprovar')}</button>
                    <button className="fixed danger" onClick={() => {
                      const reason = prompt(t('Reason for rejecting (the LLM will see it):', 'Motivo da rejeição (a LLM verá):')) ?? '';
                      act(() => api.reject(projectId, r.id, reason));
                    }}>{t('Reject', 'Rejeitar')}</button>
                  </>
                )}
              </div>
              {r.result && (
                <ul className="small" style={{ margin: '6px 0 0', paddingLeft: 18 }}>
                  {r.result.map((c) => <li key={c.index}><span className="muted">{c.op}</span> {c.label ?? c.id}</li>)}
                </ul>
              )}
              {r.error && <div className="small" style={{ color: 'var(--danger)' }}>{r.error}</div>}
              {open === r.id && <pre className="json">{JSON.stringify(r.operations, null, 2)}</pre>}
            </div>
          ))}
        </>
      )}
    </Modal>
  );
}

/** Paste a batch of operations (the same JSON the LLM sends), check it with a dry run, then apply it. */
function BatchConsole({ projectId, onChanged }: { projectId: string; onChanged: () => void }) {
  const [text, setText] = useState('[\n  {"op": "create_node", "ref": "$x", "type": "", "name": ""}\n]');
  const [title, setTitle] = useState('');
  const [result, setResult] = useState<Proposal | null>(null);
  const [error, setError] = useState<string | null>(null);
  const send = async (mode: ChangeMode) => {
    setError(null); setResult(null);
    let ops;
    try { ops = JSON.parse(text); } catch { setError(t('Invalid JSON', 'JSON inválido')); return; }
    if (!Array.isArray(ops)) { setError(t('Expected a list of operations', 'Esperava uma lista de operações')); return; }
    try {
      const r = await api.propose(projectId, title || t('Batch', 'Lote'), ops, mode);
      setResult(r);
      if (mode !== 'dry_run') onChanged();
    } catch (e) {
      setError((e as Error).message);
    }
  };
  return (
    <div>
      <p className="small muted">{t('The same format as propose_changes (see the manual). "Check" runs a dry run: it lists every error and writes nothing.',
        'O mesmo formato do propose_changes (veja o manual). “Conferir” roda um dry run: lista todos os erros e não grava nada.')}</p>
      <input placeholder={t('Title', 'Título')} value={title} onChange={(e) => setTitle(e.target.value)} style={{ marginBottom: 6 }} />
      <textarea rows={12} className="code" value={text} onChange={(e) => setText(e.target.value)} spellCheck={false} />
      <div className="row" style={{ marginTop: 6 }}>
        <button onClick={() => send('dry_run')}>{t('Check (dry run)', 'Conferir (dry run)')}</button>
        <button className="primary" onClick={() => send('atomic')} title={t('All or nothing', 'Tudo ou nada')}>{t('Apply', 'Aplicar')}</button>
        <button onClick={() => send('best_effort')} title={t('Apply the valid operations, report the rest', 'Aplica as operações válidas e relata o resto')}>{t('Apply valid ones', 'Aplicar as válidas')}</button>
      </div>
      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}
      {result && (
        <div className="section" style={{ marginTop: 10 }}>
          <h4>{result.status}{result.message ? ` · ${result.message}` : ''}</h4>
          {result.errors?.map((e) => <div key={e.index} className="small" style={{ color: 'var(--danger)' }}>#{e.index} {e.op}{e.ref ? ` ${e.ref}` : ''}: {e.message}</div>)}
          {result.changes?.map((c) => <div key={c.index} className="small"><span className="muted">#{c.index} {c.op}</span> {c.label ?? c.id}{c.version ? ` · v${c.version}` : ''}</div>)}
          {result.status === 'dry_run' && !result.errors?.length && <div className="small" style={{ color: 'var(--ok)' }}>{t('No errors: ready to apply.', 'Sem erros: pronto para aplicar.')}</div>}
          {result.guidance?.map((g) => <div key={g} className="small muted">→ {g}</div>)}
        </div>
      )}
    </div>
  );
}

export function AuditPanel({ projectId, entityId, onClose }: { projectId: string; entityId?: string; onClose: () => void }) {
  const [rows, setRows] = useState<AuditEntry[]>([]);
  const [open, setOpen] = useState<number | null>(null);
  useEffect(() => { api.audit(projectId, entityId).then(setRows).catch(() => setRows([])); }, [projectId, entityId]);
  const label = (v: unknown) => {
    const o = v as { name?: string; text?: string; fromName?: string; relType?: string; toName?: string } | undefined;
    return o?.name ?? o?.text ?? (o?.relType ? `${o.fromName} —${o.relType}→ ${o.toName}` : '');
  };
  return (
    <Modal title={t('Change history', 'Histórico de mudanças')} onClose={onClose}>
      <table>
        <thead><tr><th>{t('When', 'Quando')}</th><th>{t('Who', 'Quem')}</th><th>{t('Operation', 'Operação')}</th><th>{t('Target', 'Alvo')}</th><th /></tr></thead>
        <tbody>
          {rows.map((r) => (
            <Fragment key={r.id}>
              <tr>
                <td className="small">{r.createdAt.slice(0, 19).replace('T', ' ')}</td>
                <td>{who(r.actor)}</td>
                <td>{r.op}</td>
                <td>{label(r.after ?? r.before)}</td>
                <td><button className="ghost small" onClick={() => setOpen(open === r.id ? null : r.id)}>{open === r.id ? '−' : 'diff'}</button></td>
              </tr>
              {open === r.id && (
                <tr><td colSpan={5}>
                  <div className="row" style={{ alignItems: 'flex-start' }}>
                    <div><div className="small muted">{t('before', 'antes')}</div><pre className="json">{JSON.stringify(r.before, null, 2) ?? '—'}</pre></div>
                    <div><div className="small muted">{t('after', 'depois')}</div><pre className="json">{JSON.stringify(r.after, null, 2) ?? '—'}</pre></div>
                  </div>
                </td></tr>
              )}
            </Fragment>
          ))}
        </tbody>
      </table>
    </Modal>
  );
}

export function OutlineTree({ items, onFocus }: { items: OutlineItem[]; onFocus: (id: string) => void }) {
  if (items.length === 0) return <div className="muted small">{t('No narrative units yet.', 'Sem unidades narrativas ainda.')}</div>;
  return (
    <ul className="tree" style={{ paddingLeft: 0 }}>
      {items.map((i) => (
        <li key={i.id}>
          <span className="node-link" onClick={() => onFocus(i.id)} title={i.summary}>{i.name}</span>
          {i.status && <span className="muted small"> · {i.status}</span>}
          {!!i.wordCount && <span className="muted small"> · {i.wordCount} {t('w', 'p')}</span>}
          {i.children && i.children.length > 0 && <OutlineTree items={i.children} onFocus={onFocus} />}
        </li>
      ))}
    </ul>
  );
}

export function FactsPanel({ projectId, onClose, onFocus }: { projectId: string; onClose: () => void; onFocus: (id: string) => void }) {
  const [min, setMin] = useState('critical');
  const [q, setQ] = useState('');
  const [facts, setFacts] = useState<Fact[]>([]);
  useEffect(() => {
    const h = setTimeout(() => api.facts(projectId, { minCriticality: min || undefined, q: q || undefined }).then(setFacts).catch(() => setFacts([])), 200);
    return () => clearTimeout(h);
  }, [projectId, min, q]);
  return (
    <Modal title={t('Facts and rules', 'Fatos e regras')} onClose={onClose}>
      <p className="small muted">{t('Everything marked critical or foundational (on nodes, attributes or relations) shows up here. It is what the LLM always gets so it does not contradict itself. 🔒 = secret until a unit.',
        'Tudo que foi marcado como crítico ou fundacional (em nós, atributos ou relações) aparece aqui. É isso que a LLM sempre recebe para não se contradizer. 🔒 = segredo até uma unidade.')}</p>
      <div className="row" style={{ marginBottom: 12 }}>
        <input placeholder={t('search the facts', 'buscar nos fatos')} value={q} onChange={(e) => setQ(e.target.value)} />
        <select className="fixed" style={{ width: 200 }} value={min} onChange={(e) => setMin(e.target.value)}>
          <option value="critical">{t('Critical or foundational', 'Crítico ou fundacional')}</option>
          <option value="foundational">{t('Foundational only', 'Só fundacional')}</option>
          <option value="">{t('All', 'Todos')}</option>
        </select>
      </div>
      {facts.length === 0 && <p className="muted">{t('No facts.', 'Nenhum fato.')}</p>}
      {facts.map((f) => (
        <div key={f.id} className="list-item">
          <div><span className={`crit-${f.criticality}`}>{f.criticality === 'foundational' ? `◆ ${t('foundational', 'fundacional')}` : `● ${t('critical', 'crítico')}`}</span>
            {' · '}<b>{rigidityLabel(f.rigidity)}</b> <span className="muted small">· {f.origin === 'manual' ? 'manual' : t('mirrored', 'espelhado')}</span>
            {f.revealAt && <span className="small"> · 🔒 {t('until', 'até')} <span className="tree node-link" onClick={() => { onFocus(f.revealAt!.id); onClose(); }}>{f.revealAt.name}</span></span>}</div>
          <div>{f.text}</div>
          <div className="chips" style={{ marginTop: 4 }}>
            {f.about?.map((a) => <button key={a.id} className="chip" onClick={() => { onFocus(a.id); onClose(); }}>{a.name}</button>)}
          </div>
        </div>
      ))}
    </Modal>
  );
}
