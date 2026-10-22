# Obliterate Harness

> Spin-off da Harness Machine: um "cérebro" em grafo, parametrizável para qualquer domínio, operado por pessoas (interface) e por LLMs (MCP).

**Status:** fase 0 concluída + **contextos** (primeira entrega da plataforma multi-domínio).

## Contextos

A tela inicial é a escolha do **contexto**: o domínio que a plataforma vai rodar. Um contexto define quais tipos de nó existem, como eles se relacionam, quais regras são vinculantes e como a IA deve se comportar. Os projetos vivem dentro de um contexto, e qualquer Claude conectado via MCP pode usá-los.

| Contexto | O que traz |
|---|---|
| 📖 **Histórias e mundos** (embutido) | Tudo o que a Harness Machine fazia: perfis livro, mangá, filme…, unidades, ganchos, templates, manuscrito |
| 🏢 **Cérebro corporativo** (embutido) | Equipe, Pessoa, Cliente, Parceiro, Concorrente, Produto, Contrato, Pricing, Oportunidade, Regra de negócio, Integração, Playbook, Documento (com link), Inteligência de mercado, Decisão, Termo do glossário; relações como ATENDE (BizDev → cliente), TEM_PRODUTO_HABILITADO, REGIDO_POR, PRECIFICADO_POR, DOCUMENTADO_EM; diretivas de "responder só com fonte" e "regras são vinculantes" |
| 🧩 **Contextos seus** | Criados na tela inicial (em branco ou a partir de outro contexto) ou por um Claude via MCP (`context_create`) |

Como funciona por baixo:
- Os contextos embutidos vêm de YAML (`harness/contexts/*.yaml`) e apontam para perfis (`harness/profiles/`).
- Um contexto criado guarda sua definição num **projeto-molde** escondido: tipos, relações, atributos, regras (fatos) e diretivas. "Editar definição" abre esse molde no editor de sempre, e um Claude o edita com `propose_changes`.
- Um projeto novo no contexto nasce com uma cópia da definição (só o schema, as regras e as diretivas; nós de exemplo do molde não vão junto).
- Novo papel de atributo: `link`, uma URL para o documento com o detalhe, aberta com um clique na interface.

MCP: `context_list`, `context_get`, `context_create`, `context_update`; `project_list(contextId)`; `project_create(contextId, profile)`. O `session_begin` traz o contexto e as instruções dele no briefing.

Projeto de demonstração: `node scripts/seed_demo_corporativo.mjs` cria "Demo — Pagamentos Exemplo S.A." (empresa fictícia) no contexto corporativo.

## A ideia em uma frase

Em vez de uma plataforma de roteiro, uma plataforma **em branco**: o usuário define os tipos de nó e de relação e **como eles se comportam** (via *traits*), empacota tudo num **pacote de domínio** e conecta LLMs via MCP para consultar e alimentar o grafo de forma estruturada. Histórias viram só um pacote; uma empresa (áreas, capacidades, processos, sistemas, documentos) vira outro.

## Critério de sucesso

Instalar um **Pacote Narrativo** e reproduzir o projeto Bronze Age (e os smoke tests da Harness Machine) sem nenhuma linha de código narrativo no núcleo.

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

Roda **ao lado** da Harness Machine, sem conflito: projeto Compose, volumes e portas próprios.

| Serviço | Obliterate Harness | (Harness Machine) |
|---|---|---|
| Interface | http://localhost:5273 | 5173 |
| API REST | http://localhost:8180/api | 8080 |
| MCP | http://localhost:8180/mcp | 8080 |
| Neo4j Browser | http://localhost:7574 (bolt 7787) | 7474 / 7687 |
| Postgres | localhost:5532 | 5432 |

Tudo fica preso em `127.0.0.1`. O Neo4j daqui usa menos memória (heap 2G, pagecache 1G) para conviver com a outra stack.

Testes de ponta a ponta (herdados; o padrão já aponta para a porta 8180):

```bash
node scripts/smoke.mjs
```

```bash
node scripts/smoke_fase4.mjs
```

```bash
node scripts/smoke_foco.mjs
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
                "criticality": "normal", "attributes": {}, "refs": { "dono": ["n_…"] }, "attributeCriticality": {}, "version": 3, "extra": {} }],
    "edges": [{ "id": "e_…", "kind": "rel | structure | attr", "relTypeId": "rt_…", "relType": "…", "from": "n_…", "to": "n_…",
                "attrKey": null, "description": "…", "criticality": "critical", "fromUnit": "n_…", "toUnit": "n_…", "version": 1 }],
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
- A relação de sistema `CONTAINS` não é listada no schema (todo projeto tem). Não vão no arquivo: changesets, auditoria, sessões, histórico de rascunhos de texto e a biblioteca de templates.

## Conectar a LLM

```bash
claude mcp add --transport http obliterate http://localhost:8180/mcp
```

## O que mudou em relação à Harness Machine (fase 0)

- Pacote Java `com.harnessmachine` → `com.obliterateharness`; artefato `obliterate-harness`.
- Nome do servidor MCP e da aplicação: `obliterate-harness`; marca "Obliterate Harness" na interface, nos manuais e nas instruções do MCP.
- Portas, projeto Compose e volumes próprios.
- Mantidos de propósito até a fase 3: o esquema `harness://` dos resources, os nomes das classes (`HarnessTools`, `NarrativeService`…) e todo o comportamento narrativo.

## Notas de manutenção deste arquivo

- Criado em 19/10/2026; atualizado em 19/10/2026 com a conclusão da fase 0 e com os contextos (tela inicial, contexto corporativo, contextos criados por pessoas e LLMs); atualizado em 21/10/2026 com exportar/importar.
