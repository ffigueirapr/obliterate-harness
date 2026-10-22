import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';
import { Modal } from './Dialogs';
import type { Directive, Operation, Schema } from '../types';
import { BASES, baseLabel } from '../types';
import { t } from '../i18n';

type Run = (title: string, ops: Operation[]) => Promise<boolean>;

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
      <p className="small muted">{t('Short instructions the platform adds to the LLM\'s responses when the trigger matches. The context preset\'s are fixed; you edit the project\'s here (or turn them off without deleting).',
        'Instruções curtas que a plataforma injeta nas respostas da LLM quando o gatilho bate. As do preset do contexto são fixas; as do projeto você edita aqui (ou desliga sem apagar).')}</p>
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
        <textarea rows={2} placeholder={t('E.g. Always cite the source document when answering about a product.', 'Ex.: Sempre cite o documento de origem ao responder sobre um produto.')} value={draft.text} onChange={(e) => setDraft({ ...draft, text: e.target.value })} />
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
