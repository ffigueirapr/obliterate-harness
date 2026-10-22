import type { Core, NodeSingular } from 'cytoscape';
import { t } from '../i18n';

export type LayoutKind = 'organico' | 'hierarquico' | 'aneis' | 'grade';

export const LAYOUT_KINDS: LayoutKind[] = ['organico', 'hierarquico', 'aneis', 'grade'];

export const layoutLabel = (k: LayoutKind) => ({
  organico: t('Organic', 'Orgânico'),
  hierarquico: t('Hierarchy', 'Hierárquico'),
  aneis: t('Rings', 'Anéis'),
  grade: t('Grid', 'Grade'),
})[k];

export const layoutHint = (k: LayoutKind) => ({
  organico: t('Groups what is linked together', 'Agrupa o que é ligado entre si'),
  hierarquico: t('Tree from the most connected node (or the focused one)', 'Árvore a partir do nó mais conectado (ou do nó em foco)'),
  aneis: t('Rings by distance from the focused node (or by number of links)', 'Anéis por distância do nó em foco (ou por número de conexões)'),
  grade: t('Grid sorted by type', 'Grade ordenada por tipo'),
})[k];

type Pos = { x: number; y: number };

/**
 * Separa nós cujas caixas (forma + rótulo) se sobrepõem, mexendo o mínimo possível: preserva o arranjo atual.
 * Usa uma grade espacial para não comparar todos com todos.
 *
 * @param movable só estes podem se mover (os outros ficam fixos); omitido = todos
 * @returns posições novas de quem precisou mudar
 */
export function removeOverlaps(cy: Core, movable?: Set<string>, padding = 16, maxIterations = 400): Record<string, Pos> {
  const items = cy.nodes().map((n) => {
    const bb = n.boundingBox({ includeLabels: true, includeOverlays: false } as never);
    const p = n.position();
    // a largura do rótulo só é medida depois de desenhado; antes disso, estima pelo texto (máx. 130px, ~6.5px por letra)
    const label = String(n.data('label') ?? '');
    const w = Math.max(bb.w, Math.min(130, label.length * 6.5) + 4);
    return {
      id: n.id(),
      x: p.x, y: p.y,
      // centro da caixa pode diferir da posição (rótulo fica embaixo do nó)
      ox: 0, oy: (bb.y1 + bb.y2) / 2 - p.y,
      hw: w / 2 + padding / 2, hh: bb.h / 2 + padding / 2,
      mov: !movable || movable.has(n.id()),
    };
  });
  if (items.length < 2) return {};
  const cell = Math.max(40, ...items.map((i) => Math.max(i.hw, i.hh) * 2));
  const start = new Map(items.map((i) => [i.id, { x: i.x, y: i.y }]));

  for (let iter = 0; iter < maxIterations; iter++) {
    const grid = new Map<string, number[]>();
    items.forEach((it, idx) => {
      const k = `${Math.floor((it.x + it.ox) / cell)},${Math.floor((it.y + it.oy) / cell)}`;
      const list = grid.get(k);
      if (list) list.push(idx); else grid.set(k, [idx]);
    });
    let moved = false;
    items.forEach((a, ia) => {
      const gx = Math.floor((a.x + a.ox) / cell), gy = Math.floor((a.y + a.oy) / cell);
      for (let dx = -1; dx <= 1; dx++) {
        for (let dy = -1; dy <= 1; dy++) {
          for (const ib of grid.get(`${gx + dx},${gy + dy}`) ?? []) {
            if (ib <= ia) continue;
            const b = items[ib];
            if (!a.mov && !b.mov) continue;
            const ddx = (b.x + b.ox) - (a.x + a.ox);
            const ddy = (b.y + b.oy) - (a.y + a.oy);
            const overX = a.hw + b.hw - Math.abs(ddx);
            const overY = a.hh + b.hh - Math.abs(ddy);
            if (overX <= 0 || overY <= 0) continue;
            moved = true;
            // empurra pelo eixo de menor sobreposição; empate/coincidência resolvido por índice
            const alongX = overX < overY;
            const sign = (alongX ? ddx : ddy) === 0 ? (ia % 2 === 0 ? 1 : -1) : Math.sign(alongX ? ddx : ddy);
            const amount = (alongX ? overX : overY) + 0.5;
            const shareA = a.mov && b.mov ? 0.5 : a.mov ? 1 : 0;
            const shareB = a.mov && b.mov ? 0.5 : b.mov ? 1 : 0;
            if (alongX) {
              a.x -= sign * amount * shareA;
              b.x += sign * amount * shareB;
            } else {
              a.y -= sign * amount * shareA;
              b.y += sign * amount * shareB;
            }
          }
        }
      }
    });
    if (!moved) break;
  }
  const out: Record<string, Pos> = {};
  for (const it of items) {
    const s = start.get(it.id)!;
    if (Math.abs(s.x - it.x) > 0.5 || Math.abs(s.y - it.y) > 0.5) out[it.id] = { x: it.x, y: it.y };
  }
  return out;
}

/**
 * Aplica posições. Sem animação, aplica na hora (não depende de a página estar sendo desenhada,
 * já que animações do Cytoscape só avançam com a aba visível).
 */
export function applyPositions(cy: Core, positions: Record<string, Pos>, animate = false): Promise<void> {
  if (Object.keys(positions).length === 0) return Promise.resolve();
  if (!animate || document.hidden) {
    cy.batch(() => cy.nodes().forEach((n) => { const p = positions[n.id()]; if (p) n.position(p); }));
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    const layout = cy.layout({
      name: 'preset', fit: false, animate, animationDuration: 280,
      positions: (n: NodeSingular) => positions[n.id()] ?? n.position(),
    } as never);
    layout.one('layoutstop', () => resolve());
    layout.run();
  });
}

/** Desencavala tudo (ou só os nós móveis) mantendo o arranjo. */
export async function untangle(cy: Core, movable?: Set<string>, animate = false) {
  await applyPositions(cy, removeOverlaps(cy, movable), animate);
}

/**
 * Posição inicial de nós novos: perto dos vizinhos já posicionados (em vez de todos empilhados no mesmo ponto),
 * ou ao lado do desenho atual quando não têm vizinhos.
 */
export function seedPositions(cy: Core, fresh: Set<string>) {
  const placed = cy.nodes().filter((n) => !fresh.has(n.id()));
  const bb = placed.nonempty() ? placed.boundingBox({}) : { x1: 0, y1: 0, x2: 0, y2: 0, w: 0, h: 0 };
  let orphan = 0;
  cy.nodes().filter((n) => fresh.has(n.id())).forEach((n) => {
    const anchors = n.neighborhood('node').nodes().filter((m) => !fresh.has(m.id()));
    const angle = Math.random() * Math.PI * 2;
    if (anchors.nonempty()) {
      const c = { x: 0, y: 0 };
      anchors.forEach((m) => {
        const p = (m as unknown as NodeSingular).position();
        c.x += p.x;
        c.y += p.y;
      });
      const r = 110 + Math.random() * 50;
      n.position({ x: c.x / anchors.length + Math.cos(angle) * r, y: c.y / anchors.length + Math.sin(angle) * r });
    } else {
      // sem vizinho posicionado: coluna à direita do desenho
      n.position({ x: bb.x2 + 160 + Math.floor(orphan / 8) * 170, y: bb.y1 + (orphan % 8) * 90 });
      orphan++;
    }
  });
}

/** Roda um layout completo e, ao final, remove sobreposições restantes. */
export function runLayout(cy: Core, kind: LayoutKind, opts: { distance?: Record<string, number>; centerId?: string } = {}): Promise<void> {
  const common = { animate: !document.hidden, animationDuration: 450, fit: true, padding: 40, nodeDimensionsIncludeLabels: true };
  let options: Record<string, unknown>;
  switch (kind) {
    case 'hierarquico': {
      // without a focus, start from the most connected node
      let roots: string[] = opts.centerId ? [opts.centerId] : [cy.nodes().max((n) => n.degree(false)).ele.id()];
      roots = roots.map((id) => '#' + CSS.escape(id));
      options = { name: 'breadthfirst', directed: false, roots: roots.join(','), spacingFactor: 1.3, avoidOverlap: true, ...common };
      break;
    }
    case 'aneis': {
      const d = opts.distance;
      const maxD = d ? Math.max(0, ...Object.values(d)) : 0;
      options = {
        name: 'concentric', minNodeSpacing: 30, avoidOverlap: true, startAngle: -Math.PI / 2, ...common,
        concentric: (n: NodeSingular) => (d ? maxD - (d[n.id()] ?? maxD) : n.degree(false)),
        levelWidth: () => (d ? 1 : Math.max(1, Math.ceil(cy.nodes().max((n) => n.degree(false)).value / 4))),
      };
      break;
    }
    case 'grade':
      options = {
        name: 'grid', avoidOverlap: true, avoidOverlapPadding: 20, condense: false, ...common,
        sort: (a: NodeSingular, b: NodeSingular) => String(a.data('base') + a.data('label')).localeCompare(String(b.data('base') + b.data('label'))),
      };
      break;
    default:
      options = {
        name: 'fcose', quality: 'proof', randomize: true, nodeRepulsion: 14000, idealEdgeLength: 130, nodeSeparation: 120,
        packComponents: true, ...common,
      };
  }
  return new Promise((resolve) => {
    const layout = cy.layout(options as never);
    layout.one('layoutstop', async () => {
      await untangle(cy);
      cy.fit(undefined, 40);
      resolve();
    });
    layout.run();
  });
}
