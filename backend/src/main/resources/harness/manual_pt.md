# Manual de operação — Obliterate Harness

Você está operando um "cérebro" em grafo que pessoas e LLMs moldam para qualquer domínio: o conhecimento de uma empresa,
uma área de produto, uma base de pesquisa, o mundo de uma história, o que o usuário definir. O grafo é a fonte da
verdade. Seu trabalho é ler dele o contexto mínimo necessário, responder e gravar com coerência, e registrar de volta o
que aprender. Assim nada depende da sua janela de contexto.

**Idioma:** cada projeto tem um `locale` (en ou pt), mostrado no briefing. Escreva conteúdo, nomes e resumos nesse
idioma. As respostas das tools também vêm nele.

## Contextos

A plataforma roda vários **contextos** (domínios). Cada contexto define os tipos de nó, relações, regras e instruções
que os projetos dele recebem:

- `corporate` (embutido) — o cérebro interno de uma empresa: equipes, pessoas, clientes e quem os atende, contratos,
  pricing, produtos habilitados, regras de negócio, integrações, documentos (com link para o detalhe) e ida a mercado;
- `narrative` (embutido) — histórias e mundos: romances, séries, quadrinhos, jogos, campanhas de RPG. Traz o motor
  narrativo descrito na seção própria abaixo (unidades, tempo, ganchos, templates, manuscrito, contexto de escrita);
- contextos criados por pessoas ou por LLMs (`context_create`), do zero ou a partir de outro contexto.

Para achar onde trabalhar: `context_list` → `project_list(contextId)` → `session_begin`. O briefing traz as instruções
do contexto; siga-as. Fora do contexto narrativo, ignore as tools de unidade/manuscrito/templates.

**Criar ou adaptar um contexto** (só se o usuário pedir): `context_create` (com `baseContext` para partir de outro)
devolve `blueprintProjectId`. A definição do contexto É o schema desse projeto: altere com `propose_changes`
(`upsert_node_type`, `upsert_rel_type` com pares permitidos, `create_fact` para regras, `upsert_directive` para como a
IA deve agir). Todo projeto criado depois no contexto nasce com essa definição. `context_update` muda nome,
descrição, instruções, abas e rótulos.

Atributos com `role: link` guardam a URL de um documento externo com o detalhe: cite-a como fonte.

## Ciclo de trabalho

1. `session_begin` (ou `session_resume` depois de perder contexto). Leia o briefing: idioma, contexto, schema,
   estatísticas, fatos fundacionais, último checkpoint, apelidos da sessão e diretivas (`guidance`).
2. Leia só o necessário: `node_search`, `node_get`, `edge_get`, `graph_focus`, `graph_path`, `facts_list`.
3. Converse com o usuário. Não invente fatos sobre o domínio dele: pergunte.
4. Grave com `propose_changes` em lotes coerentes (passe `sessionId`). Leia o campo `guidance` das respostas.
5. `session_checkpoint` ao fim de cada etapa.

## Modelo de dados

- **Meta-modelo:** o usuário define tipos de nó, cada um estendendo um tipo-base: `Agent` (pessoa, equipe,
  organização, personagem, facção), `Place`, `Object` (produto, sistema, contrato, item), `Ability` (capacidade, poder),
  `Concept` (regra, política, ideia, cultura, sistema de magia), `Event` (decisão, reunião, incidente), `Media`
  (documento, arquivo), `Note`. O contexto narrativo usa também `Unit` (obra, arco, capítulo, cena, take...) e `Device`
  (recurso narrativo: gancho, prenúncio, arma de Tchekhov).
- **Atributos tipados:** int, decimal, bool, string_short (≤255), text_long, enum, json, node_ref (vira aresta), list,
  composite (objeto de um ObjectType), media e story_time (narrativo). Alguns atributos têm um **papel** (role) que o
  motor usa independente do nome: `link` (URL do documento de origem) e, no narrativo, `manuscript`, `word_count`,
  `location`, `secret`.
- **Relações** têm tipo definido pelo usuário, com pares origem→destino permitidos (`"Equipe"`, `"@Agent"` ou `"*"`).
  Se uma relação não for permitida, o erro lista as relações que permitem aquele par.
- **Espaços** agrupam o grafo em abas: `actors` (base Agent por padrão), `knowledge` (o resto) e `analysis`; o
  narrativo acrescenta `story` (unidades e recursos narrativos) e `templates`. Cada contexto dá o próprio nome a eles
  (narrativo: actors = Personagens, knowledge = Mundo).

## Criticidade, fatos e regras

- `criticality` em nó, atributo (`attributeCriticality`) ou aresta: `normal`, `critical`, `foundational`.
- Tudo que é critical/foundational vira um **Fato** indexado, sempre presente nos briefings.
- Rigidez: `law` (nunca violar), `norm` (o padrão; desvio exige motivo explícito), `flavor` (informativo).
  Padrão: foundational → law, critical → norm.
- Regras avulsas: `create_fact` com os nós de que trata (`about`).
- **Diretivas:** instruções curtas com gatilho (session, context, write, validate, always), vindas do preset do contexto ou
  do projeto. `upsert_directive` com `id` edita; `definition.enabled: false` desliga sem apagar. Elas chegam no campo
  `guidance`; siga-as.

## Contexto narrativo (histórias e mundos)

Projetos do contexto `narrative` nascem de um **perfil de produção** (preset): livro, light novel, mangá, visual novel,
jogo, RPG de mesa, filme/série, publicidade (`profile_list`, `profile_apply`). Cada um traz tipos de nó, a hierarquia de
unidades (ex.: Obra › Parte › Capítulo › Cena), templates sugeridos e diretivas da mídia.

- **Ciclo de escrita:** para escrever ou revisar uma unidade, comece pelo `writing_context` (pacote pronto dentro de um
  orçamento de tokens: onde a unidade está, final do texto anterior, partes com objetivo/conflito/resultado, beats
  esperados, entidades em jogo com o estado delas naquele ponto, leis, segredos, ganchos abertos, amostra de estilo,
  diretivas). Grave a prosa com `unit_text_set` / `unit_text_append`, confira com `unit_validate` e registre o que a
  unidade estabeleceu (elenco, mudanças de estado, ganchos).
- **Chaves de sistema** marcam o que o motor usa (tipos template, beat, thread; relações appears_in, plants, pays, about,
  maps_to_beat, beat_of, located_in). `schema_describe` mostra; use os nomes de relação do *seu* projeto.
- **Tempo narrativo:** unidades formam uma árvore ordenada (`parent`, `order`). Relações de estado usam âncoras
  `fromUnit` (a partir de qual unidade vale) e `toUnit` (até qual, inclusive). Ex.: Ana POSSUI a Chave fromUnit=cap3
  toUnit=cap9 → no cap10 ela não tem mais. Use `state_at(entidade, unidade)` (ou `writing_context`, que já inclui) antes
  de escrever uma cena. `story_outline` mostra a árvore com contagem de palavras.
- **Segredos:** fato com `revealAt` (id de unidade) é segredo até aquela unidade: `writing_context` lista em `secrets`
  (não revelar) e, na unidade, em `revealedHere`. O atributo `segredo`/`secret` de personagens e nós com tag
  `secret`/`segredo` também aparecem.
- **Elenco e local:** `cast` no create_node/update_node da unidade, ou `unit_cast_set(unitId, nodeIds, mode)`, cria as
  relações de aparição de uma vez; o atributo de local da unidade (role location) diz onde acontece.
- **Manuscrito:** cada rascunho é versionado (`unit_text_history`, `unit_text_diff`); a contagem de palavras é do
  servidor; `manuscript_export` compila a obra em ordem (md, txt, html, docx, epub). `project_settings` define uma amostra
  de estilo (ou outro projeto como referência) que o `writing_context` repassa.
- **Templates:** `template_list`, `template_apply` (Três atos, Jornada do herói, Save the Cat, Kishōtenketsu, terror,
  capítulo serial, spot, quest, one-shot) e templates **próprios**: `template_derive` transforma uma obra existente em
  template, com um beat por capítulo; `template_upsert` cria à mão. Aplicar cria um nó Template e seus Beats; ligue a
  unidade ao beat que ela cumpre com a relação de mapear beat (unidade → beat). `milestone_coverage` mostra beats no
  lugar, fora de posição, faltando e futuros.
- **Ganchos e pistas:** um nó Gancho (base Device) ou qualquer nó usado como pista. Plante com a relação de plantar
  (unidade → nó), pague com a de pagar, e diga do que o gancho trata com a relação SOBRE (gancho →
  pessoas/itens/lugares). `open_threads` lista o que está aberto e há quanto tempo.

## Copiar, traduzir e mover projetos

- `project_clone(projectId, newName, ..., translateTo)` copia o projeto com ids novos e devolve o mapa antigo→novo.
- `project_localize(projectId, locale)` converte para en ou pt o schema que veio do preset do contexto (tipos, chaves de
  atributo, enums, relações, templates) e migra os dados. O conteúdo não é traduzido.
- `project_export` / `project_import` movem projetos entre instâncias (JSON obliterate-archive). Só importe se o usuário
  pedir.

## Formato das operações

Veja os exemplos no manual em inglês (`harness://manual`); os campos são os mesmos. Lote atômico, a não ser com
`mode: best_effort`; `dryRun: true` lista todos os erros sem gravar; `expectedVersion` obrigatório em update/delete (cada
mudança devolve a `version` nova); `null` num atributo o remove; voltar a criticidade para `normal` remove o fato
espelhado; com `sessionId`, os `$apelidos` valem nos lotes seguintes da sessão.
