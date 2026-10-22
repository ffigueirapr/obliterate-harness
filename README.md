# Obliterate Harness

> Um "cérebro" em grafo, aberto a qualquer domínio: pessoas (pela interface) e LLMs (pelo MCP) definem os tipos de nó, as relações, as regras e o comportamento da IA, e depois consultam e alimentam o grafo de forma estruturada.

**Status:** plataforma multi-domínio. A camada narrativa da POC original (livros, mangá, templates de história, manuscrito) foi removida; o domínio agora é sempre definido por um contexto.

## Contextos

A tela inicial é a escolha do **contexto**: o domínio que a plataforma vai rodar. Um contexto define quais tipos de nó existem, como eles se relacionam, quais regras são vinculantes e como a IA deve se comportar. Os projetos vivem dentro de um contexto, e qualquer Claude conectado via MCP pode usá-los.

| Contexto | O que traz |
|---|---|
| 🏢 **Cérebro corporativo** (embutido) | Equipe, Pessoa, Cliente, Parceiro, Concorrente, Produto, Contrato, Pricing, Oportunidade, Regra de negócio, Integração, Playbook, Documento (com link), Inteligência de mercado, Decisão, Termo do glossário; relações como ATENDE (BizDev → cliente), TEM_PRODUTO_HABILITADO, REGIDO_POR, PRECIFICADO_POR, DOCUMENTADO_EM; diretivas de "responder só com fonte" e "regras são vinculantes" |
| 🧩 **Contextos seus** | Criados na tela inicial (em branco ou a partir de outro contexto) ou por um Claude via MCP (`context_create`) |

Como funciona por baixo:
- Os contextos embutidos vêm de YAML (`harness/contexts/*.yaml`) e apontam para presets de schema (`harness/profiles/`).
- Um contexto criado guarda sua definição num **projeto-molde** escondido: tipos, relações, atributos, regras (fatos) e diretivas. "Editar definição" abre esse molde no editor de sempre, e um Claude o edita com `propose_changes`.
- Um projeto novo no contexto nasce com uma cópia da definição (só o schema, as regras e as diretivas; nós de exemplo do molde não vão junto).
- Papel de atributo `link`: uma URL para o documento com o detalhe, aberta com um clique na interface.
- O grafo se divide em três espaços (abas): `actors` (pessoas, equipes, organizações), `knowledge` (todo o resto) e `analysis`. Cada contexto dá o próprio nome a eles.

MCP: `context_list`, `context_get`, `context_create`, `context_update`; `project_list(contextId)`; `project_create(contextId, preset)`. O `session_begin` traz o contexto e as instruções dele no briefing. Prompts prontos: `design_context`, `ingest_document` e `answer_from_graph`.

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

Na primeira subida depois de atualizar, uma migração automática converte grafos antigos: remove a estrutura narrativa (relação `CONTAINS`, âncoras de tempo, segredos, estilo), transforma nós dos tipos-base removidos em comuns (`Unit` → `Note`, `Device` → `Concept`) e renomeia os espaços (`world` → `knowledge`, `characters` → `actors`). Nenhum nó é apagado.

Testes de ponta a ponta (o padrão aponta para a porta 8180):

```bash
node scripts/smoke.mjs
```

```bash
node scripts/smoke_foco.mjs
```

```bash
node scripts/smoke_contexts.mjs
```

## Conectar a LLM

```bash
claude mcp add --transport http obliterate http://localhost:8180/mcp
```

## Origem

O projeto nasceu como spin-off da Harness Machine, uma plataforma de roteiro e construção de mundo. A camada narrativa (unidades, manuscrito, templates de estrutura, ganchos, segredos, perfis de mídia) foi removida em 21/10/2026; o núcleo (grafo tipado, changesets auditados, fatos e diretivas, contextos, MCP) ficou.

## Notas de manutenção deste arquivo

- Criado em 19/10/2026; atualizado em 19/10/2026 com a conclusão da fase 0 e com os contextos (tela inicial, contexto corporativo, contextos criados por pessoas e LLMs).
- 21/10/2026: removida a camada narrativa; espaços renomeados; prompts MCP genéricos; migração automática dos grafos antigos.
