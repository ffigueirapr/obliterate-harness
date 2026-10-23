# Guia do contexto narrativo — histórias e mundos no Obliterate

> Como o Obliterate modela histórias (o motor que veio do Harness Machine), e um roteiro passo a passo para criar um mundo,
> os personagens, a estrutura e escrever a obra com uma LLM, sem perder continuidade.
>
> Referências que acompanham este guia:
> - [referencia_schema.md](referencia_schema.md) — tabelas geradas do motor: todos os perfis, tipos, atributos (pt/en),
>   relações, diretivas e os 10 templates com seus beats.
> - [schema_narrativo.json](schema_narrativo.json) — o mesmo conteúdo legível por máquina (para checagens e outras ferramentas).
> - `scripts/migrate_from_harness_machine.mjs` — a migração dos projetos do Harness Machine (seção 12).

---

## 1. A ideia em uma página

O grafo é o **cânone** da obra. Tudo que a história estabelece (quem é quem, onde as coisas estão, o que cada um sabe,
o que ainda é segredo, que regras o mundo tem) fica gravado como nós, relações e fatos. A LLM não precisa lembrar de nada:
antes de escrever uma cena ela pede ao grafo o pacote certo (`writing_context`), escreve, confere (`unit_validate`) e
registra de volta o que mudou.

Três ideias seguram tudo:

1. **Cânone tipado** — personagens, lugares, itens, culturas, sistemas de magia… são tipos com atributos tipados. Regras
   que não podem ser quebradas viram **fatos** com rigidez (`law`, `norm`, `flavor`).
2. **Tempo narrativo** — capítulos e cenas formam uma árvore ordenada; relações de estado ("Ana possui a Chave") dizem
   **de qual unidade até qual unidade** valem. O motor responde "como estava o mundo no capítulo 7?" (`state_at`).
3. **Estrutura e promessas** — templates (Três atos, Jornada do herói…) dizem que marcos a história precisa cumprir e
   onde; ganchos e pistas registram o que foi prometido ao leitor e ainda não foi pago.

## 2. Onde isso mora no Obliterate

| Conceito | No Obliterate |
|---|---|
| Domínio | contexto embutido **`narrative`** ("Histórias e mundos"). O motor narrativo só existe nele (e em contextos criados a partir dele); nos outros contextos a plataforma continua neutra. |
| Tipo de obra | **perfil** (preset) escolhido ao criar o projeto: `livro`, `light_novel`, `manga`, `visual_novel`, `jogo`, `rpg_mesa`, `filme_serie`, `publicidade`. |
| Obra | um **projeto** no contexto narrativo. |
| Idioma | `locale` do projeto (`pt` ou `en`): idioma do schema e do que a LLM escreve. `project_localize` converte. |
| Abas | `actors` = **Personagens**, `knowledge` = **Mundo**, `story` = **Roteiro**, `analysis` = Análise, `templates` = Templates. |
| Seu próprio "tipo de história" | `context_create` com `baseContext: narrative` (e um preset): um contexto narrativo seu, com tipos extras, regras e instruções próprias. |

Criar uma obra pela LLM: `project_create(name, contextId: "narrative", profile: "livro", locale: "pt")`.
Pela interface: tela inicial → **Stories & worlds** → **+ New project**.

## 3. Modelo de dados

### 3.1 Tipos-base

Todo tipo de nó estende um tipo-base. O motor usa a base para saber o papel do nó.

| Base | Para quê | Exemplos (livro, pt) | Aba padrão |
|---|---|---|---|
| `Agent` | quem age | Personagem, Facção, Criatura | Personagens |
| `Place` | onde | Lugar (continente → cômodo, com `dentro_de`) | Mundo |
| `Object` | coisas | Item | Mundo |
| `Ability` | poderes, técnicas | Habilidade (efeito, **custo**, condições, limites) | Mundo |
| `Concept` | ideias e regras | Cultura, Sistema (regras de magia, leis) | Mundo |
| `Event` | o que aconteceu | Evento (história do mundo, fora das unidades) | Mundo |
| `Unit` | **unidades narrativas** (só no narrativo) | Obra › Parte › Capítulo › Cena | Roteiro |
| `Device` | **recursos narrativos** (só no narrativo) | Gancho (prenúncio, arma de Tchekhov, mistério, promessa…) | Roteiro |
| `Media` | arquivos | ilustração, storyboard | Mundo |
| `Note` | apoio | Template, Beat (criados pelo motor) | Templates |

### 3.2 Atributos

Tipos: `int`, `decimal`, `bool`, `string_short` (≤255), `text_long`, `enum`, `story_time` (tempo da história, só no
narrativo), `json`, `node_ref` (vira aresta: ex. `origem` → Lugar), `list`, `composite` (objeto de um ObjectType), `media`.

**Papéis** (o motor acha o atributo pelo papel, seja qual for o nome ou idioma):

| Papel | Uso |
|---|---|
| `manuscript` | o texto (prosa/roteiro) da unidade — escrito por `unit_text_set`, versionado |
| `word_count` | palavras da unidade, calculado pelo servidor |
| `location` | onde a cena acontece (`node_ref` para Lugar) |
| `secret` | segredo do personagem (entra na lista de "não revelar") |

Atributos podem ter **criticidade padrão**: no perfil livro, o `custo` de uma Habilidade nasce `foundational` — vira lei.

### 3.3 Relações

Cada relação tem nome, base e **pares permitidos** (`"Personagem"`, `"@Agent"` = qualquer tipo com base Agent, `"*"`).
Se a LLM tentar um par não permitido, o erro já sugere quais relações servem.

Bases: `PARTICIPATION`, `SOCIAL`, `POSSESSION`, `SPATIAL`, `CAUSAL`, `KNOWLEDGE`, `ORIGIN`, `REFERENCE` e, no narrativo,
`STRUCTURE` (a relação de sistema CONTAINS: capítulo dentro da obra) e `SETUP_PAYOFF` (plantar/pagar).

### 3.4 Chaves de sistema

O motor encontra o que precisa pela **chave de sistema**, não pelo nome (por isso funciona em pt e en):

| Chave | Tipo/relação (livro, pt) | Usado em |
|---|---|---|
| `template` | Template | templates aplicados |
| `beat` | Beat | marcos do template |
| `thread` | Gancho | `open_threads` |
| `beat_of` | BEAT_DE | beat → template |
| `maps_to_beat` | MAPEIA_BEAT | unidade → beat que ela cumpre (cobertura) |
| `plants` | PLANTA | unidade → gancho/pista plantado |
| `pays` | PAGA | unidade → gancho/pista pago |
| `about` | SOBRE | gancho → do que ele trata |
| `appears_in` | APARECE_EM | elenco da unidade |
| `located_in` | ESTÁ_EM | onde algo está |

### 3.5 Criticidade, fatos e rigidez

- `criticality` em nó, atributo ou relação: `normal`, `critical`, `foundational`.
- Tudo que é critical/foundational vira um **Fato** espelhado, sempre presente no briefing e na validação.
- Fatos avulsos: `create_fact` com `about` (os nós de que trata).
- **Rigidez**: `law` (nunca quebrar), `norm` (via de regra; desvio exige motivo mostrado na narrativa), `flavor`
  (ambientação). Padrão: foundational → law, critical → norm.
- **Segredo**: fato com `revealAt` (id da unidade onde o leitor descobre). Antes dela o `writing_context` lista em
  `secrets` ("não revelar"); na unidade, em `revealedHere`.

### 3.6 Tempo narrativo

- Unidades (`Unit`) formam uma árvore: `parent` + `order`. O motor calcula a ordem total (`sortKey`) e a posição de cada
  unidade na obra (0 a 1), usada pela cobertura dos templates.
- Relações de estado levam âncoras: `fromUnit` (a partir de qual unidade vale) e `toUnit` (até qual, inclusive).
  - Ana POSSUI a Chave `fromUnit=cap3 toUnit=cap9` → no cap10 ela não tem mais.
  - Bruno SABE "Ana é a traidora" `fromUnit=cap7` → antes disso ele não pode agir sabendo.
- `state_at(entidade, unidade)` devolve o que vale (`active`), o que já acabou (`ended`) e o que ainda não começou
  (`notYet`) naquele ponto.

### 3.7 Elenco, local, ganchos e pistas

- **Elenco**: `unit_cast_set(unidade, [nós])` ou `cast` no create/update da unidade — cria os APARECE_EM de uma vez.
- **Local**: o atributo de papel `location` da cena.
- **Gancho**: nó Gancho (tipo: prenúncio, arma de Tchekhov, mistério, promessa, pista falsa, cliffhanger, subtrama;
  status: aberto, pago, abandonado). Plante com PLANTA (unidade → gancho), pague com PAGA, ligue ao assunto com SOBRE.
- **Pista**: qualquer nó plantado (um Item, um Lugar) vira pista e aparece em `open_threads`.

### 3.8 Templates, beats e cobertura

`template_apply` cria um nó Template e seus Beats (cada um com posição esperada, função dramática e critério de aceite).
Ligue cada unidade ao beat que ela cumpre (MAPEIA_BEAT). `milestone_coverage` mostra cada beat como **no lugar**, **fora
de posição**, **faltando** (a história já passou do ponto), **opcional ausente** ou **futuro**. A lista completa dos 10
templates e seus beats está em [referencia_schema.md](referencia_schema.md#templates-de-estrutura). Templates próprios:
`template_derive` (uma obra dissecada vira template, um beat por capítulo) e `template_upsert` (à mão).

### 3.9 Diretivas

Instruções curtas que a plataforma injeta nas respostas da LLM quando o gatilho bate (`session`, `context`, `write`,
`validate`, `always`), filtradas por tipo/base em jogo. Vêm do perfil (ex.: "manga: pense em painéis") e do projeto
(ex.: "Ana nunca mente diretamente; ela omite"). Editáveis por id; `enabled: false` desliga sem apagar.

### 3.10 Manuscrito

`unit_text_set` (substitui) / `unit_text_append` (capítulos longos em partes) gravam no atributo `manuscript` da unidade,
cada gravação vira um rascunho versionado (`unit_text_history`, `unit_text_diff`). `manuscript_export` compila a obra em
ordem (md, txt, html, docx, epub). `project_settings` define a amostra de estilo (ou outro projeto como referência).

### 3.11 O pacote de escrita (`writing_context`)

Para uma unidade, dentro de um orçamento de tokens: caminho na estrutura, unidades anteriores e o **final do texto
anterior**, a próxima unidade, as **partes** (cenas com objetivo/conflito/resultado), beats mapeados e esperados ali,
**entidades em jogo com o estado delas naquele ponto**, fatos e leis, **segredos**, o que é revelado ali, ganchos
abertos, amostra de estilo e diretivas. Se faltar algo importante no pacote, é porque falta registrar no grafo.

## 4. Roteiro: do zero à obra escrita

Cada etapa abaixo tem o objetivo, as perguntas a fazer ao autor, o que gravar e um "pronto quando". A LLM nunca inventa
fato sobre a obra: pergunta. Grave em lotes coerentes com `propose_changes` (use `dryRun: true` em lotes grandes) e feche
cada etapa com `session_checkpoint`.

### Etapa 0 — Conceito e projeto

- Perguntas: que tipo de obra (perfil)? público? tom? premissa em uma frase? tema? narrador? idioma?
- Gravar: `project_create(contextId: narrative, profile, locale)`; depois a **Obra** (base Unit, raiz) com `premissa`,
  `tema`, `publico`, `tom`, `narrador`.
- Pronto quando: `session_begin` mostra o perfil, o schema e a Obra.

### Etapa 1 — As leis do mundo

- Perguntas: o que é fisicamente/magicamente possível? que custo tem o poder? o que nunca acontece neste mundo?
- Gravar: **Sistema** (regras, exceções) e **Habilidade** (efeito, custo, condições, limites). Cada regra inviolável
  vira fato `law` + `foundational` com `about`; costumes viram `norm`; cor local vira `flavor`.

```json
[{"op":"create_node","ref":"$magia","type":"Sistema","name":"Síntese Ambiente","attributes":{"regras":"Absorve energia de objetos antigos."}},
 {"op":"create_fact","text":"A Síntese nunca cria energia: só transfere","rigidity":"law","criticality":"foundational","about":["$magia"]}]
```

- Pronto quando: `facts_list(minCriticality: foundational)` lista as leis e nenhuma contradiz outra.

### Etapa 2 — Geografia, culturas e facções

- Gravar: **Lugar** em hierarquia (`dentro_de`), CONECTA_A entre lugares, **Cultura** (costumes, valores, aparência
  típica, crenças) com CULTURA_DE → Lugar/Facção, **Facção** (ideologia, líder, alcance, recursos).
- Pronto quando: `graph_focus` num lugar mostra quem vive, que cultura e que facção agem ali.

### Etapa 3 — Personagens

- Perguntas por personagem: papel, motivação, objetivo, medo, segredo, voz, arco, origem.
- Gravar: **Personagem** com esses atributos (`segredo` tem papel `secret`), relações sociais (FAMÍLIA_DE, ALIADO_DE,
  INIMIGO_DE, MEMBRO_DE) e de origem. Se um vínculo muda ao longo da história, use âncoras (`fromUnit`/`toUnit`) quando a
  estrutura existir (Etapa 4).
- Segredos do leitor: fato com `revealAt` apontando a unidade da revelação.
- Pronto quando: cada protagonista tem motivação, medo e arco; `graph_path` explica como dois personagens se ligam.

### Etapa 4 — Estrutura

- Gravar a árvore: Obra › Parte › Capítulo › Cena (`parent`, `order`); para cenas, `objetivo`, `conflito`, `resultado`
  (sim / não / sim, mas / não, e), `pov`, `local`.
- Aplicar um template (`template_apply` com `plannedUnits` = total previsto) e mapear unidades → beats (MAPEIA_BEAT).
- Pronto quando: `story_outline` mostra a árvore na ordem certa e `milestone_coverage` não tem beat obrigatório "faltando".

### Etapa 5 — Promessas: ganchos, pistas e segredos

- Gravar: **Gancho** (tipo, status aberto, pagamento planejado) + SOBRE (do que trata) + PLANTA (onde é plantado);
  pistas = nós plantados; segredos = fatos com `revealAt`.
- Pronto quando: `open_threads` lista cada promessa com onde foi plantada e há quantas unidades; nenhum gancho está
  "pago" sem PAGA ou com PAGA e status aberto (o motor avisa).

### Etapa 6 — Escrever uma unidade (o ciclo)

1. `writing_context(unidade)` — leia o pacote inteiro; respeite leis, segredos e estado.
2. Escreva e grave: `unit_text_set` (ou `unit_text_append` em partes).
3. `unit_validate` — mostra quem é citado e não está no elenco, quem está no elenco e não aparece, nomes que não estão no
   grafo, personagens mortos citados, e a lista de regras/segredos/estado a conferir.
4. Registre: elenco (`unit_cast_set`), mudanças de estado com âncoras (posse, conhecimento, relações), ganchos plantados
   ou pagos, novos personagens/lugares que valem guardar, resumo da unidade.
5. `session_checkpoint`.

### Etapa 7 — Revisão

- Cobertura (`milestone_coverage`), promessas (`open_threads`), leis (`facts_list`), continuidade por personagem
  (`state_at` em pontos-chave), diff entre rascunhos (`unit_text_diff`), exportar (`manuscript_export`).

## 5. Checklist de qualidade do grafo (o "check")

| Verificação | Como | Saudável quando |
|---|---|---|
| Leis do mundo | `facts_list(minCriticality: foundational)` | sem contradição; cada lei tem `about` |
| Estrutura | `story_outline` | ordem correta; toda cena tem pai |
| Cobertura | `milestone_coverage` | nenhum beat obrigatório "faltando" |
| Promessas | `open_threads(includeClosed: true)` | sem aviso; gancho antigo ainda aberto é decisão consciente |
| Elenco | `unit_validate` por unidade escrita | "citados fora do elenco" vazio |
| Continuidade | `state_at(personagem, unidade)` | posse/conhecimento batem com o texto |
| Segredos | `writing_context(…, focus: [secrets])` | nada da lista aparece antes do `revealAt` |
| Paridade com o Harness Machine | `migrate_from_harness_machine.mjs --check-only` | "MIGRATION OK" |

## 6. Ferramentas (MCP) do contexto narrativo

| Grupo | Tools |
|---|---|
| Projeto e sessão | `context_list`, `project_list`, `project_create`, `project_settings`, `project_clone`, `project_localize`, `project_export`, `project_import`, `session_begin`, `session_resume`, `session_checkpoint` |
| Leitura | `schema_describe`, `node_search`, `node_get`, `edge_get`, `graph_focus`, `graph_path`, `state_at`, `story_outline`, `facts_list`, `directives_list` |
| Escrita | `propose_changes` (com `dryRun` / `mode: best_effort`), `changeset_status` |
| Manuscrito | `writing_context`, `unit_text_set`, `unit_text_append`, `unit_text_get`, `unit_text_history`, `unit_text_diff`, `unit_cast_set`, `unit_validate`, `manuscript_export` |
| Estrutura | `profile_list`, `profile_apply`, `template_list`, `template_apply`, `template_derive`, `template_upsert`, `template_delete`, `milestone_coverage`, `open_threads` |
| Prompts prontos | `start_project`, `build_world`, `write_unit` |

## 7. Formato das operações (exemplo completo, perfil livro em pt)

```json
[
  {"op":"create_node","ref":"$obra","type":"Obra","name":"A Obra","attributes":{"premissa":"Uma ladra precisa roubar a própria memória."}},
  {"op":"create_node","ref":"$c1","type":"Capítulo","name":"Capítulo 1","parent":"$obra","order":1},
  {"op":"create_node","ref":"$ana","type":"Personagem","name":"Ana","summary":"Ladra","attributes":{"papel":"protagonista","segredo":"É filha do duque"}},
  {"op":"create_node","ref":"$chave","type":"Item","name":"Chave de Obsidiana","attributes":{"regras_de_uso":"Só abre portas à meia-noite"},"attributeCriticality":{"regras_de_uso":"foundational"}},
  {"op":"create_node","ref":"$s1","type":"Cena","name":"1.1 O roubo","parent":"$c1","order":1,"cast":["$ana"],
   "attributes":{"objetivo":"Roubar a chave","conflito":"Guardas","resultado":"sim_mas"}},
  {"op":"create_edge","relType":"POSSUI","from":"$ana","to":"$chave","fromUnit":"$s1"},
  {"op":"create_node","ref":"$g","type":"Gancho","name":"Quem forjou a chave?","attributes":{"tipo":"misterio","status":"aberto"}},
  {"op":"create_edge","relType":"SOBRE","from":"$g","to":"$chave"},
  {"op":"create_edge","relType":"PLANTA","from":"$c1","to":"$g"},
  {"op":"create_fact","text":"Ana é filha do duque","criticality":"foundational","about":["$ana"],"revealAt":"n_cap12"},
  {"op":"upsert_directive","text":"Ana nunca mente diretamente; ela omite.","definition":{"when":["context"],"types":["Personagem"],"priority":5}}
]
```

Lote atômico (tudo ou nada) a não ser com `mode: best_effort`; `dryRun: true` lista todos os erros sem gravar;
`expectedVersion` obrigatório em update/delete; com `sessionId`, os `$apelidos` valem nos lotes seguintes da sessão.

## 8. Migração do Harness Machine

`scripts/migrate_from_harness_machine.mjs` lê o Neo4j do Harness Machine (mesmo modelo de dados), monta um arquivo
`obliterate-archive` (o formato de export/import do Obliterate) e, com `--import`, importa e confere a paridade.

```bash
node scripts/migrate_from_harness_machine.mjs
```

```bash
node scripts/migrate_from_harness_machine.mjs --import http://127.0.0.1:8180
```

- Sem `--import`, só gera o arquivo (padrão: `projetos-pessoais/Stories/migracao_obliterate/hm_to_obliterate_<data>.json`).
- `--projects p_a,p_b` escolhe os projetos (padrão: Bronze Age e Harry Potter); `--no-library` pula a biblioteca.
- `--check-only <arquivo>.import_result.json --import <url>` refaz só a checagem.
- O Harness Machine não é alterado. O import cria **projetos novos** (ids novos, referências remapeadas); se o nome já
  existir, ganha sufixo " (2)".

O que vai e como fica:

| Harness Machine | Obliterate |
|---|---|
| projeto (perfil, idioma, estilo) | projeto no contexto `narrative`, mesmo perfil e idioma |
| tipos, atributos, papéis, chaves de sistema, relações e pares | idênticos |
| nós (atributos, tags, criticidade, ordem, contagem de palavras) | idênticos; espaços `world`→Mundo (`knowledge`), `characters`→Personagens (`actors`) |
| estrutura (CONTAINS), âncoras `fromUnit`/`toUnit` | idênticas |
| fatos (manuais e espelhados, rigidez, `revealAt`) | idênticos |
| diretivas do projeto | idênticas (as do perfil vêm do próprio perfil) |
| templates aplicados e beats (nós) | idênticos — a cobertura dá o mesmo resultado |
| biblioteca de templates | projeto **"Biblioteca de estruturas"**: 10 Templates e 79 Beats, em pt com os textos em inglês nos atributos `*_en` |
| rascunhos de texto, changesets, auditoria, sessões | não vão (os projetos migrados não têm manuscrito; o histórico fica no Harness Machine) |

A checagem compara, projeto a projeto: schema e chaves de sistema, nós, arestas, arestas ancoradas no tempo, ordem das
unidades (outline), fatos e segredos, cobertura de cada beat, ganchos, diretivas, `state_at` em relações ancoradas e o
`writing_context` de unidades de amostra (por conteúdo; a ordem das linhas de estado pode variar porque o desempate usa
ids, que a importação renova).

Resultado na validação (stack de teste, 23/10/2026): Bronze Age — 16 tipos, 23 relações, 210 nós, 481 arestas, 73 fatos,
5 diretivas; Harry Potter e a Pedra Filosofal — 15 tipos, 20 relações, 335 nós, 754 arestas, 83 fatos; Biblioteca de
estruturas — 89 nós, 79 arestas. Todos idênticos ("MIGRATION OK").

## 9. Manutenção

- Este guia é escrito à mão; a referência (`referencia_schema.md` e `schema_narrativo.json`) é gerada:
  `node scripts/gen_narrative_schema.mjs <url de uma instância de teste>` (cria e apaga projetos temporários).
- Criado em 23/10/2026 com a volta do motor narrativo ao Obliterate (branch `feat/narrative-module`).
