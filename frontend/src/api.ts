import type {
  AppliedTemplate, AuditEntry, ChangesetRow, CloneResult, ContextView, Coverage, Directive, Fact, FocusResult, GEdge, GNode, ImportResult, IndirectLink, LocalizeReport, NodeDetail,
  Operation, OutlineItem, ProfileSummary, Project, Proposal, Schema, TemplateDef, TemplateSummary, TextDiff, TextRead, TextVersion, TextWrite, Thread,
  Validation, ViewFilter, ViewResult, WritingContext,
} from './types';
import { lang } from './i18n';

export class ApiError extends Error {}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { 'x-lang': lang() };
  if (body !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new ApiError(data?.detail ?? `${res.status} ${res.statusText}`);
  return data as T;
}

const qs = (params: Record<string, string | number | string[] | undefined>) => {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === '') continue;
    if (Array.isArray(v)) v.forEach((x) => sp.append(k, x));
    else sp.append(k, String(v));
  }
  const s = sp.toString();
  return s ? '?' + s : '';
};

export type ChangeMode = 'atomic' | 'dry_run' | 'best_effort';

export interface ContextInput {
  name?: string; description?: string; icon?: string; locale?: string; baseContext?: string; basePreset?: string;
  instructions?: string; spaceLabels?: Record<string, string>; tabs?: string[];
}

export const api = {
  contexts: () => call<ContextView[]>('GET', '/api/contexts'),
  context: (id: string) => call<ContextView>('GET', `/api/contexts/${id}`),
  createContext: (c: ContextInput) => call<ContextView>('POST', '/api/contexts', c),
  updateContext: (id: string, c: ContextInput) => call<ContextView>('PATCH', `/api/contexts/${id}`, c),
  deleteContext: (id: string, confirmName: string) => call<void>('DELETE', `/api/contexts/${id}${qs({ confirm: confirmName })}`),
  createProjectIn: (contextId: string, p: { name: string; description?: string; locale?: string; preset?: string; requireReviewForLlm?: boolean }) =>
    call<Project>('POST', `/api/contexts/${contextId}/projects`, p),
  /** includes the contexts' definition (blueprint) projects */
  projects: () => call<Project[]>('GET', '/api/projects?all=true'),
  createProject: (p: Partial<Project>) => call<Project>('POST', '/api/projects', p),
  updateProject: (id: string, p: Partial<Project>) => call<Project>('PATCH', `/api/projects/${id}`, p),
  deleteProject: (id: string, confirmName: string) => call<void>('DELETE', `/api/projects/${id}${qs({ confirm: confirmName })}`),
  cloneProject: (id: string, body: { newName: string; includeUnits?: boolean; includeFacts?: boolean; includeDirectives?: boolean; includeTemplates?: boolean; translateTo?: string }) =>
    call<CloneResult>('POST', `/api/projects/${id}/clone`, body),
  localizeProject: (id: string, locale: string) => call<LocalizeReport>('POST', `/api/projects/${id}/localize`, { locale }),
  exportUrl: (id: string, format: string) => `/api/projects/${id}/export${qs({ format })}`,
  schema: (pid: string) => call<Schema>('GET', `/api/projects/${pid}/schema`),

  view: (pid: string, f: ViewFilter, includeContext: boolean) =>
    call<ViewResult>('POST', `/api/projects/${pid}/graph/view`, { ...f, minCriticality: f.minCriticality || undefined, includeContext, limit: 2000 }),
  focus: (pid: string, nodeId: string, all: boolean, includeStructure: boolean) =>
    call<FocusResult>('GET', `/api/projects/${pid}/graph/focus${qs({ nodeId, hops: 1, all: String(all), includeStructure: String(includeStructure) })}`),
  indirect: (pid: string, ids: string[], maxHops: number, relTypes: string[]) =>
    call<IndirectLink[]>('POST', `/api/projects/${pid}/graph/indirect`, { ids, maxHops, relTypes }),
  nodes: (pid: string, params: { q?: string; types?: string[]; bases?: string[]; limit?: number; detail?: string }) =>
    call<GNode[]>('GET', `/api/projects/${pid}/nodes${qs(params)}`),
  node: (pid: string, id: string) => call<NodeDetail>('GET', `/api/projects/${pid}/nodes/${id}`),
  outline: (pid: string) => call<OutlineItem[]>('GET', `/api/projects/${pid}/outline`),
  facts: (pid: string, params: { minCriticality?: string; q?: string; about?: string[] }) =>
    call<Fact[]>('GET', `/api/projects/${pid}/facts${qs(params)}`),
  edgeContext: (pid: string, nodeId: string) => call<{ nodes: GNode[]; edges: GEdge[] }>('GET', `/api/projects/${pid}/graph/neighborhood${qs({ nodeId })}`),

  propose: (pid: string, title: string, operations: Operation[], mode?: ChangeMode) =>
    call<Proposal>('POST', `/api/projects/${pid}/changesets`, { title, operations, mode }),
  changesets: (pid: string, status?: string) => call<ChangesetRow[]>('GET', `/api/projects/${pid}/changesets${qs({ status })}`),
  approve: (pid: string, id: string) => call<Proposal>('POST', `/api/projects/${pid}/changesets/${id}/approve`),
  reject: (pid: string, id: string, reason: string) => call<void>('POST', `/api/projects/${pid}/changesets/${id}/reject`, { reason }),
  audit: (pid: string, entityId?: string) => call<AuditEntry[]>('GET', `/api/projects/${pid}/audit${qs({ entityId })}`),

  profiles: () => call<ProfileSummary[]>('GET', '/api/harness/profiles'),
  templateLibrary: () => call<TemplateSummary[]>('GET', '/api/harness/templates'),
  template: (id: string) => call<TemplateDef>('GET', `/api/harness/templates/${id}`),
  deriveTemplate: (body: { fromProjectId: string; unitType: string; name?: string; description?: string; includeChildren?: boolean }) =>
    call<TemplateDef>('POST', '/api/harness/templates/derive', body),
  upsertTemplate: (def: TemplateDef) => call<TemplateDef>('PUT', '/api/harness/templates', def),
  deleteTemplate: (id: string) => call<void>('DELETE', `/api/harness/templates/${id}`),
  applyProfile: (pid: string, profileId: string) => call<Proposal>('POST', `/api/projects/${pid}/profile/apply`, { profileId }),
  appliedTemplates: (pid: string) => call<AppliedTemplate[]>('GET', `/api/projects/${pid}/templates`),
  applyTemplate: (pid: string, body: { templateId: string; unitType?: string; plannedUnits?: number; scopeUnitId?: string }) =>
    call<Proposal>('POST', `/api/projects/${pid}/templates/apply`, body),
  coverage: (pid: string) => call<Coverage[]>('GET', `/api/projects/${pid}/coverage`),
  threads: (pid: string, includeClosed = false) => call<Thread[]>('GET', `/api/projects/${pid}/threads${qs({ includeClosed: String(includeClosed) })}`),
  writingContext: (pid: string, unitId: string, opts: { maxTokens?: number; tailParagraphs?: number; focus?: string[] } = {}) =>
    call<WritingContext>('GET', `/api/projects/${pid}/writing-context/${unitId}${qs(opts)}`),
  directives: (pid: string) => call<Directive[]>('GET', `/api/projects/${pid}/directives`),

  unitText: (pid: string, unitId: string, version?: number) => call<TextRead>('GET', `/api/projects/${pid}/units/${unitId}/text${qs({ version })}`),
  setUnitText: (pid: string, unitId: string, body: { text: string; mode?: 'replace' | 'append'; note?: string; attribute?: string }) =>
    call<TextWrite>('PUT', `/api/projects/${pid}/units/${unitId}/text`, body),
  textHistory: (pid: string, unitId: string) => call<TextVersion[]>('GET', `/api/projects/${pid}/units/${unitId}/text/history`),
  textDiff: (pid: string, unitId: string, from: number, to?: number) =>
    call<TextDiff>('GET', `/api/projects/${pid}/units/${unitId}/text/diff${qs({ from, to })}`),
  setCast: (pid: string, unitId: string, nodeIds: string[], mode: 'replace' | 'add' = 'replace') =>
    call<Proposal>('PUT', `/api/projects/${pid}/units/${unitId}/cast`, { nodeIds, mode }),
  validateUnit: (pid: string, unitId: string, text?: string) =>
    call<Validation>('POST', `/api/projects/${pid}/units/${unitId}/validate`, text === undefined ? {} : { text }),

  /** obliterate-archive download: one project, or everything when no id */
  archiveUrl: (pid?: string) => (pid ? `/api/projects/${pid}/export` : '/api/export'),
  importArchive: async (file: File, opts: { recreateContexts?: boolean; name?: string } = {}) => {
    const body = new FormData();
    body.append('file', file);
    const res = await fetch(`/api/import${qs({ recreateContexts: opts.recreateContexts === undefined ? undefined : String(opts.recreateContexts), name: opts.name })}`,
      { method: 'POST', headers: { 'x-lang': lang() }, body });
    const text = await res.text();
    const data = text ? JSON.parse(text) : null;
    if (!res.ok) throw new ApiError(data?.detail ?? `${res.status} ${res.statusText}`);
    return data as ImportResult;
  },
};
