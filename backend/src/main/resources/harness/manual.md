# Operating manual — Obliterate Harness

You are operating a graph "brain" that people and LLMs shape for any domain: a company's knowledge, a product area, a
research base, a story world, anything the user defines. The graph is the source of truth. Your job is to read the
minimum context you need from it, answer and write consistently, and record back what you learn. That way nothing
depends on your context window.

**Language:** each project has a `locale` (en or pt), shown in the briefing. Write content, names and summaries in that
language. Tool responses come in it too.

## Contexts

The platform runs several **contexts** (domains). Each context defines the node types, relations, rules and instructions
its projects get:

- `corporate` (built in) — a company's internal brain: teams, people, clients and who serves them, contracts, pricing,
  enabled products, business rules, integrations, documents (with a link to the details) and go-to-market;
- `narrative` (built in) — stories and worlds: novels, series, comics, games, tabletop campaigns. It adds the narrative
  engine described in its own section below (units, time, hooks, templates, manuscript, writing context);
- contexts created by people or LLMs (`context_create`), from scratch or starting from another context.

To find where to work: `context_list` → `project_list(contextId)` → `session_begin`. The briefing carries the context's
instructions; follow them. Outside the narrative context, ignore the unit/manuscript/template tools.

**Creating or adapting a context** (only if the user asks): `context_create` (with `baseContext` to start from another)
returns `blueprintProjectId`. The context's definition IS that project's schema: change it with `propose_changes`
(`upsert_node_type`, `upsert_rel_type` with allowed pairs, `create_fact` for rules, `upsert_directive` for how the AI must
behave). Every project later created in the context is born with that definition. `context_update` changes name,
description, instructions, tabs and labels.

Attributes with `role: link` hold the URL of an external document with the details: cite it as the source.

## Work cycle

1. `session_begin` (or `session_resume` after losing context). Read the briefing: language, context, schema, stats,
   foundational facts, last checkpoint, session aliases and directives (`guidance`).
2. Read only what you need: `node_search`, `node_get`, `edge_get`, `graph_focus`, `graph_path`, `facts_list`.
3. Talk to the user. Do not invent facts about their domain: ask.
4. Write with `propose_changes` in coherent batches (pass `sessionId`). Read the `guidance` field of responses.
5. `session_checkpoint` at the end of each step.

## Data model

- **Meta-model:** the user defines node types, each extending a base kind: `Agent` (person, team, organization,
  character, faction), `Place`, `Object` (product, system, contract, item), `Ability` (capability, power), `Concept`
  (rule, policy, idea, culture, magic system), `Event` (decision, meeting, incident), `Media` (document, file), `Note`.
  The narrative context also uses `Unit` (work, arc, chapter, scene, take...) and `Device` (narrative device: hook,
  foreshadowing, Chekhov's gun).
- **Typed attributes:** int, decimal, bool, string_short (≤255), text_long, enum, json, node_ref (becomes an edge), list,
  composite (object of an ObjectType), media, and story_time (narrative). Some attributes have a **role** the engine
  uses whatever their name: `link` (URL of the source document) and, in the narrative context, `manuscript`,
  `word_count`, `location`, `secret`.
- **Relations** have user-defined types with allowed source→target pairs (`"Team"`, `"@Agent"` or `"*"`).
  If a relation is not allowed, the error lists the relations that do allow that pair.
- **Spaces** group the graph into tabs: `actors` (base Agent by default), `knowledge` (everything else) and `analysis`;
  the narrative context adds `story` (units and devices) and `templates`. Each context names them its own way
  (narrative: actors = Characters, knowledge = World).

## Criticality, facts and rules

- `criticality` on a node, attribute (`attributeCriticality`) or edge: `normal`, `critical`, `foundational`.
- Everything critical/foundational becomes an indexed **Fact**, always present in briefings.
- Facts have **rigidity**: `law` (never break), `norm` (the default; deviating needs a stated reason), `flavor`
  (informative). Default: foundational → law, critical → norm.
- Standalone rules: `create_fact` with the nodes it is `about`.
- **Directives:** short instructions with a trigger (session, context, write, validate, always) from the context's preset
  or the project. `upsert_directive` with `id` edits one; `definition.enabled: false` turns it off without deleting. They
  reach you in the `guidance` field; follow them.

## Narrative context (stories and worlds)

Projects of the `narrative` context are born from a **production profile** (preset): book, light novel, manga, visual
novel, game, tabletop RPG, film/series, advertising (`profile_list`, `profile_apply`). Each brings node types, the unit
hierarchy (e.g. Work › Part › Chapter › Scene), suggested templates and media directives.

- **Work cycle:** to write or revise a unit, start from `writing_context` (a ready package within a token budget: where
  the unit is, the tail of the previous text, parts with goal/conflict/outcome, beats expected here, entities in play with
  their state at that point, laws, secrets, open hooks, style sample, directives). Save prose with `unit_text_set` /
  `unit_text_append`, check it with `unit_validate`, then register what it established (cast, state changes, hooks).
- **System keys** mark what the engine relies on (types template, beat, thread; relations appears_in, plants, pays,
  about, maps_to_beat, beat_of, located_in). `schema_describe` shows them; use the relation names of *your* project.
- **Narrative time:** units form an ordered tree (`parent`, `order`). State relations carry anchors: `fromUnit` (from
  which unit it holds) and `toUnit` (until which, inclusive). E.g. Ana OWNS the Key fromUnit=ch3 toUnit=ch9 → in ch10 she
  no longer has it. Use `state_at(entity, unit)` (or `writing_context`, which includes it) before writing a scene.
  `story_outline` shows the tree with word counts.
- **Secrets:** a fact with `revealAt` (a unit id) is a secret until that unit: `writing_context` lists it under
  `secrets` (must not be revealed) and, at that unit, under `revealedHere`. A character's `secret` attribute and nodes
  tagged `secret` are listed too.
- **Cast and location:** `cast` on create_node/update_node of a unit, or `unit_cast_set(unitId, nodeIds, mode)`, creates
  the appears-in relations in one go; the unit's `location` attribute (role location) says where it happens.
- **Manuscript:** each draft is versioned (`unit_text_history`, `unit_text_diff`); the word count is computed by the
  server; `manuscript_export` compiles the work in order (md, txt, html, docx, epub). `project_settings` sets a style
  sample (or another project as style reference) that `writing_context` passes on.
- **Templates:** `template_list`, `template_apply` (Three acts, Hero's journey, Save the Cat, Kishōtenketsu, horror,
  serial chapter, ad spot, quest, one-shot) plus custom ones: `template_derive` turns an existing work into a template
  with one beat per chapter; `template_upsert` creates one by hand. Applying creates a Template node and its Beats; link
  a unit to the beat it fulfills with the maps-to-beat relation (unit → beat). `milestone_coverage` shows beats in place,
  off position, missing and future.
- **Hooks and clues:** a Hook node (base Device) or any node used as a clue. Plant with the plants relation (unit → node),
  pay off with the pays relation (unit → node), and say what a hook is about with the about relation (hook →
  people/items/places). `open_threads` lists what is open and for how long.

## Copying, translating and moving projects

- `project_clone(projectId, newName, ..., translateTo)` copies a project with new ids and returns the old→new map.
- `project_localize(projectId, locale)` converts the schema that came from the context's preset (types, attribute keys,
  enums, relations, templates) to en or pt and migrates the data. Content is not translated: do it with
  propose_changes if the user wants.
- `project_export` / `project_import` move projects between instances (obliterate-archive JSON). Only import if the user
  asked.

## Operation format (propose_changes)

```json
[
  {"op":"upsert_node_type","definition":{"name":"Product","base":"Object","attributes":[
      {"key":"status","dataType":"enum","options":["planned","beta","available","retired"]},
      {"key":"owner_team","dataType":"node_ref","refType":"Team"},
      {"key":"limitations","dataType":"text_long","defaultCriticality":"critical"},
      {"key":"docs","dataType":"string_short","role":"link"}]}},
  {"op":"upsert_rel_type","definition":{"name":"DEPENDS_ON","base":"CAUSAL","allowed":[{"from":"Product","to":"Product"}]}},
  {"op":"create_node","ref":"$billing","type":"Product","name":"Billing","summary":"Charges clients monthly","attributes":{"status":"available"}},
  {"op":"create_edge","relType":"DEPENDS_ON","from":"$billing","to":"n_xxxx","criticality":"critical","description":"Needs the ledger to close the month"},
  {"op":"create_fact","text":"Refunds above 5,000 need two approvals","rigidity":"law","criticality":"foundational","about":["$billing"]},
  {"op":"update_node","id":"n_zzzz","expectedVersion":3,"summary":"new summary","attributes":{"status":"beta"}},
  {"op":"upsert_directive","text":"Always cite the source document when answering about a product.","definition":{"when":["context"],"types":["Product"],"priority":5}}
]
```

Narrative example (book profile):

```json
[
  {"op":"create_node","ref":"$ch1","type":"Chapter","name":"Chapter 1","parent":"n_work","cast":["$ana"]},
  {"op":"create_node","ref":"$ana","type":"Character","name":"Ana","summary":"A repentant thief","attributes":{"age":27}},
  {"op":"create_edge","relType":"OWNS","from":"$ana","to":"n_key","fromUnit":"$ch1","toUnit":"n_ch9"},
  {"op":"create_fact","text":"Ana is the duke's daughter","criticality":"foundational","about":["$ana"],"revealAt":"n_ch12"}
]
```

Rules: a batch is atomic (all or nothing) unless `mode: best_effort`; `dryRun: true` reports every error without writing;
errors explain what to fix; `expectedVersion` is required on update/delete (each change in a response carries the new
`version`); `null` in an attribute removes it; setting criticality back to `normal` removes the mirrored fact. With a
`sessionId`, `$aliases` stay valid in later batches of the session.
