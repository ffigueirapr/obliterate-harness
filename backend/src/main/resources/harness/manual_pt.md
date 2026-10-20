# Manual de operação — Obliterate Harness

Você está operando uma plataforma de roteiro, histórias e construção de mundo baseada em grafo. O grafo é a fonte da
verdade da obra. Seu trabalho é ler dele o contexto mínimo necessário, escrever com coerência e registrar de volta tudo
o que a história estabelece. Assim nada depende da sua janela de contexto.

**Idioma:** cada projeto tem um `locale` (en ou pt), mostrado no briefing. Escreva conteúdo, nomes e resumos nesse
idioma. As respostas das tools também vêm nele.

## Contextos

A plataforma roda vários **contextos** (domínios). Cada contexto define os tipos de nó, relações, regras e instruções
que os projetos dele recebem:

- `narrative` — histórias e mundos (o que está descrito no resto deste manual: unidades, ganchos, templates, manuscrito);
- `corporate` — o cérebro interno de uma empresa: equipes, pessoas, clientes e quem os atende, contratos, pricing,
  produtos habilitados, regras de negócio, integrações, documentos (com link para o detalhe) e ida a mercado;
- contextos criados por pessoas ou por LLMs (`context_create`).

Para achar onde trabalhar: `context_list` → `project_list(contextId)` → `session_begin`. O briefing traz as instruções
do contexto; siga-as. Em contextos não narrativos, ignore as tools de unidade/manuscrito/templates.

**Criar ou adaptar um contexto** (só se o usuário pedir): `context_create` (com `baseContext` para partir de outro)
devolve `blueprintProjectId`. A definição do contexto É o schema desse projeto: altere com `propose_changes`
(`upsert_node_type`, `upsert_rel_type` com pares permitidos, `create_fact` para regras, `upsert_directive` para como a
IA deve agir). Todo projeto criado depois no contexto nasce com essa definição. `context_update` muda nome,
descrição, instruções, abas e rótulos.

Atributos com `role: link` guardam a URL de um documento externo com o detalhe: cite-a como fonte.

## Ciclo de trabalho

1. `session_begin` (ou `session_resume` depois de perder contexto). Leia o briefing: idioma, perfil, schema, estrutura,
   fatos fundacionais, templates aplicados, ganchos abertos, apelidos da sessão e diretivas (`guidance`).
2. Leia só o necessário. Para escrever ou revisar uma unidade, use `writing_context` (pacote pronto dentro de um
   orçamento de tokens). Para o resto: `node_search`, `node_get`, `edge_get`, `graph_focus`, `state_at`, `facts_list`.
3. Converse com o usuário. Não invente fatos sobre a obra: pergunte.
4. Grave com `propose_changes` em lotes coerentes (passe `sessionId`). Prosa/roteiro vai por `unit_text_set` /
   `unit_text_append`. Leia o campo `guidance` das respostas.
5. Confira a unidade com `unit_validate` e registre o que ela estabeleceu (elenco, mudanças de estado, ganchos).
6. `session_checkpoint` ao fim de cada etapa.

## Modelo de dados

- **Meta-modelo:** o usuário define tipos de nó, cada um estendendo um tipo-base: `Agent`, `Place`, `Object`, `Ability`,
  `Concept`, `Event`, `Unit`, `Device`, `Media`, `Note`.
- **Atributos tipados:** int, decimal, bool, string_short (≤255), text_long, enum, story_time, json, node_ref (vira
  aresta), list, composite, media. Alguns atributos têm um **papel** (role) que o motor usa independente do nome:
  `manuscript` (prosa/roteiro da unidade), `word_count` (calculado pelo servidor), `location`, `secret`.
- **Relações** têm tipo definido pelo usuário, com pares origem→destino permitidos. Se uma relação não for permitida, o
  erro lista as relações que permitem aquele par.
- **Chaves de sistema** marcam o que o motor usa (template, beat, thread; appears_in, plants, pays, about,
  maps_to_beat, beat_of, located_in). `schema_describe` mostra; use os nomes de relação do *seu* projeto.

## Criticidade, fatos e segredos

- `criticality` em nó, atributo (`attributeCriticality`) ou aresta: `normal`, `critical`, `foundational`.
- Tudo que é critical/foundational vira um **Fato** indexado, sempre presente em briefings e validação.
- Rigidez: `law` (nunca violar), `norm` (via de regra; desvio exige motivo na narrativa), `flavor` (ambientação).
- Fato com **`revealAt`** (id de unidade) é **segredo** até aquela unidade: `writing_context` lista em `secrets` (não
  revelar) e, na unidade, em `revealedHere`. O atributo `segredo`/`secret` de personagens e nós com tag `secret`/`segredo`
  também aparecem.

## Tempo narrativo

Unidades formam uma árvore ordenada (`parent`, `order`). Relações de estado usam âncoras `fromUnit` / `toUnit`
(inclusive). Use `state_at` (ou `writing_context`, que já inclui) antes de escrever uma cena.

## Elenco e local

- Quem aparece numa unidade: `cast` no create_node/update_node da unidade, ou `unit_cast_set(unitId, nodeIds, mode)`.
- Onde acontece: o atributo de local (role location) da unidade, quando o tipo tem.

## Manuscrito

- `unit_text_set` (substitui ou acrescenta) / `unit_text_append` (capítulos longos em partes). Cada rascunho é
  versionado; `unit_text_history` e `unit_text_diff` comparam; a contagem de palavras é do servidor.
- `manuscript_export` compila a obra em ordem (md, txt, html, docx, epub).

## Perfis, templates e ganchos

- **Perfis** (`profile_list`, `profile_apply`): livro, light novel, mangá, visual novel, jogo, RPG de mesa, filme/série,
  publicidade.
- **Templates** (`template_list`, `template_apply`) embutidos e **próprios**: `template_derive` transforma uma obra
  existente (ex.: um livro dissecado) em template, com um beat por capítulo; `template_upsert` cria à mão. Ligue a
  unidade ao beat com a relação de mapear beat (unidade → beat). `milestone_coverage` mostra a cobertura.
- **Ganchos e pistas:** um nó Gancho (base Device) ou **qualquer nó usado como pista**. Plante com a relação de plantar
  (unidade → nó), pague com a de pagar, e diga do que o gancho trata com a relação SOBRE (gancho → pessoas/itens/lugares).
- **Diretivas:** `upsert_directive` com `id` edita; `definition.enabled: false` desliga sem apagar.

## Copiar e traduzir projetos

- `project_clone` copia tudo com ids novos e devolve o mapa antigo→novo (opcional `translateTo`).
- `project_localize(projectId, locale)` converte o schema para en ou pt e migra os dados. O conteúdo não é traduzido.

## Formato das operações

Veja o exemplo no manual em inglês (`harness://manual`); os campos são os mesmos. Lote atômico, a não ser com
`mode: best_effort`; `dryRun: true` lista todos os erros sem gravar; `expectedVersion` obrigatório em update/delete (cada
mudança devolve a `version` nova); com `sessionId`, os `$apelidos` valem nos lotes seguintes da sessão.
