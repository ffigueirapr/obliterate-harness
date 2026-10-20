import { t } from './i18n';

export type Criticality = 'normal' | 'critical' | 'foundational';

export const BASES = ['Agent', 'Place', 'Object', 'Ability', 'Concept', 'Event', 'Unit', 'Device', 'Media', 'Note'] as const;
export const REL_BASES = ['STRUCTURE', 'PARTICIPATION', 'SOCIAL', 'POSSESSION', 'SPATIAL', 'CAUSAL', 'KNOWLEDGE', 'ORIGIN', 'SETUP_PAYOFF', 'REFERENCE'] as const;
export const DATA_TYPES = ['int', 'decimal', 'bool', 'string_short', 'text_long', 'enum', 'story_time', 'json', 'node_ref', 'list', 'composite', 'media'] as const;
export const SPACES = ['world', 'characters', 'story', 'analysis', 'templates'] as const;

const BASE_LABELS: Record<string, [string, string]> = {
  Agent: ['Agent', 'Agente'], Place: ['Place', 'Lugar'], Object: ['Object', 'Objeto'], Ability: ['Ability', 'Habilidade'],
  Concept: ['Concept', 'Conceito'], Event: ['Event', 'Evento'], Unit: ['Narrative unit', 'Unidade narrativa'],
  Device: ['Narrative device', 'Recurso narrativo'], Media: ['Media', 'Mídia'], Note: ['Note', 'Nota'],
};
const SPACE_LABELS: Record<string, [string, string]> = {
  world: ['World', 'Mundo'], characters: ['Characters', 'Personagens'], story: ['Story', 'Roteiro'],
  analysis: ['Analysis', 'Análise'], templates: ['Templates', 'Templates'],
};
export const baseLabel = (b?: string) => (b && BASE_LABELS[b] ? t(...BASE_LABELS[b]) : b ?? '');
export const spaceLabel = (s?: string) => (s && SPACE_LABELS[s] ? t(...SPACE_LABELS[s]) : s ?? '');
export const critLabel = (c?: string) => (c === 'foundational' ? t('foundational', 'fundacional') : c === 'critical' ? t('critical', 'crítico') : 'normal');
export const rigidityLabel = (r?: string) => (r === 'law' ? t('law', 'lei') : r === 'norm' ? t('norm', 'norma') : t('flavor', 'ambientação'));

export interface Project {
  id: string;
  name: string;
  description?: string;
  profile?: string;
  requireReviewForLlm: boolean;
  createdAt?: string;
  /** language of the project's schema and content */
  locale?: 'en' | 'pt';
  styleSample?: string;
  styleReference?: string;
  /** context (domain) the project belongs to; absent = narrative */
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
  narrative: boolean;
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
  /** what the engine uses it for: manuscript, word_count, location, secret */
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
  parent?: string;
  order?: number;
  sortKey?: string;
  version?: number;
  createdBy?: string;
  updatedBy?: string;
  updatedAt?: string;
  wordCount?: number;
}

export interface GEdge {
  id: string;
  relType: string;
  base?: string;
  kind: 'rel' | 'attr' | 'structure';
  from: string;
  fromName?: string;
  to: string;
  toName?: string;
  description?: string;
  criticality?: Criticality;
  tags?: string[];
  fromUnit?: string;
  toUnit?: string;
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
  /** secret until this unit */
  revealAt?: { id: string; name: string };
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

export interface OutlineItem {
  id: string;
  type: string;
  name: string;
  summary?: string;
  status?: string;
  wordCount?: number;
  children?: OutlineItem[];
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
  units?: string[];
  suggestedTemplates?: string[];
}

export interface TemplateSummary {
  id: string;
  name: string;
  description?: string;
  scope?: 'obra' | 'unidade';
  media?: string[];
  beats: number;
  custom?: boolean;
  source?: string;
}

export interface AppliedTemplate {
  id: string;
  name: string;
  templateId?: string;
  unitType?: string;
  plannedUnits?: number;
  scopeUnitId?: string;
  beats: number;
}

export interface BeatStatus {
  beatId: string;
  key?: string;
  name: string;
  expected: number;
  function?: string;
  criteria?: string;
  required: boolean;
  units?: { id: string; name: string; position: number }[];
  status: 'ok' | 'off_position' | 'missing' | 'optional_missing' | 'future';
  note?: string;
}

export interface Coverage {
  templateNodeId: string;
  templateName: string;
  unitType: string;
  scopeUnitId?: string;
  unitsWritten: number;
  unitsPlanned: number;
  progress: number;
  beats?: BeatStatus[];
  unitsWithoutBeat?: { id: string; name: string }[];
  summary?: string[];
}

export interface Thread {
  id: string;
  name: string;
  type: string;
  kind?: string;
  status: string;
  summary?: string;
  plantedIn?: { id: string; name: string }[];
  paidIn?: { id: string; name: string }[];
  about?: { id: string; name: string }[];
  unitsSincePlanted?: number;
  /** a plain node planted as a clue (not a Hook) */
  clue?: boolean;
  warning?: string;
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

export interface WritingContext {
  unit: GNode;
  path?: { id: string; name: string; type?: string; summary?: string }[];
  previous?: { id: string; name: string; summary?: string }[];
  next?: { id: string; name: string; summary?: string };
  beatsHere?: BeatStatus[];
  beatsExpectedHere?: BeatStatus[];
  previousTail?: { unitId: string; unitName: string; paragraphs: string[] };
  parts?: { id: string; type: string; name: string; summary?: string; attributes?: Record<string, unknown>; wordCount?: number }[];
  inPlay?: { id: string; type: string; name: string; summary?: string; criticality?: string; state?: string[] }[];
  facts?: Fact[];
  secrets?: Secret[];
  revealedHere?: Secret[];
  style?: { source: string; text: string };
  openThreads?: Thread[];
  guidance?: string[];
  estimatedTokens: number;
  trimmed?: string[];
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
  includeStructure: boolean;
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

export interface Secret {
  kind: string;
  text: string;
  about?: { id: string; name: string }[];
  revealAt?: { id: string; name: string };
}

export interface TextRead { unitId: string; unitName: string; attribute: string; textVersion?: number; words: number; text?: string }
export interface TextWrite { unitId: string; attribute: string; nodeVersion: number; textVersion: number; words: number; characters: number }
export interface TextVersion { version: number; attrKey: string; words: number; actor: string; note?: string; createdAt: string }
export interface TextDiff { added: number; removed: number; lines: { op: '+' | '-' | ' ' | string; text: string }[] }

export interface Validation {
  unit: { id: string; name: string };
  words: number;
  mentionedNotInCast?: { id: string; name: string }[];
  castNotMentioned?: { id: string; name: string }[];
  possibleNewEntities?: string[];
  warnings?: string[];
  stateToCheck?: string[];
  rulesToCheck?: Fact[];
  secretsToCheck?: Secret[];
  directives?: string[];
  instructions?: string[];
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
  templatesTranslated: number;
  untouched?: string[];
  notes?: string[];
}

export interface BeatDef { key?: string; name: string; position: number; function?: string; criteria?: string; required?: boolean }

export interface TemplateDef {
  id?: string;
  name: string;
  description?: string;
  scope?: 'obra' | 'unidade';
  media?: string[];
  source?: string;
  beats: BeatDef[];
  custom?: boolean;
  locale?: string;
}