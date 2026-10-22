import { useEffect, useRef, useState } from 'react';
import { api } from '../api';
import type { ImportResult, Project } from '../types';
import { t } from '../i18n';
import { Modal } from './Dialogs';

// ------------------------------------------------------------------ renaming a project

/** A project name with a pencil: click it to edit inline, Enter saves, Esc cancels. */
export function ProjectName({ project, onRenamed, onError, as = 'span' }: {
  project: Project; onRenamed: (p: Project) => void; onError: (msg: string) => void; as?: 'span' | 'h4';
}) {
  const [editing, setEditing] = useState(false);
  const Tag = as;
  if (editing) {
    return <RenameInput project={project} onDone={(p) => { setEditing(false); if (p) onRenamed(p); }} onError={onError} />;
  }
  return (
    <Tag className="proj-name">
      <span>{project.name}</span>
      <RenameButton onClick={() => setEditing(true)} />
    </Tag>
  );
}

export function RenameButton({ onClick }: { onClick: () => void }) {
  return (
    <button className="ghost pencil" onClick={(e) => { e.stopPropagation(); onClick(); }}
      title={t('Rename project', 'Renomear projeto')} aria-label={t('Rename project', 'Renomear projeto')}>✎</button>
  );
}

/** Inline input that saves the project's new name (PATCH /api/projects/{id}). */
export function RenameInput({ project, onDone, onError }: {
  project: Project; onDone: (p?: Project) => void; onError: (msg: string) => void;
}) {
  const [name, setName] = useState(project.name);
  const [busy, setBusy] = useState(false);
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => { ref.current?.select(); }, []);
  // after a refused save the input is enabled again: give it the focus back (Esc cancels from there)
  useEffect(() => { if (!busy) ref.current?.focus(); }, [busy]);
  const save = async () => {
    const clean = name.trim();
    if (clean === project.name) { onDone(); return; }
    if (!clean) { onError(t('The name cannot be empty', 'O nome não pode ficar vazio')); return; }
    setBusy(true);
    try {
      onDone(await api.updateProject(project.id, { name: clean }));
    } catch (e) {
      onError((e as Error).message);
      setBusy(false);
    }
  };
  return (
    <form className="rename" onClick={(e) => e.stopPropagation()} onSubmit={(e) => { e.preventDefault(); save(); }}>
      <input ref={ref} autoFocus value={name} disabled={busy} aria-label={t('Project name', 'Nome do projeto')}
        onChange={(e) => setName(e.target.value)} onKeyDown={(e) => { if (e.key === 'Escape') { e.stopPropagation(); onDone(); } }} />
      <button type="submit" className="primary small" disabled={busy || !name.trim()} title={t('Save', 'Salvar')}>✓</button>
      <button type="button" className="small" onClick={() => onDone()} title={t('Cancel', 'Cancelar')}>✕</button>
    </form>
  );
}

// ------------------------------------------------------------------ export and import

/** Export (one project or everything) and Import buttons. */
export function ArchiveActions({ projectId, onImported, small }: {
  projectId?: string; onImported: (r: ImportResult) => void; small?: boolean;
}) {
  const [importing, setImporting] = useState(false);
  const cls = small ? 'small' : 'fixed';
  return (
    <>
      <a className={'button ' + cls} href={api.archiveUrl(projectId)} download
        title={projectId
          ? t('Downloads this project (schema, nodes, relations, facts, directives and its context) as an obliterate-archive JSON',
            'Baixa este projeto (schema, nós, relações, fatos, diretivas e o contexto) como um JSON obliterate-archive')
          : t('Downloads every project and custom context as one obliterate-archive JSON (backup)',
            'Baixa todos os projetos e contextos personalizados num único JSON obliterate-archive (backup)')}>
        ⤓ {projectId ? t('Export', 'Exportar') : t('Export all', 'Exportar tudo')}</a>
      <button className={cls} onClick={() => setImporting(true)}
        title={t('Creates new projects from an obliterate-archive JSON', 'Cria projetos novos a partir de um JSON obliterate-archive')}>
        ⤒ {t('Import…', 'Importar…')}</button>
      {importing && <ImportDialog onClose={() => setImporting(false)} onImported={(r) => { setImporting(false); onImported(r); }} />}
    </>
  );
}

interface Preview { format?: string; version?: number; scope?: string; projects: { name: string; counts?: Record<string, number> }[]; contexts: { name?: string; kind?: string }[] }

export function ImportDialog({ onClose, onImported }: { onClose: () => void; onImported: (r: ImportResult) => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [recreate, setRecreate] = useState(true);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const pick = async (f: File | null) => {
    setFile(f); setPreview(null); setError(null); setName('');
    if (!f) return;
    try {
      const a = JSON.parse(await f.text());
      setPreview({ format: a.format, version: a.version, scope: a.scope, projects: a.projects ?? [], contexts: a.contexts ?? [] });
      if (a.format !== 'obliterate-archive') setError(t('This file is not an obliterate-archive (format field).', 'Este arquivo não é um obliterate-archive (campo format).'));
    } catch {
      setError(t('This file is not valid JSON.', 'Este arquivo não é um JSON válido.'));
    }
  };
  const run = async () => {
    if (!file) return;
    setBusy(true); setError(null);
    try {
      onImported(await api.importArchive(file, { recreateContexts: recreate, name: name.trim() || undefined }));
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };
  const custom = preview?.contexts.filter((c) => c.kind === 'custom') ?? [];
  return (
    <Modal title={t('Import projects', 'Importar projetos')} onClose={onClose} narrow
      footer={<><button onClick={onClose}>{t('Cancel', 'Cancelar')}</button>
        <button className="primary" disabled={!file || busy} onClick={run}>{busy ? t('Importing…', 'Importando…') : t('Import', 'Importar')}</button></>}>
      <div className="field"><label>{t('Archive (.json exported by Obliterate Harness)', 'Arquivo (.json exportado pelo Obliterate Harness)')}</label>
        <input type="file" accept=".json,application/json" onChange={(e) => pick(e.target.files?.[0] ?? null)} /></div>
      {preview && (
        <div className="section">
          <p className="small muted">{preview.format} v{preview.version ?? '?'} · {preview.scope ?? '—'}</p>
          <ul className="small" style={{ paddingLeft: 16, margin: '4px 0' }}>
            {preview.projects.map((p, i) => (
              <li key={i}><b>{p.name}</b>{p.counts && <span className="muted"> · {p.counts.nodes ?? 0} {t('nodes', 'nós')}, {p.counts.edges ?? 0} {t('relations', 'relações')}, {p.counts.facts ?? 0} {t('facts', 'fatos')}, {p.counts.directives ?? 0} {t('directives', 'diretivas')}</span>}</li>
            ))}
          </ul>
          {custom.length > 0 && <p className="small">{t('Custom contexts in the file', 'Contextos personalizados no arquivo')}: {custom.map((c) => c.name).join(', ')}</p>}
        </div>
      )}
      {preview && preview.projects.length === 1 && (
        <div className="field"><label>{t('Name of the imported project (optional)', 'Nome do projeto importado (opcional)')}</label>
          <input value={name} placeholder={preview.projects[0].name} onChange={(e) => setName(e.target.value)} /></div>
      )}
      <label className="check"><input type="checkbox" checked={recreate} onChange={(e) => setRecreate(e.target.checked)} />
        <span>{t('Recreate custom contexts that do not exist here', 'Recriar contextos personalizados que não existem aqui')}</span></label>
      <p className="hint">{t('Projects are created as new ones (new ids); nothing existing is changed. If anything in the file is invalid, nothing is imported.',
        'Os projetos são criados como novos (ids novos); nada que já existe é alterado. Se algo no arquivo for inválido, nada é importado.')}</p>
      {error && <p style={{ color: 'var(--danger)' }}>{error}</p>}
    </Modal>
  );
}

/** Toast text for an import. */
export const importSummary = (r: ImportResult) => {
  const ps = r.projects.map((p) => `"${p.name}"`).join(', ');
  const created = (r.contexts ?? []).filter((c) => c.action === 'created').map((c) => c.name);
  return t(`Imported ${ps}`, `Importado ${ps}`)
    + (created.length ? t(` · contexts recreated: ${created.join(', ')}`, ` · contextos recriados: ${created.join(', ')}`) : '')
    + ((r.notes ?? []).length ? ` · ${r.notes!.join(' · ')}` : '');
};
