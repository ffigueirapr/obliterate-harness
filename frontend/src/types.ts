import { t } from './i18n';

export type Criticality = 'normal' | 'critical' | 'foundational';

export const BASES = ['Agent', 'Place', 'Object', 'Ability', 'Concept', 'Event', 'Media', 'Note'] as const;
export const REL_BASES = ['PARTICIPATION', 'SOCIAL', 'POSSESSION', 'SPATIAL', 'CAUSAL', 'KNOWLEDGE', 'ORIGIN', 'REFERENCE'] as const;
export const DATA_TYPES = ['int', 'decimal', 'bool', 'string_short', 'text_long', 'enum', 'json', 'node_ref', 'list', 'composite', 'media'] as const;
export const SPACES = ['actors', 'knowledge', 'analysis'] as const;

const BASE_LABELS: Record<string, [string, string]> = {
  Agent: ['Agent', 'Agente'], Place: ['Place', 'Lugar'], Object: ['Object', 'Objeto'], Ability: ['Ability', 'Habilidade'],
  Concept: ['Concept', 'Conceito'], Event: ['Event', 'Evento'], Media: ['Media', 'Mídia'], Note: ['Note', 'Nota'],
};
const SPACE_LABELS: Record<string, [string, string]> = {
  actors: ['Actors', 'Atores'], knowledge: ['Knowledge', 'Conhecimento'], analysis: ['Analysis', 'Análise'],
};
export const baseLabel = (b?: string) => (b && BASE_LABELS[b] ? t(...BASE_LABELS[b]) : b ?? '');
export const spaceLabel = (s?: string) => (s && SPACE_LABELS[s] ? t(...SPACE_LABELS[s]) : s ?? '');
export const critLabel = (c?: string) => (c === 'foundational' ? t('foundational', 'fundacional') : c === 'critical' ? t('critical', 'crítico') : 'normal');
export const rigidityLabel = (r?: string) => (r === 'law' ? t('law', 'lei') : r === 'norm' ? t('norm', 'norma') : t('flavor', 'informativo'));

export interface Project {
  id: string;
  name: string;
  description?: string;
  profile?: string;
  requireReviewForLlm: boolean;
  createdAt?: string;
  /** language of the project's schema and content */
  locale?: 'en' | 'pt';
  /** context (domain) the project belongs to */
  context?: string;
  /** true for a context's definition project */
  blueprint?: boolean;
}

export interface ContextPreset { id: string; name: string; description?: string }

/** A context: the domain a set of projects runs in (built-in or created by people/LLMs). */
export interface ContextView {
  id: string;
  name: string;
  description?: string;
  icon?: string;
  color?: string;
  kind: 'builtin' | 'custom';
  locale?: 'en' | 'pt';
  presets?: ContextPreset[];
  defaultPreset?: string;
  blueprintProjectId?: string;
  baseContext?: string;
  instructions?: string;
  spaceLabels?: Record<string, string>;
  tabs: string[];
  projectCount: number;
}

export interface AttrDef {
  key: string;
  label?: string;
  dataType: string;
  required?: boolean;
  description?: string;
  options?: string[];
  itemType?: string;
  refType?: string;
  objectType?: string;
  maxLength?: number;
  defaultCriticality?: string;
  /** what the engine uses it for: link (URL of the source document) */
  role?: string;
}

export interface NodeTypeDef {
  id: string;
  name: string;
  base: string;
  description?: string;
  color?: string;
  strict?: boolean;
  attributes?: AttrDef[];
  systemKey?: string;
}

export interface RelTypeDef {
  id: string;
  name: string;
  base: string;
  description?: string;
  symmetric?: boolean;
  inverseName?: string;
  temporal?: boolean;
  allowed?: { from: string; to: string }[];
  systemKey?: string;
}

export interface ObjectTypeDef {
  id: string;
  name: string;
  description?: string;
  fields?: AttrDef[];
}

export interface Schema {
  nodeTypes: NodeTypeDef[];
  relTypes: RelTypeDef[];
  objectTypes?: ObjectTypeDef[];
  nodeCountByType?: Record<string, number>;
}

export interface GNode {
  id: string;
  type: string;
  base?: string;
  space?: string;
  name: string;
  summary?: string;
  description?: string;
  tags?: string[];
  criticality?: Criticality;
  attributes?: Record<string, unknown>;
  attributeCriticality?: Record<string, string>;
  refs?: Record<string, string[]>;
  version?: number;
  createdBy?: string;
  updatedBy?: string;
  updatedAt?: string;
}

export interface GEdge {
  id: string;
  relType: string;
  base?: string;
  kind: 'rel' | 'attr';
  from: string;
  fromName?: string;
  to: string;
  toName?: string;
  description?: string;
  criticality?: Criticality;
  tags?: string[];
  version?: number;
}

export interface Fact {
  id: string;
  text: string;
  rigidity: 'law' | 'norm' | 'flavor';
  criticality: Criticality;
  origin: string;
  about?: { id: string; name: string }[];
  version?: number;
}

export interface IndirectLink {
  from: string;
  to: string;
  hops: number;
  pathNodeIds: string[];
  pathRelTypes: string[];
}

export interface ViewResult {
  nodes: GNode[];
  edges: GEdge[];
  indirect: IndirectLink[];
  contextIds: string[];
  total: number;
  truncated: boolean;
}

export interface NodeDetail {
  node: GNode;
  edges: GEdge[];
  facts: Fact[];
}

export type Operation = Record<string, unknown> & { op: string };

export interface OpError { index: number; op: string; ref?: string; kind?: string; message: string }

export interface Proposal {
  changesetId?: string;
  status: string;
  ids?: Record<string, string>;
  changes?: { index: number; op: string; kind: string; id: string; label?: string; version?: number }[];
  message?: string;
  guidance?: string[];
  errors?: OpError[];
}

export interface ChangesetRow {
  id: string;
  actor: string;
  title?: string;
  status: string;
  operations: Operation[];
  result?: { index: number; op: string; kind: string; id: string; label?: string }[];
  error?: string;
  createdAt: string;
  decidedAt?: string;
}

export interface AuditEntry {
  id: number;
  changesetId?: string;
  actor: string;
  op: string;
  entityKind: string;
  entityId: string;
  before?: unknown;
  after?: unknown;
  createdAt: string;
}

export interface ProfileSummary {
  id: string;
  name: string;
  description?: string;
}

export interface Directive {
  id: string;
  text: string;
  when?: string[];
  bases?: string[];
  types?: string[];
  priority: number;
  enabled: boolean;
  source: string;
}

export interface FocusResult {
  center: { id: string; name: string };
  nodes: GNode[];
  edges: GEdge[];
  distance: Record<string, number>;
  levels: number;
  byLevel?: Record<string, number>;
  truncated: boolean;
}

export interface FocusState {
  nodeId: string;
  name: string;
  all: boolean;
}

export interface ViewFilter {
  types: string[];
  bases: string[];
  tags: string[];
  spaces: string[];
  minCriticality: string;
  includeIndirect: boolean;
  maxHops: number;
  relTypes: string[];
}

export interface CloneResult {
  projectId: string;
  name: string;
  idMap: Record<string, string>;
  counts?: Record<string, number>;
  notes?: string[];
}

export interface LocalizeReport {
  projectId: string;
  from: string;
  to: string;
  nodeTypes?: string[];
  relTypes?: string[];
  nodesMigrated: number;
  untouched?: string[];
  notes?: string[];
}

/** Result of POST /api/import (obliterate-archive). */
export interface ImportResult {
  projects: { sourceId: string; projectId: string; name: string; context?: string; counts?: Record<string, number> }[];
  contexts?: { sourceId: string; contextId: string; name: string; action: 'created' | 'existing' | 'matched' }[];
  notes?: string[];
}
