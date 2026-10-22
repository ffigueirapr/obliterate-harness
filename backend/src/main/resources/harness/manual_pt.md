# Manual de operação — Obliterate Harness

Você está operando um "cérebro" em grafo que pessoas e LLMs moldam para qualquer domínio: o conhecimento de uma empresa,
uma área de produto, uma base de pesquisa, o que o usuário definir. O grafo é a fonte da verdade. Seu trabalho é ler dele
o contexto mínimo necessário, responder e gravar com coerência, e registrar de volta o que aprender. Assim nada depende da
sua janela de contexto.

**Idioma:** cada projeto tem um `locale` (en ou pt), mostrado no briefing. Escreva conteúdo, nomes e resumos nesse
idioma. As respostas das tools também vêm nele.

## Contextos

A plataforma roda vários **contextos** (domínios). Cada contexto define os tipos de nó, relações, regras e instruções
que os projetos dele recebem:

- `corporate` (embutido) — o cérebro interno de uma empresa: equipes, pessoas, clientes e quem os atende, contratos,
  pricing, produtos habilitados, regras de negócio, integrações, documentos (com link para o detalhe) e ida a mercado;
- contextos criados por pessoas ou por LLMs (`context_create`), do zero ou a partir de outro contexto.

Para achar onde trabalhar: `context_list` → `project_list(contextId)` → `session_begin`. O briefing traz as instruções
do contexto; siga-as.

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
  organização), `Place`, `Object` (produto, sistema, contrato), `Ability` (capacidade), `Concept` (regra, política,
  ideia), `Event` (decisão, reunião, incidente), `Media` (documento, arquivo), `Note`.
- **Atributos tipados:** int, decimal, bool, string_short (≤255), text_long, enum, json, node_ref (vira aresta), list,
  composite (objeto de um ObjectType), media. O papel (role) `link` marca o atributo que guarda a URL do documento de origem.
- **Relações** têm tipo definido pelo usuário, com pares origem→destino permitidos (`"Equipe"`, `"@Agent"` ou `"*"`).
  Se uma relação não for permitida, o erro lista as relações que permitem aquele par.
- **Espaços** agrupam o grafo em abas: `actors` (base Agent por padrão), `knowledge` (todo o resto) e `analysis`.
  Cada contexto dá o próprio nome a eles.

## Criticidade, fatos e regras

- `criticality` em nó, atributo (`attributeCriticality`) ou aresta: `normal`, `critical`, `foundational`.
- Tudo que é critical/foundational vira um **Fato** indexado, sempre presente nos briefings.
- Rigidez: `law` (nunca violar), `norm` (o padrão; desvio exige motivo explícito), `flavor` (informativo).
  Padrão: foundational → law, critical → norm.
- Regras avulsas: `create_fact` com os nós de que trata (`about`).
- **Diretivas:** instruções curtas com gatilho (session, context, write, validate, always), vindas do preset do contexto ou
  do projeto. `upsert_directive` com `id` edita; `definition.enabled: false` desliga sem apagar. Elas chegam no campo
  `guidance`; siga-as.

## Copiar e traduzir projetos

- `project_clone(projectId, newName, includeNodes, includeFacts, includeDirectives, translateTo)` copia o projeto com ids
  novos e devolve o mapa antigo→novo.
- `project_localize(projectId, locale)` converte para en ou pt o schema que veio do preset do contexto (tipos, chaves de
  atributo, enums, relações) e migra os dados. O conteúdo não é traduzido.

## Formato das operações

Veja o exemplo no manual em inglês (`harness://manual`); os campos são os mesmos. Lote atômico, a não ser com
`mode: best_effort`; `dryRun: true` lista todos os erros sem gravar; `expectedVersion` obrigatório em update/delete (cada
mudança devolve a `version` nova); `null` num atributo o remove; voltar a criticidade para `normal` remove o fato
espelhado; com `sessionId`, os `$apelidos` valem nos lotes seguintes da sessão.
