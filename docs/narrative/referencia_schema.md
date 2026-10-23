# Referência do schema narrativo

> Gerado de uma instância do Obliterate em 2026-10-23 por `scripts/gen_narrative_schema.mjs`. Não edite à mão: rode o script de novo.
> Versão legível por máquina: [schema_narrativo.json](schema_narrativo.json).

## Perfis (presets) do contexto narrativo

| Perfil | Nome (pt / en) | Unidades | Templates sugeridos |
|---|---|---|---|
| `filme_serie` | Filme / série / Film / series | Obra › Temporada › Episódio › Sequência › Cena › Take | save_the_cat, tres_atos, jornada_do_heroi, story_circle |
| `jogo` | Jogo (videogame) / Video game | Jogo › Ato › Fase › Quest › Encontro › Diálogo | jornada_do_heroi, quest_jogo, tres_atos |
| `light_novel` | Light novel / Light novel | Série › Volume › Capítulo › Cena | kishotenketsu, jornada_do_heroi, capitulo_serial |
| `livro` | Livro / romance / Book / novel | Obra › Parte › Capítulo › Cena | tres_atos, jornada_do_heroi, save_the_cat, story_circle |
| `manga` | Mangá / HQ / Manga / comics | Série › Volume › Capítulo › Página › Painel | kishotenketsu, capitulo_serial, jornada_do_heroi |
| `publicidade` | Publicidade / Advertising | Campanha › Peça › Spot › Shot | spot_publicitario |
| `rpg_mesa` | RPG de mesa / Tabletop RPG | Campanha › Arco › Sessão › Encontro | one_shot_rpg, jornada_do_heroi, tres_atos |
| `visual_novel` | Visual novel / Visual novel | Obra › Rota › Cena › Escolha › Final | tres_atos, kishotenketsu |

## Perfil `filme_serie` — Filme / série

Roteiro audiovisual: episódios, sequências, cenas e takes, com locação, figurino, trilha e storyboard.

### Tipos de nó

#### Beat (en: Beat) · base `Note` · chave de sistema `beat`

Marco de um template, com posição esperada na obra.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `chave` | `key` | Chave | string_short |  |  |  |
| `posicao` | `position` | Posição esperada (0 a 1) | decimal |  |  |  |
| `funcao` | `function` | Função dramática | text_long |  |  |  |
| `criterio` | `criteria` | Critério de aceite | text_long |  |  |  |
| `recursos` | `devices` | Recursos esperados | list | itens: string_short |  |  |
| `obrigatorio` | `required` | Obrigatório | bool |  |  |  |

#### Cena (en: Scene) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `int_ext` | `int_ext` | INT/EXT | enum | opções: INT, EXT, INT_EXT |  |  |
| `periodo` | `time_of_day` | Período | enum | opções: dia, noite, amanhecer, entardecer |  |  |
| `locacao` | `location` | Locação | node_ref | ref: @Place | location |  |
| `objetivo` | `dramatic_goal` | Objetivo dramático | text_long |  |  |  |
| `roteiro` | `screenplay` | Texto do roteiro | text_long |  | manuscript |  |
| `paginas` | `pages` | Páginas (≈ minutos) | decimal |  |  |  |

#### Cultura (en: Culture) · base `Concept`

Costumes de um povo ou lugar. Pano de fundo, não lei — personagens podem fugir dela.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `costumes` | `customs` | Costumes | text_long |  |  |  |
| `valores` | `values` | Valores | text_long |  |  |  |
| `aparencia_tipica` | `typical_appearance` | Aparência típica | text_long |  |  |  |
| `lingua` | `language` | Língua / sotaque | string_short |  |  |  |
| `crencas` | `beliefs` | Crenças | text_long |  |  |  |

#### Episódio (en: Episode) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `logline` | `logline` | Logline do episódio | text_long |  |  |  |

#### Evento (en: Event) · base `Event`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `quando` | `when` | Quando (no mundo) | story_time |  |  |  |
| `consequencias` | `consequences` | Consequências | text_long |  |  |  |

#### Facção (en: Faction) · base `Agent`

Grupo organizado (guilda, família, empresa, culto, exército).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `ideologia` | `ideology` | Ideologia | text_long |  |  |  |
| `lider` | `leader` | Líder | node_ref | ref: Personagem |  |  |
| `alcance` | `reach` | Alcance | enum | opções: local, regional, global |  |  |
| `recursos` | `resources` | Recursos | text_long |  |  |  |

#### Figurino (en: Wardrobe) · base `Object`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `descricao` | `description` | Descrição | text_long |  |  |  |

#### Gancho (en: Hook) · base `Device` · chave de sistema `thread`

Setup que pede payoff — foreshadowing, arma de Chekhov, mistério, promessa ao leitor.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: foreshadowing, arma_de_chekhov, misterio, promessa, red_herring, cliffhanger, subtrama |  |  |
| `status` | `status` | Status | enum | opções: aberto, pago, abandonado |  |  |
| `payoff_planejado` | `planned_payoff` | Payoff planejado | text_long |  |  |  |

#### Habilidade (en: Ability) · base `Ability`

Poder, magia, técnica ou mecânica que um agente pode usar.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `efeito` | `effect` | Efeito | text_long |  |  |  |
| `custo` | `cost` | Custo | text_long |  |  | foundational |
| `condicoes` | `conditions` | Condições de uso | text_long |  |  | foundational |
| `limites` | `limits` | Limites | text_long |  |  | foundational |

#### Item (en: Item) · base `Object`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | string_short |  |  |  |
| `efeito` | `effect` | Efeito / função | text_long |  |  |  |
| `regras_de_uso` | `rules_of_use` | Regras de uso | text_long |  |  | critical |

#### Lugar (en: Place) · base `Place`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: continente, regiao, cidade, vila, construcao, comodo, natureza, ponto_de_interesse, outro |  |  |
| `dentro_de` | `inside` | Dentro de | node_ref | ref: @Place |  |  |
| `clima` | `climate` | Clima | string_short |  |  |  |
| `populacao` | `population` | População / densidade | string_short |  |  |  |
| `governo` | `government` | Governo / liderança | text_long |  |  |  |
| `descricao_sensorial` | `sensory_description` | Descrição sensorial | text_long |  |  |  |

#### Obra (en: Work) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `logline` | `logline` | Logline | text_long |  |  | critical |
| `formato` | `format` | Formato | enum | opções: longa, curta, serie, minisserie |  |  |
| `duracao_alvo_min` | `target_minutes` | Duração alvo (min) | int |  |  |  |

#### Personagem (en: Character) · base `Agent`

Qualquer pessoa ou criatura com vontade própria na história.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `papel` | `role` | Papel | enum | opções: protagonista, antagonista, coadjuvante, mentor, figurante |  |  |
| `status` | `status` | Status | enum | opções: vivo, morto, desaparecido, desconhecido |  |  |
| `idade` | `age` | Idade | int |  |  |  |
| `origem` | `origin` | Origem | node_ref | ref: @Place |  |  |
| `aparencia` | `appearance` | Aparência | text_long |  |  |  |
| `personalidade` | `personality` | Personalidade | text_long |  |  |  |
| `voz` | `voice` | Voz / jeito de falar | string_short |  |  |  |
| `motivacao` | `motivation` | Motivação | text_long |  |  |  |
| `objetivo` | `goal` | Objetivo | text_long |  |  |  |
| `medo` | `fear` | Medo / ferida | text_long |  |  |  |
| `segredo` | `secret` | Segredo | text_long |  | secret |  |
| `arco` | `arc` | Arco de transformação | text_long |  |  |  |

#### Sequência (en: Sequence) · base `Unit`

#### Sistema (en: System) · base `Concept`

Sistema de regras do mundo (magia, tecnologia, economia, política).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `regras` | `rules` | Regras | text_long |  |  | foundational |
| `excecoes` | `exceptions` | Exceções conhecidas | text_long |  |  |  |

#### Take (en: Take) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `enquadramento` | `framing` | Enquadramento | enum | opções: plano_geral, plano_conjunto, plano_medio, plano_americano, close, super_close, detalhe, plongee, contra_plongee, pov |  |  |
| `movimento` | `camera_move` | Movimento de câmera | enum | opções: fixo, pan, tilt, travelling, dolly, steadicam, drone, na_mao |  |  |
| `duracao_seg` | `duration_s` | Duração (s) | decimal |  |  |  |
| `acao` | `action` | Ação | text_long |  |  |  |
| `audio` | `audio` | Áudio / falas | text_long |  |  |  |
| `storyboard` | `storyboard` | Quadro do storyboard | media |  |  |  |

#### Template (en: Template) · base `Note` · chave de sistema `template`

Estrutura narrativa aplicada ao projeto (criado pelo harness).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `template_id` | `template_id` | Template de origem | string_short |  |  |  |
| `tipo_de_unidade` | `unit_type` | Unidade medida | string_short |  |  |  |
| `unidades_planejadas` | `planned_units` | Unidades planejadas | int |  |  |  |
| `escopo` | `scope` | Escopo (id da unidade cujas partes o template mede) | string_short |  |  |  |
| `fonte` | `source` | Fonte | string_short |  |  |  |

#### Temporada (en: Season) · base `Unit`

#### Trilha (en: Score) · base `Media`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `clima` | `mood` | Clima / emoção | string_short |  |  |  |
| `referencia` | `reference` | Referência | string_short |  |  |  |

### Relações

| Nome pt | Nome en | Base | Pares permitidos | Chave de sistema |
|---|---|---|---|---|
| ACONTECE_EM | HAPPENS_IN | PARTICIPATION | @Event → @Unit |  |
| ALIADO_DE | ALLY_OF | SOCIAL | @Agent → @Agent |  |
| APARECE_EM | APPEARS_IN | PARTICIPATION | * → @Unit | appears_in |
| BEAT_DE | BEAT_OF | REFERENCE | Beat → Template | beat_of |
| CAUSA | CAUSES | CAUSAL | * → @Event; @Event → * |  |
| CONECTA_A | CONNECTS_TO | SPATIAL | @Place → @Place |  |
| CULTURA_DE | CULTURE_OF | ORIGIN | Cultura → @Place; Cultura → Facção |  |
| DOMINA | MASTERS | POSSESSION | @Agent → @Ability; @Object → @Ability |  |
| ESTÁ_EM | LOCATED_IN | SPATIAL | * → @Place | located_in |
| FAMÍLIA_DE | FAMILY_OF | SOCIAL | @Agent → @Agent |  |
| INIMIGO_DE | ENEMY_OF | SOCIAL | @Agent → @Agent |  |
| MAPEIA_BEAT | MAPS_TO_BEAT | REFERENCE | @Unit → Beat | maps_to_beat |
| MEMBRO_DE | MEMBER_OF | SOCIAL | @Agent → Facção |  |
| PAGA | PAYS_OFF | SETUP_PAYOFF | @Unit → @Device; @Unit → * | pays |
| PLANTA | PLANTS | SETUP_PAYOFF | @Unit → @Device; @Unit → * | plants |
| POSSUI | OWNS | POSSESSION | @Agent → @Object; @Place → @Object |  |
| RELACIONA_COM | RELATES_TO | SOCIAL | @Agent → @Agent |  |
| SABE | KNOWS | KNOWLEDGE | @Agent → * |  |
| SEGUE | FOLLOWS | ORIGIN | @Agent → @Concept |  |
| SOBRE | ABOUT | REFERENCE | @Device → * | about |
| TOCA_EM | PLAYS_IN | PARTICIPATION | Trilha → @Unit |  |
| VESTE | WEARS | POSSESSION | @Agent → Figurino |  |

### Diretivas do perfil

- **write**: Antes de criar personagem, lugar ou item, procure com node_search se já existe.
- **context** (bases: Agent): Cultura e costumes são pano de fundo (norma), não lei. Se um personagem foge do costume da origem, a narrativa deve mostrar ou justificar.
- **context, write**: Ao terminar uma unidade, registre o que mudou (posse, conhecimento, local, relações) com âncoras fromUnit/toUnit, defina o elenco (cast) e marque ganchos/pistas com PLANTA/PAGA.
- **context** (tipos: Cena, Take): Escreva o que a câmera vê e o microfone ouve; pensamentos só aparecem por ação, fala ou imagem.
- **context** (tipos: Cena): Confira continuidade entre cenas consecutivas: figurino (VESTE), ferimentos, objetos em mãos e período do dia.

## Perfil `jogo` — Jogo (videogame)

Roteiro de jogo: atos, fases, quests, encontros e diálogos, com mecânicas ligadas à narrativa.

### Tipos de nó

#### Ato (en: Act) · base `Unit`

#### Beat (en: Beat) · base `Note` · chave de sistema `beat`

Marco de um template, com posição esperada na obra.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `chave` | `key` | Chave | string_short |  |  |  |
| `posicao` | `position` | Posição esperada (0 a 1) | decimal |  |  |  |
| `funcao` | `function` | Função dramática | text_long |  |  |  |
| `criterio` | `criteria` | Critério de aceite | text_long |  |  |  |
| `recursos` | `devices` | Recursos esperados | list | itens: string_short |  |  |
| `obrigatorio` | `required` | Obrigatório | bool |  |  |  |

#### Cultura (en: Culture) · base `Concept`

Costumes de um povo ou lugar. Pano de fundo, não lei — personagens podem fugir dela.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `costumes` | `customs` | Costumes | text_long |  |  |  |
| `valores` | `values` | Valores | text_long |  |  |  |
| `aparencia_tipica` | `typical_appearance` | Aparência típica | text_long |  |  |  |
| `lingua` | `language` | Língua / sotaque | string_short |  |  |  |
| `crencas` | `beliefs` | Crenças | text_long |  |  |  |

#### Diálogo (en: Dialogue) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `falas` | `lines` | Falas | text_long |  | manuscript |  |
| `condicao` | `condition` | Condição para aparecer | string_short |  |  |  |

#### Encontro (en: Encounter) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: combate, puzzle, social, exploracao, chefe |  |  |
| `dificuldade` | `difficulty` | Dificuldade | enum | opções: facil, media, dificil, chefe |  |  |

#### Evento (en: Event) · base `Event`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `quando` | `when` | Quando (no mundo) | story_time |  |  |  |
| `consequencias` | `consequences` | Consequências | text_long |  |  |  |

#### Facção (en: Faction) · base `Agent`

Grupo organizado (guilda, família, empresa, culto, exército).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `ideologia` | `ideology` | Ideologia | text_long |  |  |  |
| `lider` | `leader` | Líder | node_ref | ref: Personagem |  |  |
| `alcance` | `reach` | Alcance | enum | opções: local, regional, global |  |  |
| `recursos` | `resources` | Recursos | text_long |  |  |  |

#### Fase (en: Level) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `mapa` | `map` | Mapa | node_ref | ref: @Place | location |  |
| `mecanica_nova` | `new_mechanic` | Mecânica introduzida | node_ref | ref: Mecânica |  |  |

#### Gancho (en: Hook) · base `Device` · chave de sistema `thread`

Setup que pede payoff — foreshadowing, arma de Chekhov, mistério, promessa ao leitor.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: foreshadowing, arma_de_chekhov, misterio, promessa, red_herring, cliffhanger, subtrama |  |  |
| `status` | `status` | Status | enum | opções: aberto, pago, abandonado |  |  |
| `payoff_planejado` | `planned_payoff` | Payoff planejado | text_long |  |  |  |

#### Habilidade (en: Ability) · base `Ability`

Poder, magia, técnica ou mecânica que um agente pode usar.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `efeito` | `effect` | Efeito | text_long |  |  |  |
| `custo` | `cost` | Custo | text_long |  |  | foundational |
| `condicoes` | `conditions` | Condições de uso | text_long |  |  | foundational |
| `limites` | `limits` | Limites | text_long |  |  | foundational |

#### Inimigo (en: Enemy) · base `Agent`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `comportamento` | `behavior` | Comportamento | text_long |  |  |  |
| `fraqueza` | `weakness` | Fraqueza | string_short |  |  | critical |

#### Item (en: Item) · base `Object`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | string_short |  |  |  |
| `efeito` | `effect` | Efeito / função | text_long |  |  |  |
| `regras_de_uso` | `rules_of_use` | Regras de uso | text_long |  |  | critical |

#### Jogo (en: Game) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `premissa` | `premise` | Premissa | text_long |  |  | critical |
| `genero` | `genre` | Gênero | string_short |  |  |  |
| `loop_principal` | `core_loop` | Loop principal de gameplay | text_long |  |  |  |

#### Lugar (en: Place) · base `Place`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: continente, regiao, cidade, vila, construcao, comodo, natureza, ponto_de_interesse, outro |  |  |
| `dentro_de` | `inside` | Dentro de | node_ref | ref: @Place |  |  |
| `clima` | `climate` | Clima | string_short |  |  |  |
| `populacao` | `population` | População / densidade | string_short |  |  |  |
| `governo` | `government` | Governo / liderança | text_long |  |  |  |
| `descricao_sensorial` | `sensory_description` | Descrição sensorial | text_long |  |  |  |

#### Mecânica (en: Mechanic) · base `Ability`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `descricao` | `how_it_works` | Como funciona | text_long |  |  |  |
| `justificativa_narrativa` | `narrative_reason` | Justificativa narrativa | text_long |  |  |  |

#### Personagem (en: Character) · base `Agent`

Qualquer pessoa ou criatura com vontade própria na história.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `papel` | `role` | Papel | enum | opções: protagonista, antagonista, coadjuvante, mentor, figurante |  |  |
| `status` | `status` | Status | enum | opções: vivo, morto, desaparecido, desconhecido |  |  |
| `idade` | `age` | Idade | int |  |  |  |
| `origem` | `origin` | Origem | node_ref | ref: @Place |  |  |
| `aparencia` | `appearance` | Aparência | text_long |  |  |  |
| `personalidade` | `personality` | Personalidade | text_long |  |  |  |
| `voz` | `voice` | Voz / jeito de falar | string_short |  |  |  |
| `motivacao` | `motivation` | Motivação | text_long |  |  |  |
| `objetivo` | `goal` | Objetivo | text_long |  |  |  |
| `medo` | `fear` | Medo / ferida | text_long |  |  |  |
| `segredo` | `secret` | Segredo | text_long |  | secret |  |
| `arco` | `arc` | Arco de transformação | text_long |  |  |  |

#### Quest (en: Quest) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: principal, secundaria, opcional, evento |  |  |
| `objetivo` | `objective` | Objetivo | text_long |  |  |  |
| `recompensa` | `reward` | Recompensa | text_long |  |  |  |
| `quest_giver` | `quest_giver` | Quem dá a quest | node_ref | ref: @Agent |  |  |

#### Sistema (en: System) · base `Concept`

Sistema de regras do mundo (magia, tecnologia, economia, política).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `regras` | `rules` | Regras | text_long |  |  | foundational |
| `excecoes` | `exceptions` | Exceções conhecidas | text_long |  |  |  |

#### Template (en: Template) · base `Note` · chave de sistema `template`

Estrutura narrativa aplicada ao projeto (criado pelo harness).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `template_id` | `template_id` | Template de origem | string_short |  |  |  |
| `tipo_de_unidade` | `unit_type` | Unidade medida | string_short |  |  |  |
| `unidades_planejadas` | `planned_units` | Unidades planejadas | int |  |  |  |
| `escopo` | `scope` | Escopo (id da unidade cujas partes o template mede) | string_short |  |  |  |
| `fonte` | `source` | Fonte | string_short |  |  |  |

### Relações

| Nome pt | Nome en | Base | Pares permitidos | Chave de sistema |
|---|---|---|---|---|
| ACONTECE_EM | HAPPENS_IN | PARTICIPATION | @Event → @Unit |  |
| ALIADO_DE | ALLY_OF | SOCIAL | @Agent → @Agent |  |
| APARECE_EM | APPEARS_IN | PARTICIPATION | * → @Unit | appears_in |
| BEAT_DE | BEAT_OF | REFERENCE | Beat → Template | beat_of |
| CAUSA | CAUSES | CAUSAL | * → @Event; @Event → * |  |
| CONECTA_A | CONNECTS_TO | SPATIAL | @Place → @Place |  |
| CULTURA_DE | CULTURE_OF | ORIGIN | Cultura → @Place; Cultura → Facção |  |
| DOMINA | MASTERS | POSSESSION | @Agent → @Ability; @Object → @Ability |  |
| ESTÁ_EM | LOCATED_IN | SPATIAL | * → @Place | located_in |
| FAMÍLIA_DE | FAMILY_OF | SOCIAL | @Agent → @Agent |  |
| HABITA | INHABITS | SPATIAL | Inimigo → @Place |  |
| INIMIGO_DE | ENEMY_OF | SOCIAL | @Agent → @Agent |  |
| LEVA_A | LEADS_TO | CAUSAL | @Unit → @Unit |  |
| MAPEIA_BEAT | MAPS_TO_BEAT | REFERENCE | @Unit → Beat | maps_to_beat |
| MEMBRO_DE | MEMBER_OF | SOCIAL | @Agent → Facção |  |
| PAGA | PAYS_OFF | SETUP_PAYOFF | @Unit → @Device; @Unit → * | pays |
| PLANTA | PLANTS | SETUP_PAYOFF | @Unit → @Device; @Unit → * | plants |
| POSSUI | OWNS | POSSESSION | @Agent → @Object; @Place → @Object |  |
| RELACIONA_COM | RELATES_TO | SOCIAL | @Agent → @Agent |  |
| SABE | KNOWS | KNOWLEDGE | @Agent → * |  |
| SEGUE | FOLLOWS | ORIGIN | @Agent → @Concept |  |
| SOBRE | ABOUT | REFERENCE | @Device → * | about |

### Diretivas do perfil

- **write**: Antes de criar personagem, lugar ou item, procure com node_search se já existe.
- **context** (bases: Agent): Cultura e costumes são pano de fundo (norma), não lei. Se um personagem foge do costume da origem, a narrativa deve mostrar ou justificar.
- **context, write**: Ao terminar uma unidade, registre o que mudou (posse, conhecimento, local, relações) com âncoras fromUnit/toUnit, defina o elenco (cast) e marque ganchos/pistas com PLANTA/PAGA.
- **context, write** (bases: Ability): Mecânicas novas devem ter justificativa no mundo (ludonarrativa): se o jogador ganha um poder, a história explica de onde ele veio.
- **context** (tipos: Quest): Toda quest precisa de objetivo claro para o jogador em uma frase e de recompensa coerente com o mundo.

## Perfil `light_novel` — Light novel

Volumes seriados, capítulos curtos com gancho no fim, ilustrações e tropos do gênero.

### Tipos de nó

#### Beat (en: Beat) · base `Note` · chave de sistema `beat`

Marco de um template, com posição esperada na obra.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `chave` | `key` | Chave | string_short |  |  |  |
| `posicao` | `position` | Posição esperada (0 a 1) | decimal |  |  |  |
| `funcao` | `function` | Função dramática | text_long |  |  |  |
| `criterio` | `criteria` | Critério de aceite | text_long |  |  |  |
| `recursos` | `devices` | Recursos esperados | list | itens: string_short |  |  |
| `obrigatorio` | `required` | Obrigatório | bool |  |  |  |

#### Capítulo (en: Chapter) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `status` | `status` | Status | enum | opções: ideia, rascunho, revisao, pronto |  |  |
| `gancho_final` | `final_hook` | Gancho final | text_long |  |  |  |
| `texto` | `text` | Texto | text_long |  | manuscript |  |
| `palavras` | `words` | Palavras | int |  | word_count |  |

#### Cena (en: Scene) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `objetivo` | `goal` | Objetivo da cena | text_long |  |  |  |
| `local` | `location` | Local | node_ref | ref: @Place | location |  |
| `texto` | `text` | Texto | text_long |  | manuscript |  |

#### Cultura (en: Culture) · base `Concept`

Costumes de um povo ou lugar. Pano de fundo, não lei — personagens podem fugir dela.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `costumes` | `customs` | Costumes | text_long |  |  |  |
| `valores` | `values` | Valores | text_long |  |  |  |
| `aparencia_tipica` | `typical_appearance` | Aparência típica | text_long |  |  |  |
| `lingua` | `language` | Língua / sotaque | string_short |  |  |  |
| `crencas` | `beliefs` | Crenças | text_long |  |  |  |

#### Evento (en: Event) · base `Event`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `quando` | `when` | Quando (no mundo) | story_time |  |  |  |
| `consequencias` | `consequences` | Consequências | text_long |  |  |  |

#### Facção (en: Faction) · base `Agent`

Grupo organizado (guilda, família, empresa, culto, exército).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `ideologia` | `ideology` | Ideologia | text_long |  |  |  |
| `lider` | `leader` | Líder | node_ref | ref: Personagem |  |  |
| `alcance` | `reach` | Alcance | enum | opções: local, regional, global |  |  |
| `recursos` | `resources` | Recursos | text_long |  |  |  |

#### Gancho (en: Hook) · base `Device` · chave de sistema `thread`

Setup que pede payoff — foreshadowing, arma de Chekhov, mistério, promessa ao leitor.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: foreshadowing, arma_de_chekhov, misterio, promessa, red_herring, cliffhanger, subtrama |  |  |
| `status` | `status` | Status | enum | opções: aberto, pago, abandonado |  |  |
| `payoff_planejado` | `planned_payoff` | Payoff planejado | text_long |  |  |  |

#### Habilidade (en: Ability) · base `Ability`

Poder, magia, técnica ou mecânica que um agente pode usar.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `efeito` | `effect` | Efeito | text_long |  |  |  |
| `custo` | `cost` | Custo | text_long |  |  | foundational |
| `condicoes` | `conditions` | Condições de uso | text_long |  |  | foundational |
| `limites` | `limits` | Limites | text_long |  |  | foundational |

#### Ilustração (en: Illustration) · base `Media`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `descricao` | `description` | O que mostra | text_long |  |  |  |
| `arquivo` | `file` | Arquivo | media |  |  |  |

#### Item (en: Item) · base `Object`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | string_short |  |  |  |
| `efeito` | `effect` | Efeito / função | text_long |  |  |  |
| `regras_de_uso` | `rules_of_use` | Regras de uso | text_long |  |  | critical |

#### Lugar (en: Place) · base `Place`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: continente, regiao, cidade, vila, construcao, comodo, natureza, ponto_de_interesse, outro |  |  |
| `dentro_de` | `inside` | Dentro de | node_ref | ref: @Place |  |  |
| `clima` | `climate` | Clima | string_short |  |  |  |
| `populacao` | `population` | População / densidade | string_short |  |  |  |
| `governo` | `government` | Governo / liderança | text_long |  |  |  |
| `descricao_sensorial` | `sensory_description` | Descrição sensorial | text_long |  |  |  |

#### Personagem (en: Character) · base `Agent`

Qualquer pessoa ou criatura com vontade própria na história.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `papel` | `role` | Papel | enum | opções: protagonista, antagonista, coadjuvante, mentor, figurante |  |  |
| `status` | `status` | Status | enum | opções: vivo, morto, desaparecido, desconhecido |  |  |
| `idade` | `age` | Idade | int |  |  |  |
| `origem` | `origin` | Origem | node_ref | ref: @Place |  |  |
| `aparencia` | `appearance` | Aparência | text_long |  |  |  |
| `personalidade` | `personality` | Personalidade | text_long |  |  |  |
| `voz` | `voice` | Voz / jeito de falar | string_short |  |  |  |
| `motivacao` | `motivation` | Motivação | text_long |  |  |  |
| `objetivo` | `goal` | Objetivo | text_long |  |  |  |
| `medo` | `fear` | Medo / ferida | text_long |  |  |  |
| `segredo` | `secret` | Segredo | text_long |  | secret |  |
| `arco` | `arc` | Arco de transformação | text_long |  |  |  |

#### Sistema (en: System) · base `Concept`

Sistema de regras do mundo (magia, tecnologia, economia, política).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `regras` | `rules` | Regras | text_long |  |  | foundational |
| `excecoes` | `exceptions` | Exceções conhecidas | text_long |  |  |  |

#### Série (en: Series) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `premissa` | `premise` | Premissa | text_long |  |  | critical |
| `generos` | `genres` | Gêneros e tropos | list | itens: string_short |  |  |
| `publico` | `audience` | Público | enum | opções: shounen, shoujo, seinen, josei, geral |  |  |

#### Template (en: Template) · base `Note` · chave de sistema `template`

Estrutura narrativa aplicada ao projeto (criado pelo harness).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `template_id` | `template_id` | Template de origem | string_short |  |  |  |
| `tipo_de_unidade` | `unit_type` | Unidade medida | string_short |  |  |  |
| `unidades_planejadas` | `planned_units` | Unidades planejadas | int |  |  |  |
| `escopo` | `scope` | Escopo (id da unidade cujas partes o template mede) | string_short |  |  |  |
| `fonte` | `source` | Fonte | string_short |  |  |  |

#### Volume (en: Volume) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `arco` | `arc` | Arco do volume | text_long |  |  |  |

### Relações

| Nome pt | Nome en | Base | Pares permitidos | Chave de sistema |
|---|---|---|---|---|
| ACONTECE_EM | HAPPENS_IN | PARTICIPATION | @Event → @Unit |  |
| ALIADO_DE | ALLY_OF | SOCIAL | @Agent → @Agent |  |
| APARECE_EM | APPEARS_IN | PARTICIPATION | * → @Unit | appears_in |
| BEAT_DE | BEAT_OF | REFERENCE | Beat → Template | beat_of |
| CAUSA | CAUSES | CAUSAL | * → @Event; @Event → * |  |
| CONECTA_A | CONNECTS_TO | SPATIAL | @Place → @Place |  |
| CULTURA_DE | CULTURE_OF | ORIGIN | Cultura → @Place; Cultura → Facção |  |
| DOMINA | MASTERS | POSSESSION | @Agent → @Ability; @Object → @Ability |  |
| ESTÁ_EM | LOCATED_IN | SPATIAL | * → @Place | located_in |
| FAMÍLIA_DE | FAMILY_OF | SOCIAL | @Agent → @Agent |  |
| ILUSTRA | ILLUSTRATES | REFERENCE | Ilustração → @Unit; Ilustração → @Agent |  |
| INIMIGO_DE | ENEMY_OF | SOCIAL | @Agent → @Agent |  |
| MAPEIA_BEAT | MAPS_TO_BEAT | REFERENCE | @Unit → Beat | maps_to_beat |
| MEMBRO_DE | MEMBER_OF | SOCIAL | @Agent → Facção |  |
| PAGA | PAYS_OFF | SETUP_PAYOFF | @Unit → @Device; @Unit → * | pays |
| PLANTA | PLANTS | SETUP_PAYOFF | @Unit → @Device; @Unit → * | plants |
| POSSUI | OWNS | POSSESSION | @Agent → @Object; @Place → @Object |  |
| RELACIONA_COM | RELATES_TO | SOCIAL | @Agent → @Agent |  |
| SABE | KNOWS | KNOWLEDGE | @Agent → * |  |
| SEGUE | FOLLOWS | ORIGIN | @Agent → @Concept |  |
| SOBRE | ABOUT | REFERENCE | @Device → * | about |

### Diretivas do perfil

- **write**: Antes de criar personagem, lugar ou item, procure com node_search se já existe.
- **context** (bases: Agent): Cultura e costumes são pano de fundo (norma), não lei. Se um personagem foge do costume da origem, a narrativa deve mostrar ou justificar.
- **context, write**: Ao terminar uma unidade, registre o que mudou (posse, conhecimento, local, relações) com âncoras fromUnit/toUnit, defina o elenco (cast) e marque ganchos/pistas com PLANTA/PAGA.
- **context** (tipos: Capítulo): Termine cada capítulo com um gancho (pergunta aberta, revelação ou virada) que puxe o próximo.
- **context**: Use os tropos do gênero de forma consciente; registre no grafo quando um tropo for subvertido.

## Perfil `livro` — Livro / romance

Prosa longa: obra, partes, capítulos e cenas, com foco em voz narrativa e ponto de vista.

### Tipos de nó

#### Beat (en: Beat) · base `Note` · chave de sistema `beat`

Marco de um template, com posição esperada na obra.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `chave` | `key` | Chave | string_short |  |  |  |
| `posicao` | `position` | Posição esperada (0 a 1) | decimal |  |  |  |
| `funcao` | `function` | Função dramática | text_long |  |  |  |
| `criterio` | `criteria` | Critério de aceite | text_long |  |  |  |
| `recursos` | `devices` | Recursos esperados | list | itens: string_short |  |  |
| `obrigatorio` | `required` | Obrigatório | bool |  |  |  |

#### Capítulo (en: Chapter) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `status` | `status` | Status | enum | opções: ideia, rascunho, revisao, pronto |  |  |
| `pov` | `pov` | Ponto de vista | node_ref | ref: Personagem |  |  |
| `objetivo` | `goal` | Objetivo do capítulo | text_long |  |  |  |
| `texto` | `text` | Texto | text_long |  | manuscript |  |
| `palavras` | `words` | Palavras | int |  | word_count |  |

#### Cena (en: Scene) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `objetivo` | `goal` | Objetivo da cena | text_long |  |  |  |
| `conflito` | `conflict` | Conflito | text_long |  |  |  |
| `resultado` | `outcome` | Resultado (sim, não, sim-mas, não-e) | enum | opções: sim, nao, sim_mas, nao_e |  |  |
| `pov` | `pov` | Ponto de vista | node_ref | ref: Personagem |  |  |
| `local` | `location` | Local | node_ref | ref: @Place | location |  |
| `texto` | `text` | Texto | text_long |  | manuscript |  |

#### Cultura (en: Culture) · base `Concept`

Costumes de um povo ou lugar. Pano de fundo, não lei — personagens podem fugir dela.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `costumes` | `customs` | Costumes | text_long |  |  |  |
| `valores` | `values` | Valores | text_long |  |  |  |
| `aparencia_tipica` | `typical_appearance` | Aparência típica | text_long |  |  |  |
| `lingua` | `language` | Língua / sotaque | string_short |  |  |  |
| `crencas` | `beliefs` | Crenças | text_long |  |  |  |

#### Evento (en: Event) · base `Event`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `quando` | `when` | Quando (no mundo) | story_time |  |  |  |
| `consequencias` | `consequences` | Consequências | text_long |  |  |  |

#### Facção (en: Faction) · base `Agent`

Grupo organizado (guilda, família, empresa, culto, exército).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `ideologia` | `ideology` | Ideologia | text_long |  |  |  |
| `lider` | `leader` | Líder | node_ref | ref: Personagem |  |  |
| `alcance` | `reach` | Alcance | enum | opções: local, regional, global |  |  |
| `recursos` | `resources` | Recursos | text_long |  |  |  |

#### Gancho (en: Hook) · base `Device` · chave de sistema `thread`

Setup que pede payoff — foreshadowing, arma de Chekhov, mistério, promessa ao leitor.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: foreshadowing, arma_de_chekhov, misterio, promessa, red_herring, cliffhanger, subtrama |  |  |
| `status` | `status` | Status | enum | opções: aberto, pago, abandonado |  |  |
| `payoff_planejado` | `planned_payoff` | Payoff planejado | text_long |  |  |  |

#### Habilidade (en: Ability) · base `Ability`

Poder, magia, técnica ou mecânica que um agente pode usar.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `efeito` | `effect` | Efeito | text_long |  |  |  |
| `custo` | `cost` | Custo | text_long |  |  | foundational |
| `condicoes` | `conditions` | Condições de uso | text_long |  |  | foundational |
| `limites` | `limits` | Limites | text_long |  |  | foundational |

#### Item (en: Item) · base `Object`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | string_short |  |  |  |
| `efeito` | `effect` | Efeito / função | text_long |  |  |  |
| `regras_de_uso` | `rules_of_use` | Regras de uso | text_long |  |  | critical |

#### Lugar (en: Place) · base `Place`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: continente, regiao, cidade, vila, construcao, comodo, natureza, ponto_de_interesse, outro |  |  |
| `dentro_de` | `inside` | Dentro de | node_ref | ref: @Place |  |  |
| `clima` | `climate` | Clima | string_short |  |  |  |
| `populacao` | `population` | População / densidade | string_short |  |  |  |
| `governo` | `government` | Governo / liderança | text_long |  |  |  |
| `descricao_sensorial` | `sensory_description` | Descrição sensorial | text_long |  |  |  |

#### Obra (en: Work) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `premissa` | `premise` | Premissa | text_long |  |  | critical |
| `tema` | `theme` | Tema | text_long |  |  |  |
| `publico` | `audience` | Público | string_short |  |  |  |
| `tom` | `tone` | Tom | string_short |  |  |  |
| `narrador` | `narrator` | Narrador | enum | opções: primeira_pessoa, terceira_limitada, terceira_onisciente, segunda_pessoa, multiplo |  |  |

#### Parte (en: Part) · base `Unit`

#### Personagem (en: Character) · base `Agent`

Qualquer pessoa ou criatura com vontade própria na história.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `papel` | `role` | Papel | enum | opções: protagonista, antagonista, coadjuvante, mentor, figurante |  |  |
| `status` | `status` | Status | enum | opções: vivo, morto, desaparecido, desconhecido |  |  |
| `idade` | `age` | Idade | int |  |  |  |
| `origem` | `origin` | Origem | node_ref | ref: @Place |  |  |
| `aparencia` | `appearance` | Aparência | text_long |  |  |  |
| `personalidade` | `personality` | Personalidade | text_long |  |  |  |
| `voz` | `voice` | Voz / jeito de falar | string_short |  |  |  |
| `motivacao` | `motivation` | Motivação | text_long |  |  |  |
| `objetivo` | `goal` | Objetivo | text_long |  |  |  |
| `medo` | `fear` | Medo / ferida | text_long |  |  |  |
| `segredo` | `secret` | Segredo | text_long |  | secret |  |
| `arco` | `arc` | Arco de transformação | text_long |  |  |  |

#### Sistema (en: System) · base `Concept`

Sistema de regras do mundo (magia, tecnologia, economia, política).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `regras` | `rules` | Regras | text_long |  |  | foundational |
| `excecoes` | `exceptions` | Exceções conhecidas | text_long |  |  |  |

#### Template (en: Template) · base `Note` · chave de sistema `template`

Estrutura narrativa aplicada ao projeto (criado pelo harness).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `template_id` | `template_id` | Template de origem | string_short |  |  |  |
| `tipo_de_unidade` | `unit_type` | Unidade medida | string_short |  |  |  |
| `unidades_planejadas` | `planned_units` | Unidades planejadas | int |  |  |  |
| `escopo` | `scope` | Escopo (id da unidade cujas partes o template mede) | string_short |  |  |  |
| `fonte` | `source` | Fonte | string_short |  |  |  |

### Relações

| Nome pt | Nome en | Base | Pares permitidos | Chave de sistema |
|---|---|---|---|---|
| ACONTECE_EM | HAPPENS_IN | PARTICIPATION | @Event → @Unit |  |
| ALIADO_DE | ALLY_OF | SOCIAL | @Agent → @Agent |  |
| APARECE_EM | APPEARS_IN | PARTICIPATION | * → @Unit | appears_in |
| BEAT_DE | BEAT_OF | REFERENCE | Beat → Template | beat_of |
| CAUSA | CAUSES | CAUSAL | * → @Event; @Event → * |  |
| CONECTA_A | CONNECTS_TO | SPATIAL | @Place → @Place |  |
| CULTURA_DE | CULTURE_OF | ORIGIN | Cultura → @Place; Cultura → Facção |  |
| DOMINA | MASTERS | POSSESSION | @Agent → @Ability; @Object → @Ability |  |
| ESTÁ_EM | LOCATED_IN | SPATIAL | * → @Place | located_in |
| FAMÍLIA_DE | FAMILY_OF | SOCIAL | @Agent → @Agent |  |
| INIMIGO_DE | ENEMY_OF | SOCIAL | @Agent → @Agent |  |
| MAPEIA_BEAT | MAPS_TO_BEAT | REFERENCE | @Unit → Beat | maps_to_beat |
| MEMBRO_DE | MEMBER_OF | SOCIAL | @Agent → Facção |  |
| PAGA | PAYS_OFF | SETUP_PAYOFF | @Unit → @Device; @Unit → * | pays |
| PLANTA | PLANTS | SETUP_PAYOFF | @Unit → @Device; @Unit → * | plants |
| POSSUI | OWNS | POSSESSION | @Agent → @Object; @Place → @Object |  |
| RELACIONA_COM | RELATES_TO | SOCIAL | @Agent → @Agent |  |
| SABE | KNOWS | KNOWLEDGE | @Agent → * |  |
| SEGUE | FOLLOWS | ORIGIN | @Agent → @Concept |  |
| SOBRE | ABOUT | REFERENCE | @Device → * | about |

### Diretivas do perfil

- **write**: Antes de criar personagem, lugar ou item, procure com node_search se já existe.
- **context** (bases: Agent): Cultura e costumes são pano de fundo (norma), não lei. Se um personagem foge do costume da origem, a narrativa deve mostrar ou justificar.
- **context, write**: Ao terminar uma unidade, registre o que mudou (posse, conhecimento, local, relações) com âncoras fromUnit/toUnit, defina o elenco (cast) e marque ganchos/pistas com PLANTA/PAGA.
- **context**: Respeite o ponto de vista da unidade (atributo pov). O narrador não sabe o que o personagem-POV não sabe.
- **context** (tipos: Cena): Toda cena precisa de objetivo, conflito e resultado; se faltar um dos três, a cena provavelmente é resumo.

## Perfil `manga` — Mangá / HQ

Roteiro visual: capítulos, páginas e painéis, com enquadramento, falas e ritmo de virada de página.

### Tipos de nó

#### Beat (en: Beat) · base `Note` · chave de sistema `beat`

Marco de um template, com posição esperada na obra.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `chave` | `key` | Chave | string_short |  |  |  |
| `posicao` | `position` | Posição esperada (0 a 1) | decimal |  |  |  |
| `funcao` | `function` | Função dramática | text_long |  |  |  |
| `criterio` | `criteria` | Critério de aceite | text_long |  |  |  |
| `recursos` | `devices` | Recursos esperados | list | itens: string_short |  |  |
| `obrigatorio` | `required` | Obrigatório | bool |  |  |  |

#### Capítulo (en: Chapter) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `status` | `status` | Status | enum | opções: ideia, roteiro, storyboard, arte, pronto |  |  |
| `paginas_previstas` | `planned_pages` | Páginas previstas | int |  |  |  |

#### Cultura (en: Culture) · base `Concept`

Costumes de um povo ou lugar. Pano de fundo, não lei — personagens podem fugir dela.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `costumes` | `customs` | Costumes | text_long |  |  |  |
| `valores` | `values` | Valores | text_long |  |  |  |
| `aparencia_tipica` | `typical_appearance` | Aparência típica | text_long |  |  |  |
| `lingua` | `language` | Língua / sotaque | string_short |  |  |  |
| `crencas` | `beliefs` | Crenças | text_long |  |  |  |

#### Evento (en: Event) · base `Event`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `quando` | `when` | Quando (no mundo) | story_time |  |  |  |
| `consequencias` | `consequences` | Consequências | text_long |  |  |  |

#### Facção (en: Faction) · base `Agent`

Grupo organizado (guilda, família, empresa, culto, exército).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `ideologia` | `ideology` | Ideologia | text_long |  |  |  |
| `lider` | `leader` | Líder | node_ref | ref: Personagem |  |  |
| `alcance` | `reach` | Alcance | enum | opções: local, regional, global |  |  |
| `recursos` | `resources` | Recursos | text_long |  |  |  |

#### Gancho (en: Hook) · base `Device` · chave de sistema `thread`

Setup que pede payoff — foreshadowing, arma de Chekhov, mistério, promessa ao leitor.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: foreshadowing, arma_de_chekhov, misterio, promessa, red_herring, cliffhanger, subtrama |  |  |
| `status` | `status` | Status | enum | opções: aberto, pago, abandonado |  |  |
| `payoff_planejado` | `planned_payoff` | Payoff planejado | text_long |  |  |  |

#### Habilidade (en: Ability) · base `Ability`

Poder, magia, técnica ou mecânica que um agente pode usar.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `efeito` | `effect` | Efeito | text_long |  |  |  |
| `custo` | `cost` | Custo | text_long |  |  | foundational |
| `condicoes` | `conditions` | Condições de uso | text_long |  |  | foundational |
| `limites` | `limits` | Limites | text_long |  |  | foundational |

#### Item (en: Item) · base `Object`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | string_short |  |  |  |
| `efeito` | `effect` | Efeito / função | text_long |  |  |  |
| `regras_de_uso` | `rules_of_use` | Regras de uso | text_long |  |  | critical |

#### Lugar (en: Place) · base `Place`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: continente, regiao, cidade, vila, construcao, comodo, natureza, ponto_de_interesse, outro |  |  |
| `dentro_de` | `inside` | Dentro de | node_ref | ref: @Place |  |  |
| `clima` | `climate` | Clima | string_short |  |  |  |
| `populacao` | `population` | População / densidade | string_short |  |  |  |
| `governo` | `government` | Governo / liderança | text_long |  |  |  |
| `descricao_sensorial` | `sensory_description` | Descrição sensorial | text_long |  |  |  |

#### Painel (en: Panel) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `enquadramento` | `framing` | Enquadramento | enum | opções: plano_geral, plano_medio, close, super_close, plano_detalhe, splash |  |  |
| `descricao` | `description` | Descrição visual | text_long |  |  |  |
| `falas` | `dialogue` | Falas | text_long |  | manuscript |  |
| `onomatopeias` | `sfx` | Onomatopeias | string_short |  |  |  |

#### Personagem (en: Character) · base `Agent`

Qualquer pessoa ou criatura com vontade própria na história.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `papel` | `role` | Papel | enum | opções: protagonista, antagonista, coadjuvante, mentor, figurante |  |  |
| `status` | `status` | Status | enum | opções: vivo, morto, desaparecido, desconhecido |  |  |
| `idade` | `age` | Idade | int |  |  |  |
| `origem` | `origin` | Origem | node_ref | ref: @Place |  |  |
| `aparencia` | `appearance` | Aparência | text_long |  |  |  |
| `personalidade` | `personality` | Personalidade | text_long |  |  |  |
| `voz` | `voice` | Voz / jeito de falar | string_short |  |  |  |
| `motivacao` | `motivation` | Motivação | text_long |  |  |  |
| `objetivo` | `goal` | Objetivo | text_long |  |  |  |
| `medo` | `fear` | Medo / ferida | text_long |  |  |  |
| `segredo` | `secret` | Segredo | text_long |  | secret |  |
| `arco` | `arc` | Arco de transformação | text_long |  |  |  |

#### Página (en: Page) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `lado` | `side` | Lado | enum | opções: esquerda, direita, dupla |  |  |
| `virada` | `page_turn` | Virada de página (revelação) | text_long |  |  |  |

#### Sistema (en: System) · base `Concept`

Sistema de regras do mundo (magia, tecnologia, economia, política).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `regras` | `rules` | Regras | text_long |  |  | foundational |
| `excecoes` | `exceptions` | Exceções conhecidas | text_long |  |  |  |

#### Série (en: Series) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `premissa` | `premise` | Premissa | text_long |  |  | critical |
| `demografia` | `demographic` | Demografia | enum | opções: shounen, shoujo, seinen, josei, kodomo, geral |  |  |

#### Template (en: Template) · base `Note` · chave de sistema `template`

Estrutura narrativa aplicada ao projeto (criado pelo harness).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `template_id` | `template_id` | Template de origem | string_short |  |  |  |
| `tipo_de_unidade` | `unit_type` | Unidade medida | string_short |  |  |  |
| `unidades_planejadas` | `planned_units` | Unidades planejadas | int |  |  |  |
| `escopo` | `scope` | Escopo (id da unidade cujas partes o template mede) | string_short |  |  |  |
| `fonte` | `source` | Fonte | string_short |  |  |  |

#### Volume (en: Volume) · base `Unit`

### Relações

| Nome pt | Nome en | Base | Pares permitidos | Chave de sistema |
|---|---|---|---|---|
| ACONTECE_EM | HAPPENS_IN | PARTICIPATION | @Event → @Unit |  |
| ALIADO_DE | ALLY_OF | SOCIAL | @Agent → @Agent |  |
| APARECE_EM | APPEARS_IN | PARTICIPATION | * → @Unit | appears_in |
| BEAT_DE | BEAT_OF | REFERENCE | Beat → Template | beat_of |
| CAUSA | CAUSES | CAUSAL | * → @Event; @Event → * |  |
| CONECTA_A | CONNECTS_TO | SPATIAL | @Place → @Place |  |
| CULTURA_DE | CULTURE_OF | ORIGIN | Cultura → @Place; Cultura → Facção |  |
| DOMINA | MASTERS | POSSESSION | @Agent → @Ability; @Object → @Ability |  |
| ESTÁ_EM | LOCATED_IN | SPATIAL | * → @Place | located_in |
| FAMÍLIA_DE | FAMILY_OF | SOCIAL | @Agent → @Agent |  |
| INIMIGO_DE | ENEMY_OF | SOCIAL | @Agent → @Agent |  |
| MAPEIA_BEAT | MAPS_TO_BEAT | REFERENCE | @Unit → Beat | maps_to_beat |
| MEMBRO_DE | MEMBER_OF | SOCIAL | @Agent → Facção |  |
| PAGA | PAYS_OFF | SETUP_PAYOFF | @Unit → @Device; @Unit → * | pays |
| PLANTA | PLANTS | SETUP_PAYOFF | @Unit → @Device; @Unit → * | plants |
| POSSUI | OWNS | POSSESSION | @Agent → @Object; @Place → @Object |  |
| RELACIONA_COM | RELATES_TO | SOCIAL | @Agent → @Agent |  |
| SABE | KNOWS | KNOWLEDGE | @Agent → * |  |
| SEGUE | FOLLOWS | ORIGIN | @Agent → @Concept |  |
| SOBRE | ABOUT | REFERENCE | @Device → * | about |

### Diretivas do perfil

- **write**: Antes de criar personagem, lugar ou item, procure com node_search se já existe.
- **context** (bases: Agent): Cultura e costumes são pano de fundo (norma), não lei. Se um personagem foge do costume da origem, a narrativa deve mostrar ou justificar.
- **context, write**: Ao terminar uma unidade, registre o que mudou (posse, conhecimento, local, relações) com âncoras fromUnit/toUnit, defina o elenco (cast) e marque ganchos/pistas com PLANTA/PAGA.
- **context** (tipos: Página, Capítulo): Revelações fortes funcionam melhor no início de uma página da esquerda (depois da virada); planeje o ritmo pela página, não pela frase.
- **context** (tipos: Painel): Cada painel deve ter uma única ação clara; falas curtas cabem melhor nos balões.

## Perfil `publicidade` — Publicidade

Campanhas e peças: briefing, persona, mensagem-chave, spots e shots, com diretrizes de marca como lei.

### Tipos de nó

#### Beat (en: Beat) · base `Note` · chave de sistema `beat`

Marco de um template, com posição esperada na obra.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `chave` | `key` | Chave | string_short |  |  |  |
| `posicao` | `position` | Posição esperada (0 a 1) | decimal |  |  |  |
| `funcao` | `function` | Função dramática | text_long |  |  |  |
| `criterio` | `criteria` | Critério de aceite | text_long |  |  |  |
| `recursos` | `devices` | Recursos esperados | list | itens: string_short |  |  |
| `obrigatorio` | `required` | Obrigatório | bool |  |  |  |

#### Campanha (en: Campaign) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `briefing` | `brief` | Briefing | text_long |  |  | critical |
| `objetivo` | `business_goal` | Objetivo de negócio | text_long |  |  |  |
| `kpi` | `kpi` | KPI | string_short |  |  |  |

#### Cultura (en: Culture) · base `Concept`

Costumes de um povo ou lugar. Pano de fundo, não lei — personagens podem fugir dela.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `costumes` | `customs` | Costumes | text_long |  |  |  |
| `valores` | `values` | Valores | text_long |  |  |  |
| `aparencia_tipica` | `typical_appearance` | Aparência típica | text_long |  |  |  |
| `lingua` | `language` | Língua / sotaque | string_short |  |  |  |
| `crencas` | `beliefs` | Crenças | text_long |  |  |  |

#### Diretriz de marca (en: Brand guideline) · base `Concept`

Regras da marca: tom de voz, o que nunca dizer, uso de logo e cores.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `regras` | `rules` | Regras | text_long |  |  | foundational |

#### Evento (en: Event) · base `Event`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `quando` | `when` | Quando (no mundo) | story_time |  |  |  |
| `consequencias` | `consequences` | Consequências | text_long |  |  |  |

#### Facção (en: Faction) · base `Agent`

Grupo organizado (guilda, família, empresa, culto, exército).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `ideologia` | `ideology` | Ideologia | text_long |  |  |  |
| `lider` | `leader` | Líder | node_ref | ref: Personagem |  |  |
| `alcance` | `reach` | Alcance | enum | opções: local, regional, global |  |  |
| `recursos` | `resources` | Recursos | text_long |  |  |  |

#### Gancho (en: Hook) · base `Device` · chave de sistema `thread`

Setup que pede payoff — foreshadowing, arma de Chekhov, mistério, promessa ao leitor.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: foreshadowing, arma_de_chekhov, misterio, promessa, red_herring, cliffhanger, subtrama |  |  |
| `status` | `status` | Status | enum | opções: aberto, pago, abandonado |  |  |
| `payoff_planejado` | `planned_payoff` | Payoff planejado | text_long |  |  |  |

#### Habilidade (en: Ability) · base `Ability`

Poder, magia, técnica ou mecânica que um agente pode usar.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `efeito` | `effect` | Efeito | text_long |  |  |  |
| `custo` | `cost` | Custo | text_long |  |  | foundational |
| `condicoes` | `conditions` | Condições de uso | text_long |  |  | foundational |
| `limites` | `limits` | Limites | text_long |  |  | foundational |

#### Item (en: Item) · base `Object`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | string_short |  |  |  |
| `efeito` | `effect` | Efeito / função | text_long |  |  |  |
| `regras_de_uso` | `rules_of_use` | Regras de uso | text_long |  |  | critical |

#### Lugar (en: Place) · base `Place`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: continente, regiao, cidade, vila, construcao, comodo, natureza, ponto_de_interesse, outro |  |  |
| `dentro_de` | `inside` | Dentro de | node_ref | ref: @Place |  |  |
| `clima` | `climate` | Clima | string_short |  |  |  |
| `populacao` | `population` | População / densidade | string_short |  |  |  |
| `governo` | `government` | Governo / liderança | text_long |  |  |  |
| `descricao_sensorial` | `sensory_description` | Descrição sensorial | text_long |  |  |  |

#### Mensagem-chave (en: Key message) · base `Concept`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `frase` | `line` | Frase | string_short |  |  | foundational |

#### Persona (en: Persona) · base `Agent`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `dores` | `pains` | Dores | text_long |  |  |  |
| `desejos` | `desires` | Desejos | text_long |  |  |  |
| `contexto` | `context` | Contexto de uso | text_long |  |  |  |

#### Personagem (en: Character) · base `Agent`

Qualquer pessoa ou criatura com vontade própria na história.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `papel` | `role` | Papel | enum | opções: protagonista, antagonista, coadjuvante, mentor, figurante |  |  |
| `status` | `status` | Status | enum | opções: vivo, morto, desaparecido, desconhecido |  |  |
| `idade` | `age` | Idade | int |  |  |  |
| `origem` | `origin` | Origem | node_ref | ref: @Place |  |  |
| `aparencia` | `appearance` | Aparência | text_long |  |  |  |
| `personalidade` | `personality` | Personalidade | text_long |  |  |  |
| `voz` | `voice` | Voz / jeito de falar | string_short |  |  |  |
| `motivacao` | `motivation` | Motivação | text_long |  |  |  |
| `objetivo` | `goal` | Objetivo | text_long |  |  |  |
| `medo` | `fear` | Medo / ferida | text_long |  |  |  |
| `segredo` | `secret` | Segredo | text_long |  | secret |  |
| `arco` | `arc` | Arco de transformação | text_long |  |  |  |

#### Peça (en: Piece) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `canal` | `channel` | Canal | enum | opções: tv, youtube, instagram, tiktok, radio, ooh, podcast, outro |  |  |

#### Produto (en: Product) · base `Object`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `beneficio_principal` | `main_benefit` | Benefício principal | text_long |  |  | critical |
| `provas` | `proof` | Provas / diferenciais | text_long |  |  |  |

#### Shot (en: Shot) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `segundos` | `seconds` | Tempo (de-até, em s) | string_short |  |  |  |
| `imagem` | `image` | Imagem | text_long |  |  |  |
| `audio` | `audio` | Áudio / locução | text_long |  |  |  |
| `lettering` | `lettering` | Lettering | string_short |  |  |  |

#### Sistema (en: System) · base `Concept`

Sistema de regras do mundo (magia, tecnologia, economia, política).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `regras` | `rules` | Regras | text_long |  |  | foundational |
| `excecoes` | `exceptions` | Exceções conhecidas | text_long |  |  |  |

#### Spot (en: Spot) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `duracao_seg` | `duration_s` | Duração (s) | int |  |  |  |
| `cta` | `cta` | Chamada para ação | string_short |  |  | critical |
| `roteiro` | `script` | Roteiro | text_long |  | manuscript |  |

#### Template (en: Template) · base `Note` · chave de sistema `template`

Estrutura narrativa aplicada ao projeto (criado pelo harness).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `template_id` | `template_id` | Template de origem | string_short |  |  |  |
| `tipo_de_unidade` | `unit_type` | Unidade medida | string_short |  |  |  |
| `unidades_planejadas` | `planned_units` | Unidades planejadas | int |  |  |  |
| `escopo` | `scope` | Escopo (id da unidade cujas partes o template mede) | string_short |  |  |  |
| `fonte` | `source` | Fonte | string_short |  |  |  |

### Relações

| Nome pt | Nome en | Base | Pares permitidos | Chave de sistema |
|---|---|---|---|---|
| ACONTECE_EM | HAPPENS_IN | PARTICIPATION | @Event → @Unit |  |
| ALIADO_DE | ALLY_OF | SOCIAL | @Agent → @Agent |  |
| APARECE_EM | APPEARS_IN | PARTICIPATION | * → @Unit | appears_in |
| BEAT_DE | BEAT_OF | REFERENCE | Beat → Template | beat_of |
| CAUSA | CAUSES | CAUSAL | * → @Event; @Event → * |  |
| COMUNICA | CONVEYS | REFERENCE | @Unit → Mensagem-chave; @Unit → Produto |  |
| CONECTA_A | CONNECTS_TO | SPATIAL | @Place → @Place |  |
| CULTURA_DE | CULTURE_OF | ORIGIN | Cultura → @Place; Cultura → Facção |  |
| DOMINA | MASTERS | POSSESSION | @Agent → @Ability; @Object → @Ability |  |
| ESTÁ_EM | LOCATED_IN | SPATIAL | * → @Place | located_in |
| FALA_COM | SPEAKS_TO | SOCIAL | @Unit → Persona |  |
| FAMÍLIA_DE | FAMILY_OF | SOCIAL | @Agent → @Agent |  |
| INIMIGO_DE | ENEMY_OF | SOCIAL | @Agent → @Agent |  |
| MAPEIA_BEAT | MAPS_TO_BEAT | REFERENCE | @Unit → Beat | maps_to_beat |
| MEMBRO_DE | MEMBER_OF | SOCIAL | @Agent → Facção |  |
| PAGA | PAYS_OFF | SETUP_PAYOFF | @Unit → @Device; @Unit → * | pays |
| PLANTA | PLANTS | SETUP_PAYOFF | @Unit → @Device; @Unit → * | plants |
| POSSUI | OWNS | POSSESSION | @Agent → @Object; @Place → @Object |  |
| RELACIONA_COM | RELATES_TO | SOCIAL | @Agent → @Agent |  |
| SABE | KNOWS | KNOWLEDGE | @Agent → * |  |
| SEGUE | FOLLOWS | ORIGIN | @Agent → @Concept |  |
| SOBRE | ABOUT | REFERENCE | @Device → * | about |

### Diretivas do perfil

- **write**: Antes de criar personagem, lugar ou item, procure com node_search se já existe.
- **context** (bases: Agent): Cultura e costumes são pano de fundo (norma), não lei. Se um personagem foge do costume da origem, a narrativa deve mostrar ou justificar.
- **context, write**: Ao terminar uma unidade, registre o que mudou (posse, conhecimento, local, relações) com âncoras fromUnit/toUnit, defina o elenco (cast) e marque ganchos/pistas com PLANTA/PAGA.
- **always**: Diretrizes de marca são lei: nenhuma peça pode contrariá-las.
- **context** (tipos: Spot, Shot): Os primeiros 3 segundos precisam prender atenção; a marca deve aparecer antes do fim e o CTA tem que ser explícito.

## Perfil `rpg_mesa` — RPG de mesa

Campanhas, sessões e encontros, com mapas, NPCs, monstros e ganchos para os jogadores.

### Tipos de nó

#### Arco (en: Arc) · base `Unit`

#### Beat (en: Beat) · base `Note` · chave de sistema `beat`

Marco de um template, com posição esperada na obra.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `chave` | `key` | Chave | string_short |  |  |  |
| `posicao` | `position` | Posição esperada (0 a 1) | decimal |  |  |  |
| `funcao` | `function` | Função dramática | text_long |  |  |  |
| `criterio` | `criteria` | Critério de aceite | text_long |  |  |  |
| `recursos` | `devices` | Recursos esperados | list | itens: string_short |  |  |
| `obrigatorio` | `required` | Obrigatório | bool |  |  |  |

#### Campanha (en: Campaign) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `premissa` | `premise` | Premissa | text_long |  |  | critical |
| `sistema_de_jogo` | `game_system` | Sistema de jogo | string_short |  |  |  |
| `tom` | `tone` | Tom | string_short |  |  |  |

#### Cultura (en: Culture) · base `Concept`

Costumes de um povo ou lugar. Pano de fundo, não lei — personagens podem fugir dela.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `costumes` | `customs` | Costumes | text_long |  |  |  |
| `valores` | `values` | Valores | text_long |  |  |  |
| `aparencia_tipica` | `typical_appearance` | Aparência típica | text_long |  |  |  |
| `lingua` | `language` | Língua / sotaque | string_short |  |  |  |
| `crencas` | `beliefs` | Crenças | text_long |  |  |  |

#### Encontro (en: Encounter) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: combate, social, exploracao, armadilha, enigma |  |  |
| `local` | `location` | Local | node_ref | ref: @Place | location |  |
| `dificuldade` | `difficulty` | Dificuldade | enum | opções: trivial, facil, media, dificil, mortal |  |  |

#### Evento (en: Event) · base `Event`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `quando` | `when` | Quando (no mundo) | story_time |  |  |  |
| `consequencias` | `consequences` | Consequências | text_long |  |  |  |

#### Facção (en: Faction) · base `Agent`

Grupo organizado (guilda, família, empresa, culto, exército).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `ideologia` | `ideology` | Ideologia | text_long |  |  |  |
| `lider` | `leader` | Líder | node_ref | ref: Personagem |  |  |
| `alcance` | `reach` | Alcance | enum | opções: local, regional, global |  |  |
| `recursos` | `resources` | Recursos | text_long |  |  |  |

#### Gancho (en: Hook) · base `Device` · chave de sistema `thread`

Setup que pede payoff — foreshadowing, arma de Chekhov, mistério, promessa ao leitor.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: foreshadowing, arma_de_chekhov, misterio, promessa, red_herring, cliffhanger, subtrama |  |  |
| `status` | `status` | Status | enum | opções: aberto, pago, abandonado |  |  |
| `payoff_planejado` | `planned_payoff` | Payoff planejado | text_long |  |  |  |

#### Habilidade (en: Ability) · base `Ability`

Poder, magia, técnica ou mecânica que um agente pode usar.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `efeito` | `effect` | Efeito | text_long |  |  |  |
| `custo` | `cost` | Custo | text_long |  |  | foundational |
| `condicoes` | `conditions` | Condições de uso | text_long |  |  | foundational |
| `limites` | `limits` | Limites | text_long |  |  | foundational |

#### Item (en: Item) · base `Object`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | string_short |  |  |  |
| `efeito` | `effect` | Efeito / função | text_long |  |  |  |
| `regras_de_uso` | `rules_of_use` | Regras de uso | text_long |  |  | critical |

#### Lugar (en: Place) · base `Place`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: continente, regiao, cidade, vila, construcao, comodo, natureza, ponto_de_interesse, outro |  |  |
| `dentro_de` | `inside` | Dentro de | node_ref | ref: @Place |  |  |
| `clima` | `climate` | Clima | string_short |  |  |  |
| `populacao` | `population` | População / densidade | string_short |  |  |  |
| `governo` | `government` | Governo / liderança | text_long |  |  |  |
| `descricao_sensorial` | `sensory_description` | Descrição sensorial | text_long |  |  |  |

#### Mapa (en: Map) · base `Media`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `arquivo` | `file` | Arquivo | media |  |  |  |
| `escala` | `scale` | Escala | string_short |  |  |  |

#### Monstro (en: Monster) · base `Agent`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `ficha` | `stats` | Ficha | composite | objeto: Ficha |  |  |
| `comportamento` | `behavior` | Comportamento / táticas | text_long |  |  |  |
| `habitat` | `habitat` | Habitat | node_ref | ref: @Place |  |  |

#### Personagem (en: Character) · base `Agent`

Qualquer pessoa ou criatura com vontade própria na história.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `papel` | `role` | Papel | enum | opções: protagonista, antagonista, coadjuvante, mentor, figurante |  |  |
| `status` | `status` | Status | enum | opções: vivo, morto, desaparecido, desconhecido |  |  |
| `idade` | `age` | Idade | int |  |  |  |
| `origem` | `origin` | Origem | node_ref | ref: @Place |  |  |
| `aparencia` | `appearance` | Aparência | text_long |  |  |  |
| `personalidade` | `personality` | Personalidade | text_long |  |  |  |
| `voz` | `voice` | Voz / jeito de falar | string_short |  |  |  |
| `motivacao` | `motivation` | Motivação | text_long |  |  |  |
| `objetivo` | `goal` | Objetivo | text_long |  |  |  |
| `medo` | `fear` | Medo / ferida | text_long |  |  |  |
| `segredo` | `secret` | Segredo | text_long |  | secret |  |
| `arco` | `arc` | Arco de transformação | text_long |  |  |  |

#### Sessão (en: Session) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `data_real` | `real_date` | Data da sessão | string_short |  |  |  |
| `resumo_do_que_aconteceu` | `what_happened` | O que aconteceu na mesa | text_long |  | manuscript |  |
| `status` | `status` | Status | enum | opções: planejada, jogada |  |  |

#### Sistema (en: System) · base `Concept`

Sistema de regras do mundo (magia, tecnologia, economia, política).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `regras` | `rules` | Regras | text_long |  |  | foundational |
| `excecoes` | `exceptions` | Exceções conhecidas | text_long |  |  |  |

#### Template (en: Template) · base `Note` · chave de sistema `template`

Estrutura narrativa aplicada ao projeto (criado pelo harness).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `template_id` | `template_id` | Template de origem | string_short |  |  |  |
| `tipo_de_unidade` | `unit_type` | Unidade medida | string_short |  |  |  |
| `unidades_planejadas` | `planned_units` | Unidades planejadas | int |  |  |  |
| `escopo` | `scope` | Escopo (id da unidade cujas partes o template mede) | string_short |  |  |  |
| `fonte` | `source` | Fonte | string_short |  |  |  |

### Relações

| Nome pt | Nome en | Base | Pares permitidos | Chave de sistema |
|---|---|---|---|---|
| ACONTECE_EM | HAPPENS_IN | PARTICIPATION | @Event → @Unit |  |
| ALIADO_DE | ALLY_OF | SOCIAL | @Agent → @Agent |  |
| APARECE_EM | APPEARS_IN | PARTICIPATION | * → @Unit | appears_in |
| BEAT_DE | BEAT_OF | REFERENCE | Beat → Template | beat_of |
| CAUSA | CAUSES | CAUSAL | * → @Event; @Event → * |  |
| CONECTA_A | CONNECTS_TO | SPATIAL | @Place → @Place |  |
| CULTURA_DE | CULTURE_OF | ORIGIN | Cultura → @Place; Cultura → Facção |  |
| DOMINA | MASTERS | POSSESSION | @Agent → @Ability; @Object → @Ability |  |
| ENFRENTA | FACES | PARTICIPATION | @Agent → Encontro |  |
| ESTÁ_EM | LOCATED_IN | SPATIAL | * → @Place | located_in |
| FAMÍLIA_DE | FAMILY_OF | SOCIAL | @Agent → @Agent |  |
| INIMIGO_DE | ENEMY_OF | SOCIAL | @Agent → @Agent |  |
| MAPEADO_EM | MAPPED_ON | REFERENCE | @Place → Mapa |  |
| MAPEIA_BEAT | MAPS_TO_BEAT | REFERENCE | @Unit → Beat | maps_to_beat |
| MEMBRO_DE | MEMBER_OF | SOCIAL | @Agent → Facção |  |
| PAGA | PAYS_OFF | SETUP_PAYOFF | @Unit → @Device; @Unit → * | pays |
| PLANTA | PLANTS | SETUP_PAYOFF | @Unit → @Device; @Unit → * | plants |
| POSSUI | OWNS | POSSESSION | @Agent → @Object; @Place → @Object |  |
| RELACIONA_COM | RELATES_TO | SOCIAL | @Agent → @Agent |  |
| SABE | KNOWS | KNOWLEDGE | @Agent → * |  |
| SEGUE | FOLLOWS | ORIGIN | @Agent → @Concept |  |
| SOBRE | ABOUT | REFERENCE | @Device → * | about |

### Diretivas do perfil

- **write**: Antes de criar personagem, lugar ou item, procure com node_search se já existe.
- **context** (bases: Agent): Cultura e costumes são pano de fundo (norma), não lei. Se um personagem foge do costume da origem, a narrativa deve mostrar ou justificar.
- **context, write**: Ao terminar uma unidade, registre o que mudou (posse, conhecimento, local, relações) com âncoras fromUnit/toUnit, defina o elenco (cast) e marque ganchos/pistas com PLANTA/PAGA.
- **context**: Os jogadores decidem: prepare situações e motivações de NPCs, não sequências fixas. Ofereça ao menos dois caminhos por encontro.
- **session, write** (tipos: Sessão): Depois de cada sessão jogada, registre no grafo o que de fato aconteceu na mesa (decisões, mortes, itens): isso vira cânone.

## Perfil `visual_novel` — Visual novel

Narrativa ramificada: rotas, cenas, escolhas, flags e finais.

### Tipos de nó

#### Beat (en: Beat) · base `Note` · chave de sistema `beat`

Marco de um template, com posição esperada na obra.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `chave` | `key` | Chave | string_short |  |  |  |
| `posicao` | `position` | Posição esperada (0 a 1) | decimal |  |  |  |
| `funcao` | `function` | Função dramática | text_long |  |  |  |
| `criterio` | `criteria` | Critério de aceite | text_long |  |  |  |
| `recursos` | `devices` | Recursos esperados | list | itens: string_short |  |  |
| `obrigatorio` | `required` | Obrigatório | bool |  |  |  |

#### Cena (en: Scene) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `cenario` | `background` | Cenário (background) | node_ref | ref: @Place | location |  |
| `musica` | `music` | Música | string_short |  |  |  |
| `script` | `script` | Script | text_long |  | manuscript |  |

#### Cultura (en: Culture) · base `Concept`

Costumes de um povo ou lugar. Pano de fundo, não lei — personagens podem fugir dela.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `costumes` | `customs` | Costumes | text_long |  |  |  |
| `valores` | `values` | Valores | text_long |  |  |  |
| `aparencia_tipica` | `typical_appearance` | Aparência típica | text_long |  |  |  |
| `lingua` | `language` | Língua / sotaque | string_short |  |  |  |
| `crencas` | `beliefs` | Crenças | text_long |  |  |  |

#### Escolha (en: Choice) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `pergunta` | `prompt` | Pergunta ao jogador | text_long |  |  |  |
| `opcoes` | `options` | Opções | list | itens: string_short |  |  |

#### Evento (en: Event) · base `Event`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `quando` | `when` | Quando (no mundo) | story_time |  |  |  |
| `consequencias` | `consequences` | Consequências | text_long |  |  |  |

#### Facção (en: Faction) · base `Agent`

Grupo organizado (guilda, família, empresa, culto, exército).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `ideologia` | `ideology` | Ideologia | text_long |  |  |  |
| `lider` | `leader` | Líder | node_ref | ref: Personagem |  |  |
| `alcance` | `reach` | Alcance | enum | opções: local, regional, global |  |  |
| `recursos` | `resources` | Recursos | text_long |  |  |  |

#### Final (en: Ending) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: bom, normal, ruim, verdadeiro, secreto |  |  |
| `condicoes` | `conditions` | Condições | text_long |  |  | critical |

#### Flag (en: Flag) · base `Concept`

Variável de estado que as escolhas alteram.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: booleana, contador, afeicao |  |  |
| `valor_inicial` | `initial_value` | Valor inicial | string_short |  |  |  |

#### Gancho (en: Hook) · base `Device` · chave de sistema `thread`

Setup que pede payoff — foreshadowing, arma de Chekhov, mistério, promessa ao leitor.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: foreshadowing, arma_de_chekhov, misterio, promessa, red_herring, cliffhanger, subtrama |  |  |
| `status` | `status` | Status | enum | opções: aberto, pago, abandonado |  |  |
| `payoff_planejado` | `planned_payoff` | Payoff planejado | text_long |  |  |  |

#### Habilidade (en: Ability) · base `Ability`

Poder, magia, técnica ou mecânica que um agente pode usar.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `efeito` | `effect` | Efeito | text_long |  |  |  |
| `custo` | `cost` | Custo | text_long |  |  | foundational |
| `condicoes` | `conditions` | Condições de uso | text_long |  |  | foundational |
| `limites` | `limits` | Limites | text_long |  |  | foundational |

#### Item (en: Item) · base `Object`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | string_short |  |  |  |
| `efeito` | `effect` | Efeito / função | text_long |  |  |  |
| `regras_de_uso` | `rules_of_use` | Regras de uso | text_long |  |  | critical |

#### Lugar (en: Place) · base `Place`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `tipo` | `kind` | Tipo | enum | opções: continente, regiao, cidade, vila, construcao, comodo, natureza, ponto_de_interesse, outro |  |  |
| `dentro_de` | `inside` | Dentro de | node_ref | ref: @Place |  |  |
| `clima` | `climate` | Clima | string_short |  |  |  |
| `populacao` | `population` | População / densidade | string_short |  |  |  |
| `governo` | `government` | Governo / liderança | text_long |  |  |  |
| `descricao_sensorial` | `sensory_description` | Descrição sensorial | text_long |  |  |  |

#### Obra (en: Work) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `premissa` | `premise` | Premissa | text_long |  |  | critical |

#### Personagem (en: Character) · base `Agent`

Qualquer pessoa ou criatura com vontade própria na história.

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `papel` | `role` | Papel | enum | opções: protagonista, antagonista, coadjuvante, mentor, figurante |  |  |
| `status` | `status` | Status | enum | opções: vivo, morto, desaparecido, desconhecido |  |  |
| `idade` | `age` | Idade | int |  |  |  |
| `origem` | `origin` | Origem | node_ref | ref: @Place |  |  |
| `aparencia` | `appearance` | Aparência | text_long |  |  |  |
| `personalidade` | `personality` | Personalidade | text_long |  |  |  |
| `voz` | `voice` | Voz / jeito de falar | string_short |  |  |  |
| `motivacao` | `motivation` | Motivação | text_long |  |  |  |
| `objetivo` | `goal` | Objetivo | text_long |  |  |  |
| `medo` | `fear` | Medo / ferida | text_long |  |  |  |
| `segredo` | `secret` | Segredo | text_long |  | secret |  |
| `arco` | `arc` | Arco de transformação | text_long |  |  |  |

#### Rota (en: Route) · base `Unit`

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `personagem_foco` | `focus_character` | Personagem foco | node_ref | ref: Personagem |  |  |
| `condicao_entrada` | `entry_condition` | Condição de entrada | text_long |  |  | critical |

#### Sistema (en: System) · base `Concept`

Sistema de regras do mundo (magia, tecnologia, economia, política).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `regras` | `rules` | Regras | text_long |  |  | foundational |
| `excecoes` | `exceptions` | Exceções conhecidas | text_long |  |  |  |

#### Template (en: Template) · base `Note` · chave de sistema `template`

Estrutura narrativa aplicada ao projeto (criado pelo harness).

| Chave pt | Chave en | Rótulo | Tipo | Detalhe | Papel | Criticidade padrão |
|---|---|---|---|---|---|---|
| `template_id` | `template_id` | Template de origem | string_short |  |  |  |
| `tipo_de_unidade` | `unit_type` | Unidade medida | string_short |  |  |  |
| `unidades_planejadas` | `planned_units` | Unidades planejadas | int |  |  |  |
| `escopo` | `scope` | Escopo (id da unidade cujas partes o template mede) | string_short |  |  |  |
| `fonte` | `source` | Fonte | string_short |  |  |  |

### Relações

| Nome pt | Nome en | Base | Pares permitidos | Chave de sistema |
|---|---|---|---|---|
| ACONTECE_EM | HAPPENS_IN | PARTICIPATION | @Event → @Unit |  |
| ALIADO_DE | ALLY_OF | SOCIAL | @Agent → @Agent |  |
| ALTERA_FLAG | CHANGES_FLAG | CAUSAL | @Unit → Flag |  |
| APARECE_EM | APPEARS_IN | PARTICIPATION | * → @Unit | appears_in |
| BEAT_DE | BEAT_OF | REFERENCE | Beat → Template | beat_of |
| CAUSA | CAUSES | CAUSAL | * → @Event; @Event → * |  |
| CONECTA_A | CONNECTS_TO | SPATIAL | @Place → @Place |  |
| CULTURA_DE | CULTURE_OF | ORIGIN | Cultura → @Place; Cultura → Facção |  |
| DOMINA | MASTERS | POSSESSION | @Agent → @Ability; @Object → @Ability |  |
| ESTÁ_EM | LOCATED_IN | SPATIAL | * → @Place | located_in |
| FAMÍLIA_DE | FAMILY_OF | SOCIAL | @Agent → @Agent |  |
| INIMIGO_DE | ENEMY_OF | SOCIAL | @Agent → @Agent |  |
| LEVA_A | LEADS_TO | CAUSAL | Escolha → @Unit; Cena → @Unit |  |
| MAPEIA_BEAT | MAPS_TO_BEAT | REFERENCE | @Unit → Beat | maps_to_beat |
| MEMBRO_DE | MEMBER_OF | SOCIAL | @Agent → Facção |  |
| PAGA | PAYS_OFF | SETUP_PAYOFF | @Unit → @Device; @Unit → * | pays |
| PLANTA | PLANTS | SETUP_PAYOFF | @Unit → @Device; @Unit → * | plants |
| POSSUI | OWNS | POSSESSION | @Agent → @Object; @Place → @Object |  |
| RELACIONA_COM | RELATES_TO | SOCIAL | @Agent → @Agent |  |
| REQUER_FLAG | REQUIRES_FLAG | CAUSAL | @Unit → Flag |  |
| SABE | KNOWS | KNOWLEDGE | @Agent → * |  |
| SEGUE | FOLLOWS | ORIGIN | @Agent → @Concept |  |
| SOBRE | ABOUT | REFERENCE | @Device → * | about |

### Diretivas do perfil

- **write**: Antes de criar personagem, lugar ou item, procure com node_search se já existe.
- **context** (bases: Agent): Cultura e costumes são pano de fundo (norma), não lei. Se um personagem foge do costume da origem, a narrativa deve mostrar ou justificar.
- **context, write**: Ao terminar uma unidade, registre o que mudou (posse, conhecimento, local, relações) com âncoras fromUnit/toUnit, defina o elenco (cast) e marque ganchos/pistas com PLANTA/PAGA.
- **context, write** (tipos: Escolha): Toda escolha precisa de consequência visível (imediata ou tardia) registrada com LEVA_A ou ALTERA_FLAG; escolhas sem efeito frustram o jogador.
- **context**: Fatos do tronco comum valem em todas as rotas; o que só acontece numa rota não pode ser citado em outra.

## Templates de estrutura

### Capítulo serial (en: Serial chapter) · `capitulo_serial` · escopo unidade

Estrutura interna de um capítulo de série (light novel, mangá, web novel): abrir rápido e fechar com gancho. Aplique sobre as cenas ou páginas de um capítulo.

Fonte: Prática de publicação seriada (síntese)

| Posição | Beat (pt / en) | Chave | Obrigatório | Critério |
|---|---|---|---|---|
| 0% | Retomada / Recap | `retomada` | sim | Nas primeiras linhas/painéis o leitor sabe onde está. |
| 20% | Objetivo do capítulo / Chapter goal | `objetivo` | sim | Meta clara para o capítulo. |
| 50% | Complicação / Complication | `complicacao` | sim | Obstáculo ou revelação no meio do capítulo. |
| 80% | Pico / Peak | `pico` | sim | Ação, emoção ou revelação mais forte do capítulo. |
| 100% | Gancho final / Final hook | `gancho` | sim | Última cena/página deixa uma dúvida que puxa o próximo capítulo. |

### Jornada do herói (en: Hero's journey) · `jornada_do_heroi` · escopo obra

Doze etapas da partida, iniciação e retorno do herói.

Fonte: Joseph Campbell (monomito), na versão de 12 etapas de Christopher Vogler

| Posição | Beat (pt / en) | Chave | Obrigatório | Critério |
|---|---|---|---|---|
| 0% | Mundo comum / Ordinary world | `mundo_comum` | sim | Mostra a vida normal e a falta interna do herói. |
| 10% | Chamado à aventura / Call to adventure | `chamado` | sim | Há um convite ou ameaça clara que pede ação. |
| 15% | Recusa do chamado / Refusal of the call | `recusa` | não | O herói resiste e o custo de agir fica evidente. |
| 20% | Encontro com o mentor / Meeting the mentor | `mentor` | sim | O herói recebe algo (saber, objeto, coragem) que vai usar depois. |
| 25% | Travessia do limiar / Crossing the threshold | `travessia` | sim | Há uma passagem clara para um território novo. |
| 35% | Provas, aliados e inimigos / Tests, allies and enemies | `provas` | sim | Aparecem aliados e inimigos; o herói é testado. |
| 45% | Aproximação da caverna oculta / Approach to the inmost cave | `aproximacao` | sim | Planos, tensão crescente, o perigo central se aproxima. |
| 50% | Provação / Ordeal | `provacao` | sim | O herói quase perde tudo e sai transformado. |
| 60% | Recompensa / Reward | `recompensa` | sim | Há um ganho concreto (objeto, saber, reconciliação). |
| 75% | Caminho de volta / The road back | `caminho_de_volta` | sim | O herói decide voltar e a pressão aumenta. |
| 90% | Ressurreição / Resurrection | `ressurreicao` | sim | O clímax exige do herói o que ele aprendeu. |
| 100% | Retorno com o elixir / Return with the elixir | `retorno` | sim | O mundo comum é afetado pelo que o herói trouxe. |

### Kishōtenketsu (en: Kishōtenketsu) · `kishotenketsu` · escopo obra

Quatro tempos (introdução, desenvolvimento, virada, conclusão), sem depender de conflito central. Comum em mangá e light novel.

Fonte: Estrutura tradicional da poesia e narrativa chinesa e japonesa

| Posição | Beat (pt / en) | Chave | Obrigatório | Critério |
|---|---|---|---|---|
| 0% | Ki (introdução) / Ki (introduction) | `ki` | sim | O leitor entende o cenário e quem está nele. |
| 30% | Shō (desenvolvimento) / Shō (development) | `sho` | sim | Desenvolve relações e detalhes; expectativa se forma. |
| 60% | Ten (virada) / Ten (twist) | `ten` | sim | Há uma surpresa ou mudança de perspectiva. |
| 100% | Ketsu (conclusão) / Ketsu (conclusion) | `ketsu` | sim | A virada ganha sentido junto com as partes anteriores. |

### Aventura de RPG (one-shot) (en: RPG adventure (one-shot)) · `one_shot_rpg` · escopo obra

Uma aventura fechada em uma ou poucas sessões: gancho, investigação, encontros, revelação e confronto.

Fonte: Estrutura comum de aventuras prontas (síntese)

| Posição | Beat (pt / en) | Chave | Obrigatório | Critério |
|---|---|---|---|---|
| 0% | Gancho inicial / Opening hook | `gancho` | sim | Gancho que conversa com a motivação de pelo menos um personagem jogador. |
| 20% | Investigação / Investigation | `investigacao` | sim | Ao menos três pistas para a conclusão principal (regra das três pistas). |
| 35% | Primeiro encontro / First encounter | `primeiro_encontro` | sim | Desafio que mostra o tom e o perigo da aventura. |
| 55% | Revelação / Reveal | `revelacao` | sim | Muda o que o grupo sabe e o que precisa fazer. |
| 70% | Escalada / Escalation | `escalada` | sim | Consequência das ações do grupo aparece. |
| 90% | Confronto final / Final confrontation | `confronto` | sim | Mais de uma forma de resolver (força, astúcia, diplomacia). |
| 100% | Desfecho e recompensas / Aftermath and rewards | `desfecho` | sim | Mundo reage ao resultado; fica um gancho para a próxima. |

### Quest de jogo (en: Game quest) · `quest_jogo` · escopo unidade

Estrutura de uma missão: gancho, objetivo, obstáculos, complicação, clímax e recompensa. Aplique sobre os encontros de uma quest.

Fonte: Design de quests (síntese)

| Posição | Beat (pt / en) | Chave | Obrigatório | Critério |
|---|---|---|---|---|
| 0% | Gancho / quest giver / Hook / quest giver | `gancho` | sim | Motivo para o jogador se importar, além da recompensa. |
| 10% | Objetivo claro / Clear objective | `objetivo` | sim | Objetivo cabe em uma frase e tem indicação de onde ir. |
| 35% | Jornada e obstáculos / Journey and obstacles | `jornada` | sim | Variedade de desafios (combate, exploração, social, puzzle). |
| 60% | Complicação / Complication | `complicacao` | sim | Nova informação que muda o objetivo ou o lado. |
| 85% | Clímax / chefe / Climax / boss | `climax` | sim | Exige o que o jogador aprendeu na quest. |
| 100% | Recompensa e consequência / Reward and consequence | `recompensa` | sim | Recompensa coerente e mudança visível no mundo ou nos NPCs. |

### Save the Cat (15 beats) (en: Save the Cat (15 beats)) · `save_the_cat` · escopo obra

Folha de beats com proporções fixas, muito usada em roteiro de cinema e romance comercial.

Fonte: Blake Snyder, Save the Cat! (2005)

| Posição | Beat (pt / en) | Chave | Obrigatório | Critério |
|---|---|---|---|---|
| 0% | Imagem de abertura / Opening image | `imagem_de_abertura` | sim | Uma imagem ou cena que resume o estado inicial. |
| 5% | Tema declarado / Theme stated | `tema_declarado` | não | O tema aparece, de preferência dito a ele por outro personagem. |
| 6% | Preparação / Setup | `preparacao` | sim | Ficam claras as coisas que precisam ser consertadas na vida dele. |
| 12% | Catalisador / Catalyst | `catalisador` | sim | Um acontecimento externo tira o protagonista da rotina. |
| 18% | Debate / Debate | `debate` | sim | Dúvida explícita: e agora? devo ir? |
| 25% | Virada para o ato 2 / Break into two | `virada_para_dois` | sim | O protagonista escolhe agir; a história muda de mundo. |
| 30% | História B / B story | `historia_b` | sim | Aparece um personagem/relação que vai ensinar o tema. |
| 35% | Diversão e jogos / Fun and games | `diversao_e_jogos` | sim | Sequências que exploram a premissa com leveza ou espetáculo. |
| 50% | Ponto médio / Midpoint | `ponto_medio` | sim | Virada clara e o relógio começa a correr. |
| 60% | Os inimigos se aproximam / Bad guys close in | `inimigos_se_aproximam` | sim | O grupo racha ou o antagonista avança. |
| 75% | Tudo está perdido / All is lost | `tudo_perdido` | sim | Perda significativa (pessoa, plano, esperança). |
| 80% | Noite escura da alma / Dark night of the soul | `noite_escura` | sim | Momento de reflexão antes da virada final. |
| 82% | Virada para o ato 3 / Break into three | `virada_para_tres` | sim | Nova ideia ou atitude, fruto do tema aprendido. |
| 90% | Final / Finale | `final` | sim | O plano final é executado e o conflito se resolve. |
| 100% | Imagem final / Final image | `imagem_final` | sim | Contrasta com a imagem de abertura e mostra a mudança. |

### Spot publicitário (15-60s) (en: Ad spot (15-60s)) · `spot_publicitario` · escopo unidade

Gancho, problema, virada com o produto, prova, mensagem e chamada para ação. Aplique sobre os shots de um spot.

Fonte: Estrutura de comercial de problema-solução (síntese, próxima de AIDA)

| Posição | Beat (pt / en) | Chave | Obrigatório | Critério |
|---|---|---|---|---|
| 0% | Gancho (0-3s) / Hook (0-3s) | `gancho` | sim | Imagem ou fala que interrompe o padrão nos primeiros segundos. |
| 20% | Problema / tensão / Problem / tension | `problema` | sim | A persona se reconhece na situação. |
| 45% | Virada com o produto / Turn with the product | `virada` | sim | O produto resolve a tensão mostrada. |
| 65% | Benefício e prova / Benefit and proof | `prova` | sim | Benefício visível ou demonstrado, coerente com o produto. |
| 85% | Mensagem-chave / Key message | `mensagem` | sim | A mensagem-chave aparece literal ou claramente. |
| 100% | CTA e marca / CTA and brand | `cta` | sim | CTA explícito e marca visível no fim. |

### Story Circle (en: Story Circle) · `story_circle` · escopo obra

Oito passos em círculo: necessidade, travessia, busca, conquista, preço, retorno e mudança.

Fonte: Dan Harmon, Story Circle (derivado da jornada do herói)

| Posição | Beat (pt / en) | Chave | Obrigatório | Critério |
|---|---|---|---|---|
| 0% | 1. Zona de conforto / 1. Comfort zone | `voce` | sim | Mostra quem ele é e onde está. |
| 14% | 2. Necessidade / 2. Need | `necessidade` | sim | Desejo ou falta fica explícito. |
| 28% | 3. Ir / 3. Go | `ir` | sim | Atravessa para um território novo. |
| 42% | 4. Busca / 4. Search | `busca` | sim | Obstáculos e adaptação ao novo. |
| 57% | 5. Encontra / 5. Find | `encontra` | sim | Conquista concreta do desejo. |
| 71% | 6. Paga o preço / 6. Pay the price | `paga` | sim | Perda ou consequência pesada da conquista. |
| 85% | 7. Volta / 7. Return | `volta` | sim | Movimento de volta ao ponto de partida. |
| 100% | 8. Mudou / 8. Changed | `muda` | sim | A mudança é visível na ação, não só dita. |

### Terror (en: Horror) · `terror` · escopo obra

Escalada do medo: da normalidade à ameaça revelada e ao confronto, com controle rigoroso do que se mostra.

Fonte: Convenções do gênero terror (síntese)

| Posição | Beat (pt / en) | Chave | Obrigatório | Critério |
|---|---|---|---|---|
| 0% | Normalidade / Normality | `normalidade` | sim | Personagens comuns, com algo levemente fora do lugar. |
| 10% | Primeiro sinal / First sign | `primeiro_sinal` | sim | Evento estranho que ainda pode ser racionalizado. |
| 25% | Negação e investigação / Denial and investigation | `negacao` | sim | Tentativa de racionalizar, sinais se acumulam. |
| 40% | Escalada / Escalation | `escalada` | sim | Primeira vítima ou dano real; o medo vira concreto. |
| 55% | Revelação da ameaça / The threat revealed | `revelacao` | sim | Regra da ameaça é estabelecida (marque como fato foundational). |
| 65% | Isolamento / Isolation | `isolamento` | sim | Personagens ficam sozinhos com a ameaça. |
| 78% | Ponto mais baixo / Lowest point | `ponto_mais_baixo` | sim | Aliado morre ou plano falha; parece não haver saída. |
| 90% | Confronto / Confrontation | `confronto` | sim | O confronto respeita as regras da ameaça estabelecidas antes. |
| 100% | Desfecho com eco / Ending with an echo | `eco` | não | Alívio parcial; algo sugere que o mal não acabou. |

### Três atos (en: Three acts) · `tres_atos` · escopo obra

Estrutura clássica: preparação, confronto e resolução, com dois pontos de virada e um ponto médio.

Fonte: Estrutura dramática clássica (Aristóteles; formalizada para roteiro por Syd Field)

| Posição | Beat (pt / en) | Chave | Obrigatório | Critério |
|---|---|---|---|---|
| 0% | Preparação / Setup | `preparacao` | sim | O leitor sabe quem é o protagonista, onde está e o que está em jogo para ele. |
| 12% | Incidente incitante / Inciting incident | `incidente_incitante` | sim | Há um acontecimento concreto que o protagonista não pode ignorar. |
| 25% | Primeira virada / First turning point | `primeira_virada` | sim | Decisão ativa do protagonista que o leva a um território novo e sem volta fácil. |
| 50% | Ponto médio / Midpoint | `ponto_medio` | sim | Algo muda o entendimento do protagonista ou as apostas sobem visivelmente. |
| 75% | Segunda virada / Second turning point | `segunda_virada` | sim | O protagonista está no ponto mais baixo ou diante de uma escolha decisiva. |
| 90% | Clímax / Climax | `climax` | sim | O conflito central se resolve por ação do protagonista. |
| 100% | Resolução / Resolution | `resolucao` | sim | Fica claro o que mudou no mundo e no protagonista; ganchos principais pagos. |
