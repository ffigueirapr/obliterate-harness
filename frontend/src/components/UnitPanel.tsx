import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api } from '../api';
import { Modal } from './Dialogs';
import type { GNode, Schema, TextDiff, TextVersion, Validation } from '../types';
import { fmtDate, t } from '../i18n';

type Tab = 'text' | 'drafts' | 'cast' | 'validate';

const countWords = (s: string) => (s.match(/[\p{L}\p{N}][\p{L}\p{N}'’-]*/gu) ?? []).length;

/** Unit workbench: manuscript (prose/script) with versions and diff, cast, and validation against the canon. */
export function UnitPanel({ projectId, unitId, schema, onClose, onChanged, onFocus }: {
  projectId: string; unitId: string; schema: Schema; onClose: () => void; onChanged: () => void; onFocus: (id: string) => void;
}) {
  const [tab, setTab] = useState<Tab>('text');
  const [unit, setUnit] = useState<GNode | null>(null);
  const [text, setText] = useState('');
  const [saved, setSaved] = useState('');
  const [attr, setAttr] = useState<string>('');
  const [serverWords, setServerWords] = useState(0);
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [history, setHistory] = useState<TextVersion[]>([]);
  const [diffSel, setDiffSel] = useState<{ from?: number; to?: number }>({});
  const [diff, setDiff] = useState<TextDiff | null>(null);
  const [cast, setCast] = useState<string[]>([]);
  const [castSaved, setCastSaved] = useState<string[]>([]);
  const [candidates, setCandidates] = useState<GNode[]>([]);
  const [castQuery, setCastQuery] = useState('');
  const [validation, setValidation] = useState<Validation | null>(null);

  const appearsIn = schema.relTypes.find((r) => r.systemKey === 'appears_in')?.name;

  const loadUnit = useCallback(async () => {
    const d = await api.node(projectId, unitId);
    setUnit(d.node);
    const ids = d.edges.filter((e) => e.relType === appearsIn && e.to === unitId).map((e) => e.from);
    setCast(ids); setCastSaved(ids);
  }, [projectId, unitId, appearsIn]);

  const loadText = useCallback(async () => {
    try {
      const r = await api.unitText(projectId, unitId);
      setText(r.text ?? ''); setSaved(r.text ?? ''); setAttr(r.attribute); setServerWords(r.words); setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
    api.textHistory(projectId, unitId).then(setHistory).catch(() => setHistory([]));
  }, [projectId, unitId]);

  useEffect(() => { loadUnit().catch((e) => setError(e.message)); loadText(); }, [loadUnit, loadText]);
  useEffect(() => {
    api.nodes(projectId, { limit: 2000, detail: 'id' }).then((ns) => setCandidates(ns.filter((n) => n.base !== 'Unit' && n.base !== 'Note'))).catch(() => setCandidates([]));
  }, [projectId]);

  const dirty = text !== saved;
  const save = async (mode: 'replace' | 'append') => {
    setError(null);
    try {
      const r = await api.setUnitText(projectId, unitId, { text, mode, note: note || undefined });
      setInfo(t(`Saved as draft v${r.textVersion} · ${r.words} words`, `Salvo como rascunho v${r.textVersion} · ${r.words} palavras`));
      setNote('');
      await loadText();
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const showDiff = async (from?: number, to?: number) => {
    setDiffSel({ from, to });
    if (from === undefined) { setDiff(null); return; }
    try { setDiff(await api.textDiff(projectId, unitId, from, to)); } catch (e) { setError((e as Error).message); }
  };

  const restore = async (v: number) => {
    const r = await api.unitText(projectId, unitId, v);
    setText(r.text ?? '');
    setTab('text');
    setInfo(t(`Draft v${v} loaded in the editor; save to make it current.`, `Rascunho v${v} carregado no editor; salve para torná-lo o atual.`));
  };

  const saveCast = async (ids: string[]) => {
    setError(null);
    try {
      await api.setCast(projectId, unitId, ids, 'replace');
      await loadUnit();
      onChanged();
      setInfo(t('Cast saved', 'Elenco salvo'));
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const validate = async () => {
    setError(null);
    try { setValidation(await api.validateUnit(projectId, unitId, dirty ? text : undefined)); } catch (e) { setError((e as Error).message); }
  };

  const byId = useMemo(() => new Map(candidates.map((n) => [n.id, n])), [candidates]);
  const filtered = candidates.filter((n) => !castQuery.trim() || n.name.toLowerCase().includes(castQuery.toLowerCase()) || n.type.toLowerCase().includes(castQuery.toLowerCase()));
  const toggle = (id: string) => setCast(cast.includes(id) ? cast.filter((x) => x !== id) : [...cast, id]);
  const link = (id: string, name: string) => <span className="tree node-link" onClick={() => { onFocus(id); onClose(); }}>{name}</span>;

  const tabs: [Tab, string][] = [
    ['text', t('Manuscript', 'Manuscrito')], ['drafts', `${t('Drafts', 'Rascunhos')} (${history.length})`],
    ['cast', `${t('Cast', 'Elenco')} (${castSaved.length})`], ['validate', t('Validate', 'Validar')],
  ];

  return (
    <Modal title={`${unit?.name ?? '…'}${unit ? ` · ${unit.type}` : ''}`} onClose={() => {
      if (dirty && !confirm(t('Discard unsaved text?', 'Descartar o texto não salvo?'))) return;
      onClose();
    }}>
      <div className="tabs" style={{ marginBottom: 12 }}>
        {tabs.map(([k, l]) => <button key={k} className={tab === k ? 'active' : ''} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}
      {info && <p className="small" style={{ color: 'var(--ok)' }}>{info}</p>}

      {tab === 'text' && (
        <div>
          <textarea className="manuscript" rows={18} value={text} onChange={(e) => { setText(e.target.value); setInfo(null); }}
            placeholder={t('Write the prose/script of this unit…', 'Escreva a prosa/roteiro desta unidade…')} />
          <div className="row small muted" style={{ marginTop: 4 }}>
            <span>{countWords(text)} {t('words', 'palavras')}{dirty ? ` · ${t('unsaved', 'não salvo')}` : ''} · {t('server count', 'contagem do servidor')}: {serverWords}</span>
            <span className="fixed">{attr && <code>{attr}</code>}</span>
          </div>
          <div className="row" style={{ marginTop: 8 }}>
            <input placeholder={t('Note for this draft (optional)', 'Nota deste rascunho (opcional)')} value={note} onChange={(e) => setNote(e.target.value)} />
            <button className="primary fixed" disabled={!dirty} onClick={() => save('replace')}>{t('Save draft', 'Salvar rascunho')}</button>
          </div>
          <p className="hint">{t('Each save is a new draft in the history. The LLM writes here with unit_text_set / unit_text_append.',
            'Cada salvamento vira um rascunho no histórico. A LLM escreve aqui com unit_text_set / unit_text_append.')}</p>
        </div>
      )}

      {tab === 'drafts' && (
        <div>
          {history.length === 0 && <p className="muted">{t('No drafts yet.', 'Nenhum rascunho ainda.')}</p>}
          {history.length > 0 && (
            <table>
              <thead><tr><th>v</th><th>{t('Words', 'Palavras')}</th><th>{t('Who', 'Quem')}</th><th>{t('When', 'Quando')}</th><th>{t('Note', 'Nota')}</th><th>{t('Compare', 'Comparar')}</th><th /></tr></thead>
              <tbody>
                {history.map((h) => (
                  <tr key={h.version}>
                    <td>{h.version}</td>
                    <td>{h.words}</td>
                    <td>{h.actor === 'llm' ? '🤖 LLM' : h.actor === 'human' ? t('👤 you', '👤 você') : h.actor}</td>
                    <td className="small">{fmtDate(h.createdAt)}</td>
                    <td className="small">{h.note}</td>
                    <td className="small">
                      <button className={'ghost small' + (diffSel.from === h.version ? ' active' : '')} onClick={() => showDiff(h.version, diffSel.to)}>{t('from', 'de')}</button>
                      <button className={'ghost small' + (diffSel.to === h.version ? ' active' : '')} onClick={() => diffSel.from !== undefined && showDiff(diffSel.from, h.version)}>{t('to', 'para')}</button>
                    </td>
                    <td><button className="ghost small" onClick={() => restore(h.version)}>{t('load', 'carregar')}</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {diffSel.from !== undefined && (
            <div className="section" style={{ marginTop: 12 }}>
              <h4>v{diffSel.from} → {diffSel.to !== undefined ? `v${diffSel.to}` : t('current', 'atual')}
                {diff && <span className="muted small"> · +{diff.added} −{diff.removed}</span>}</h4>
              <pre className="diff">{diff?.lines.map((l, i) => (
                <div key={i} className={l.op === '+' ? 'add' : l.op === '-' ? 'del' : l.op === '…' ? 'gap' : ''}>{l.op === '=' ? '  ' : l.op + ' '}{l.text}</div>
              ))}</pre>
            </div>
          )}
        </div>
      )}

      {tab === 'cast' && (
        <div>
          {!appearsIn && <p className="muted">{t('This project has no appears-in relation (system key appears_in). Apply a profile or create it in Schema.',
            'Este projeto não tem relação de aparição (chave de sistema appears_in). Aplique um perfil ou crie em Schema.')}</p>}
          <div className="chips" style={{ marginBottom: 8 }}>
            {cast.map((id) => (
              <span key={id} className="chip">{byId.get(id)?.name ?? id}<button onClick={() => toggle(id)} title={t('remove', 'remover')}>×</button></span>
            ))}
            {cast.length === 0 && <span className="muted small">{t('Nobody in the cast yet.', 'Ninguém no elenco ainda.')}</span>}
          </div>
          <input placeholder={t('search characters, places, items…', 'buscar personagens, lugares, itens…')} value={castQuery} onChange={(e) => setCastQuery(e.target.value)} />
          <div style={{ maxHeight: 260, overflowY: 'auto', marginTop: 6 }}>
            {filtered.slice(0, 200).map((n) => (
              <label key={n.id} className="check small">
                <input type="checkbox" checked={cast.includes(n.id)} onChange={() => toggle(n.id)} />
                <span>{n.name}</span><span className="count">{n.type}</span>
              </label>
            ))}
          </div>
          <div className="row" style={{ marginTop: 8 }}>
            <button className="primary" disabled={!appearsIn || JSON.stringify([...cast].sort()) === JSON.stringify([...castSaved].sort())} onClick={() => saveCast(cast)}>{t('Save cast', 'Salvar elenco')}</button>
            <button className="fixed" onClick={() => setCast(castSaved)}>{t('Undo', 'Desfazer')}</button>
          </div>
          <p className="hint">{t(`Creates/removes the ${appearsIn ?? 'appears-in'} relations in one changeset (unit_cast_set).`,
            `Cria/remove as relações ${appearsIn ?? 'de aparição'} num único changeset (unit_cast_set).`)}</p>
        </div>
      )}

      {tab === 'validate' && (
        <div>
          <p className="small muted">{t('Checks the text against the graph: who is mentioned, the cast, names that are not in the graph, dead characters, and the laws, secrets and state to keep in mind.',
            'Confere o texto contra o grafo: quem é citado, o elenco, nomes que não estão no grafo, personagens mortos, e as leis, segredos e estado a observar.')}
            {dirty && ` ${t('(uses the unsaved text in the editor)', '(usa o texto não salvo do editor)')}`}</p>
          <button className="primary" onClick={validate}>{t('Validate', 'Validar')}</button>
          {validation && (
            <div style={{ marginTop: 12 }}>
              <div className="small muted">{validation.words} {t('words', 'palavras')}</div>
              {validation.warnings?.map((w) => <p key={w} className="small" style={{ color: 'var(--warn)' }}>⚠ {w}</p>)}
              <Finding title={t('Mentioned but not in the cast', 'Citados mas fora do elenco')} items={validation.mentionedNotInCast}
                render={(r) => <>{link(r.id, r.name)} <button className="ghost small" onClick={() => { const next = [...new Set([...castSaved, r.id])]; saveCast(next); setValidation({ ...validation, mentionedNotInCast: validation.mentionedNotInCast?.filter((x) => x.id !== r.id) }); }}>+ {t('cast', 'elenco')}</button></>} />
              <Finding title={t('In the cast but not mentioned', 'No elenco mas não citados')} items={validation.castNotMentioned} render={(r) => link(r.id, r.name)} />
              <Finding title={t('Names not in the graph', 'Nomes que não estão no grafo')} items={validation.possibleNewEntities?.map((x) => ({ id: x, name: x }))} render={(r) => r.name} />
              <Finding title={t('Secrets that must not leak here', 'Segredos que não podem vazar aqui')} items={validation.secretsToCheck?.map((s, i) => ({ id: String(i), name: s.text + (s.revealAt ? ` (${t('until', 'até')} ${s.revealAt.name})` : '') }))} render={(r) => <>🔒 {r.name}</>} />
              <Finding title={t('Rules to check', 'Regras a conferir')} items={validation.rulesToCheck?.map((f) => ({ id: f.id, name: `[${f.rigidity}] ${f.text}` }))} render={(r) => r.name} />
              <Finding title={t('State to compare', 'Estado a comparar')} items={validation.stateToCheck?.map((s) => ({ id: s, name: s }))} render={(r) => r.name} />
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}

function Finding<T extends { id: string; name: string }>({ title, items, render }: { title: string; items?: T[]; render: (x: T) => ReactNode }) {
  if (!items?.length) return null;
  return (
    <div className="section">
      <h4>{title} ({items.length})</h4>
      {items.map((x) => <div key={x.id} className="small" style={{ padding: '2px 0' }}>{render(x)}</div>)}
    </div>
  );
}
