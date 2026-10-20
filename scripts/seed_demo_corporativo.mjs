// Creates a demo project in the corporate context (Portuguese, fictitious company) to explore the UI and the MCP.
// Usage: node scripts/seed_demo_corporativo.mjs [http://127.0.0.1:8180]
const BASE = process.argv.find((a) => a.startsWith('http')) ?? 'http://127.0.0.1:8180';

async function call(method, path, body) {
  const res = await fetch(BASE + path, { method, headers: { 'content-type': 'application/json', 'x-lang': 'pt' }, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status}: ${data?.detail ?? text}`);
  return data;
}

const p = await call('POST', '/api/contexts/corporate/projects', {
  name: 'Demo — Pagamentos Exemplo S.A.', locale: 'pt',
  description: 'Empresa fictícia de pagamentos, para demonstrar o contexto corporativo.',
});

const n = (ref, type, name, summary, attributes = {}, extra = {}) => ({ op: 'create_node', ref, type, name, summary, attributes, ...extra });
const e = (relType, from, to, description) => ({ op: 'create_edge', relType, from, to, ...(description ? { description } : {}) });

const ops = [
  n('$bizdev', 'Equipe', 'BizDev', 'Abre e cuida das contas estratégicas.', { area: 'bizdev', canal: '#bizdev' }),
  n('$cs', 'Equipe', 'Customer Success', 'Onboarding e saúde das contas.', { area: 'customer_success', canal: '#cs' }),
  n('$eng', 'Equipe', 'Engenharia de Integrações', 'Dona das APIs e integrações com clientes.', { area: 'engenharia' }),
  n('$ana', 'Pessoa', 'Ana Souza', 'Gerente de BizDev, dona da conta Loja Aurora.', { cargo: 'Gerente de BizDev', vinculo: 'colaborador', email: 'ana@exemplo.com' }),
  n('$bruno', 'Pessoa', 'Bruno Lima', 'CSM das contas de varejo.', { cargo: 'Customer Success Manager', vinculo: 'colaborador' }),
  n('$carla', 'Pessoa', 'Carla Mendes', 'Contato financeiro na Loja Aurora.', { cargo: 'Gerente financeira', vinculo: 'contato_cliente' }),
  n('$aurora', 'Cliente', 'Loja Aurora', 'Varejista com 120 lojas; cliente estratégico desde 2024.',
    { segmento: 'Varejo', tier: 'estrategico', status: 'ativo', regiao: 'Sudeste',
      condicoes_especiais: 'Isenção da tarifa de estorno até dez/2026. Liquidação sempre em D+2.', link_crm: 'https://crm.exemplo.com/contas/aurora' }),
  n('$gateway', 'Produto', 'Gateway de pagamentos', 'Captura de cartão e Pix.', { categoria: 'Pagamentos', status: 'disponivel',
    o_que_faz: 'Captura pagamentos com cartão e Pix, com split e conciliação.' }),
  n('$antifraude', 'Produto', 'Antifraude', 'Análise de risco por transação.', { categoria: 'Risco', status: 'disponivel' }),
  n('$link', 'Produto', 'Link de pagamento', 'Cobrança por link.', { categoria: 'Pagamentos', status: 'beta' }),
  n('$contrato', 'Contrato', 'Contrato Loja Aurora 2025', 'Contrato principal de processamento.',
    { numero: 'CT-2025-0142', status: 'vigente', inicio: '2025-01-01', fim: '2027-12-31', valor: 'R$ 1,2 mi/ano (estimado)',
      clausulas_chave: 'Exclusividade em cartão nas lojas físicas. Multa de 2% por indisponibilidade acima do SLA.',
      sla: '99,9% de disponibilidade mensal.', link_documento: 'https://drive.exemplo.com/contratos/aurora-2025.pdf' }),
  n('$pricing', 'Pricing', 'Tabela negociada Loja Aurora', 'MDR negociado por bandeira.',
    { modelo: 'por_transacao', moeda: 'BRL', precos: 'Crédito à vista 1,49%; parcelado 1,99%; débito 0,79%; Pix 0,49%.',
      politica_de_desconto: 'Revisão anual; desconto adicional só com aprovação da diretoria comercial.', vigencia_inicio: '2025-01-01' }),
  n('$regra', 'Regra de negócio', 'Liquidação da Loja Aurora em D+2', 'Prazo contratual de liquidação.',
    { regra: 'Pagamentos da Loja Aurora liquidam em D+2, nunca antes. Antecipação só com aditivo assinado.', escopo: 'cliente_especifico',
      status: 'vigente', motivo: 'Negociado em troca de MDR menor.' }),
  n('$regra2', 'Regra de negócio', 'Estorno acima de R$ 5 mil exige dupla aprovação', 'Vale para todos os clientes.',
    { regra: 'Estornos acima de R$ 5.000 exigem aprovação do CSM e do time de risco.', escopo: 'todos_os_clientes', status: 'vigente' }),
  n('$integ', 'Integração', 'API de pagamentos — Loja Aurora', 'Integração REST com o ERP da Aurora.',
    { metodo: 'api_rest', status: 'em_producao', regras_tecnicas: 'Idempotência obrigatória por pedido; webhooks com retry em 5 tentativas.',
      link_documentacao: 'https://docs.exemplo.com/api/pagamentos' }),
  n('$doc', 'Documento', 'Playbook de onboarding de varejo', 'Passo a passo de ativação de redes varejistas.',
    { link: 'https://drive.exemplo.com/playbooks/onboarding-varejo', tipo_de_documento: 'playbook', confidencialidade: 'interno' }),
  n('$insight', 'Inteligência de mercado', 'Varejo médio prefere Pix com split', 'Aprendizado de campanha Q2.',
    { tipo: 'proposta_de_valor', insight: 'Redes de varejo médio valorizam split automático no Pix mais que taxa menor no crédito.' }),
  n('$conc', 'Concorrente', 'PagaJá', 'Concorrente direto em varejo.', { posicionamento: 'Preço agressivo no débito.' }),
  e('MEMBRO_DE', '$ana', '$bizdev'), e('MEMBRO_DE', '$bruno', '$cs'), e('LIDERA', '$ana', '$bizdev'),
  e('ATENDE', '$ana', '$aurora', 'BizDev dona da conta'), e('ATENDE', '$bruno', '$aurora', 'CSM'),
  e('CONTATO_EM', '$carla', '$aurora'),
  e('TEM_CONTRATO', '$aurora', '$contrato'),
  e('TEM_PRODUTO_HABILITADO', '$aurora', '$gateway', 'Desde jan/2025, cartão e Pix'),
  e('TEM_PRODUTO_HABILITADO', '$aurora', '$antifraude', 'Desde mar/2025'),
  e('COBRE', '$contrato', '$gateway'), e('COBRE', '$contrato', '$integ'),
  e('PRECIFICADO_POR', '$contrato', '$pricing'),
  e('REGIDO_POR', '$aurora', '$regra'), e('REGIDO_POR', '$aurora', '$regra2'),
  e('INTEGRA_VIA', '$aurora', '$integ'), e('CONECTA_A', '$integ', '$gateway'),
  e('DOCUMENTADO_EM', '$aurora', '$doc'),
  e('E_DONO_DE', '$eng', '$integ'), e('E_DONO_DE', '$bizdev', '$pricing'),
  e('TRATA_DE', '$insight', '$gateway'), e('CONCORRE_COM', '$conc', '$gateway'),
];
const r = await call('POST', `/api/projects/${p.id}/changesets`, { title: 'Dados de demonstração', operations: ops });
console.log(`projeto ${p.id} · ${r.status} · ${ops.length} operações`);
