# Obliterate Harness

> Um "cérebro" em grafo, aberto a qualquer domínio: pessoas (pela interface) e LLMs (pelo MCP) definem os tipos de nó, as relações, as regras e o comportamento da IA, e depois consultam e alimentam o grafo de forma estruturada.

**Status:** plataforma multi-domínio; o domínio é sempre definido por um contexto. O motor narrativo (livros, mangá, templates de história, manuscrito) voltou como um **módulo do contexto "Histórias e mundos"**: só existe nele e nos contextos criados a partir dele; os outros contextos continuam neutros.

## Contextos

A tela inicial é a escolha do **contexto**: o domínio que a plataforma vai rodar. Um contexto define quais tipos de nó existem, como eles se relacionam, quais regras são vinculantes e como a IA deve se comportar. Os projetos vivem dentro de um contexto, e qualquer Claude conectado via MCP pode usá-los.

| Contexto | O que traz |
|---|---|
| 📖 **Histórias e mundos** (embutido) | O motor narrativo que veio da Harness Machine: perfis livro, light novel, mangá, visual novel, jogo, RPG de mesa, filme/série, publicidade; unidades em árvore (obra › capítulo › cena), tempo narrativo (âncoras e `state_at`), ganchos e pistas, segredos, templates de estrutura com cobertura, manuscrito versionado e `writing_context`. Guia completo: [docs/narrative/guia_historias.md](docs/narrative/guia_historias.md) |
| 🏢 **Cérebro corporativo** (embutido) | Equipe, Pessoa, Cliente, Parceiro, Concorrente, Produto, Contrato, Pricing, Oportunidade, Regra de negócio, Integração, Playbook, Documento (com link), Inteligência de mercado, Decisão, Termo do glossário; relações como ATENDE (BizDev → cliente), TEM_PRODUTO_HABILITADO, REGIDO_POR, PRECIFICADO_POR, DOCUMENTADO_EM; diretivas de "responder só com fonte" e "regras são vinculantes" |
| 🧩 **Contextos seus** | Criados na tela inicial (em branco ou a partir de outro contexto) ou por um Claude via MCP (`context_create`) |

Como funciona por baixo:
- Os contextos embutidos vêm de YAML (`harness/contexts/*.yaml`) e apontam para presets de schema (`harness/profiles/`).
- Um contexto criado guarda sua definição num **projeto-molde** escondido: tipos, relações, atributos, regras (fatos) e diretivas. "Editar definição" abre esse molde no editor de sempre, e um Claude o edita com `propose_changes`.
- Um projeto novo no contexto nasce com uma cópia da definição (só o schema, as regras e as diretivas; nós de exemplo do molde não vão junto).
- Papel de atributo `link`: uma URL para o documento com o detalhe, aberta com um clique na interface.
- O grafo se divide em espaços (abas): `actors` (pessoas, equipes, organizações), `knowledge` (todo o resto) e `analysis`; o contexto narrativo acrescenta `story` (unidades e recursos narrativos) e `templates`. Cada contexto dá o próprio nome a eles (narrativo: Personagens, Mundo, Roteiro…).
- Tipos-base `Unit` e `Device`, bases de relação `STRUCTURE`/`SETUP_PAYOFF`, o tipo `story_time` e a relação de sistema `CONTAINS` só existem em contextos narrativos; fora deles o servidor recusa com uma mensagem clara.

MCP: `context_list`, `context_get`, `context_create`, `context_update`; `project_list(contextId)`; `project_create(contextId, preset)`. O `session_begin` traz o contexto e as instruções dele no briefing. Prompts prontos: `design_context`, `ingest_document` e `answer_from_graph`; no narrativo, `start_project`, `build_world` e `write_unit`. Tools do motor narrativo: `writing_context`, `state_at`, `story_outline`, `unit_text_*`, `unit_cast_set`, `unit_validate`, `manuscript_export`, `template_*`, `milestone_coverage`, `open_threads`, `profile_*`, `project_settings`.

Projeto de demonstração: `node scripts/seed_demo_corporativo.mjs` cria "Demo — Pagamentos Exemplo S.A." (empresa fictícia) no contexto corporativo.

## A ideia em uma frase

Uma plataforma **em branco**: o usuário (ou uma LLM) define os tipos de nó e de relação, as regras vinculantes e como a IA deve se comportar, num **contexto**; depois conecta LLMs via MCP para consultar e alimentar o grafo de forma estruturada, sem depender da janela de contexto delas. Uma empresa (áreas, produtos, regras, sistemas, documentos) é um contexto; uma base de pesquisa é outro.

## Rodar

Pré-requisito: Docker Desktop (WSL2). Java, Gradle e Node rodam dentro dos containers.

```bash
git clone https://github.com/<seu-usuario>/obliterate-harness.git
```

```bash
cd obliterate-harness
```

Opcional: copie `.env.example` para `.env` e troque as senhas locais (sem `.env`, valem os padrões de desenvolvimento).

```bash
cp .env.example .env
```

Suba a infraestrutura completa (Neo4j, Postgres, API/MCP e interface):

```bash
docker compose up -d --build neo4j postgres app web
```

O primeiro build baixa as imagens e compila o backend (alguns minutos). Para acompanhar até a API ficar pronta:

```bash
docker compose logs -f app
```

Opcional, com GPU NVIDIA: o Ollama fica no perfil `ai`.

```bash
docker compose --profile ai up -d ollama
```

Para parar (os dados ficam nos volumes `neo4j_data` e `pg_data`):

```bash
docker compose down
```

| Serviço | Endereço |
|---|---|
| Interface | http://localhost:5273 |
| API REST | http://localhost:8180/api |
| MCP | http://localhost:8180/mcp |
| Neo4j Browser | http://localhost:7574 (bolt 7787) |
| Postgres | localhost:5532 |

Tudo fica preso em `127.0.0.1`. O Neo4j usa heap 2G e pagecache 1G.

Na subida, uma migração automática e idempotente só toca projetos do contexto narrativo (chaves de sistema dos tipos do motor, perfis atualizados); os outros contextos não são alterados. A migração Flyway V5 recria as tabelas de rascunhos de texto e de templates próprios.

Testes de ponta a ponta (o padrão aponta para a porta 8180):

```bash
node scripts/smoke.mjs
```

```bash
node scripts/smoke_foco.mjs
```

```bash
node scripts/smoke_fase4.mjs
```

```bash
node scripts/smoke_backlog.mjs
```

```bash
node scripts/smoke_contexts.mjs
```

```bash
node scripts/smoke_archive.mjs
```

`smoke.mjs`, `smoke_contexts.mjs` e `smoke_archive.mjs` cobrem o núcleo e os contextos; `smoke_fase4.mjs`, `smoke_backlog.mjs` e `smoke_foco.mjs` cobrem o motor narrativo (perfis, templates, ganchos, manuscrito, elenco, validação, clone com tradução, foco).

## Migrar da Harness Machine

`scripts/migrate_from_harness_machine.mjs` lê o Neo4j da Harness Machine e gera um obliterate-archive com os projetos (padrão: Bronze Age e Harry Potter) e um projeto **"Biblioteca de estruturas"** com os 10 templates e seus beats; com `--import <url>` importa e confere a paridade (schema, nós, arestas, âncoras, outline, fatos, cobertura, ganchos, `state_at`, `writing_context`). Detalhes no [guia](docs/narrative/guia_historias.md#8-migração-do-harness-machine).

```bash
node scripts/migrate_from_harness_machine.mjs --import http://127.0.0.1:8180
```

## Exportar e importar (backup e migração)

Um projeto, ou tudo, vira um único JSON versionado que outra instância (ou outra plataforma) consegue ler.

| Onde | Exportar | Importar |
|---|---|---|
| Interface | botão **Exportar** no painel do projeto; **Exportar tudo** na tela inicial | botão **Importar…** na tela inicial, na tela do contexto e no projeto |
| REST | `GET /api/projects/{pid}/export` (um projeto) · `GET /api/export` (tudo) | `POST /api/import` (multipart, campo `file`, ou o JSON no corpo) |
| MCP | `project_export` (`projectId` ou `all=true`) | `project_import` (`archive` = texto do JSON) |

Importar sempre cria projetos **novos** (ids novos, todas as referências internas remapeadas: arestas, âncoras de tempo, fatos, `revealAt`, atributos `node_ref`, referência de estilo). Se o nome já existir, ganha um sufixo ` (2)`; `?name=` dá outro nome a um arquivo de um projeto só. Contextos personalizados que não existem na instância são recriados a partir do arquivo (`recreateContexts`, padrão `true`); se já houver um contexto com o mesmo id ou o mesmo nome, ele é reaproveitado sem alteração. Formato ou versão inválidos, ou referências quebradas, falham com a mensagem do problema e nada é gravado (o grafo é escrito numa transação só).

No MCP, `project_export` devolve um resumo (contagens e `downloadUrl`); o JSON vem junto só se tiver até ~200 KB (`inline=false` desliga). Arquivos maiores: baixar do `downloadUrl`.

Formato (`version` 1):

```json
{
  "format": "obliterate-archive", "version": 1, "exportedAt": "2026-10-21T12:00:00Z", "generator": "obliterate-harness",
  "scope": "project | all",
  "contexts": [
    { "id": "corporate", "kind": "builtin", "name": "Corporativo" },
    { "id": "ctx_…", "kind": "custom", "name": "…", "description": "…", "icon": "💳", "locale": "pt", "baseContext": "corporate",
      "instructions": "…", "spaceLabels": {}, "tabs": [], "definition": { "…mesmo formato de um projeto…" } }
  ],
  "projects": [{
    "id": "p_…", "name": "…", "description": "…", "context": "ctx_…", "locale": "pt", "profile": "corporate", "createdAt": "…",
    "settings": { "requireReviewForLlm": false, "styleSample": null, "styleReference": null },
    "schema": { "nodeTypes": [{ "id": "nt_…", "name": "…", "base": "Agent", "attributes": [{ "key": "…", "dataType": "node_ref", "refType": "…" }] }],
                "relTypes": [{ "id": "rt_…", "name": "…", "base": "SOCIAL", "allowed": [{ "from": "@Agent", "to": "Equipe" }] }],
                "objectTypes": [] },
    "nodes": [{ "id": "n_…", "typeId": "nt_…", "type": "…", "base": "Agent", "space": "…", "name": "…", "summary": "…", "tags": [],
                "criticality": "normal", "attributes": {}, "refs": { "dono": ["n_…"] }, "attributeCriticality": {}, "order": 1, "version": 3, "extra": {} }],
    "edges": [{ "id": "e_…", "kind": "rel | structure | attr", "relTypeId": "rt_…", "relType": "…", "from": "n_…", "to": "n_…",
                "attrKey": null, "description": "…", "criticality": "critical", "tags": [], "fromUnit": "n_…", "toUnit": "n_…", "version": 1 }],
    "facts": [{ "id": "f_…", "text": "…", "rigidity": "law", "criticality": "foundational", "originKind": "manual | node | attr | edge",
                "originId": "…", "about": ["n_…"], "revealAt": "n_…" }],
    "directives": [{ "id": "dr_…", "text": "…", "when": ["context"], "bases": [], "types": [], "priority": 0, "enabled": true }],
    "counts": { "nodes": 0, "edges": 0, "facts": 0, "directives": 0 }
  }]
}
```

- Ids no arquivo são os originais. Os atributos são JSON comum; atributos `node_ref` aparecem em `refs` (visão de leitura) e são reconstruídos a partir das arestas `kind: "attr"`.
- Fatos espelhados (`originKind` ≠ `manual`) vêm da criticidade do nó/atributo/aresta de origem e seguem o novo id dela.
- `extra` guarda propriedades que esta versão não nomeia, para nada se perder na ida e volta.
- `space` vai como está gravado; no import, os nomes antigos (`world`, `characters`) viram os atuais (`knowledge`, `actors`).
- Projetos narrativos levam a estrutura (arestas `structure`, `order`), as âncoras `fromUnit`/`toUnit`, `revealAt` e o estilo. A relação de sistema `CONTAINS` não é listada no schema: o import a recria nos projetos narrativos.
- Não vão no arquivo: changesets, auditoria, sessões, rascunhos de texto e a biblioteca de templates próprios.

## Conectar a LLM

```bash
claude mcp add --transport http obliterate http://localhost:8180/mcp
```

## Origem

O projeto nasceu como spin-off da Harness Machine, uma plataforma de roteiro e construção de mundo. A camada narrativa foi removida em 21/10/2026 para deixar o núcleo neutro e voltou em 23/10/2026 como módulo do contexto "Histórias e mundos", para que as histórias da Harness Machine passem a viver aqui (uma plataforma só).

## Notas de manutenção deste arquivo

- Criado em 19/10/2026; atualizado em 19/10/2026 com a conclusão da fase 0 e com os contextos (tela inicial, contexto corporativo, contextos criados por pessoas e LLMs).
- 21/10/2026: removida a camada narrativa; espaços renomeados; prompts MCP genéricos; migração automática dos grafos antigos.
- 21/10/2026: exportar/importar (obliterate-archive) e renomear projeto.
- 23/10/2026: motor narrativo de volta como módulo do contexto narrativo (só nele); migração da Harness Machine; guia e schema em `docs/narrative/`.
