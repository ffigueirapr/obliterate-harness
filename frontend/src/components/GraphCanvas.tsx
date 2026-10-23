import { useEffect, useImperativeHandle, useRef, forwardRef } from 'react';
import { t } from '../i18n';
import cytoscape, { type Core, type ElementDefinition } from 'cytoscape';
import fcose from 'cytoscape-fcose';
import lasso from 'cytoscape-lasso';
import type { GEdge, GNode, IndirectLink, NodeTypeDef } from '../types';
import { runLayout, seedPositions, untangle, type LayoutKind } from './graphLayout';

cytoscape.use(fcose);
cytoscape.use(lasso);

export type SelectMode = 'pan' | 'box' | 'lasso';

export const BASE_COLOR: Record<string, string> = {
  Agent: '#e8590c', Place: '#2f9e44', Object: '#1971c2', Ability: '#9c36b5', Concept: '#0c8599',
  Event: '#c2255c', Unit: '#5c636a', Device: '#f08c00', Media: '#74b816', Note: '#868e96',
};
const BASE_SHAPE: Record<string, string> = {
  Agent: 'ellipse', Place: 'round-hexagon', Object: 'round-diamond', Ability: 'star', Concept: 'round-octagon',
  Event: 'round-triangle', Unit: 'round-rectangle', Device: 'vee', Media: 'round-tag', Note: 'rectangle',
};

export interface GraphCanvasHandle {
  fit: () => void;
  relayout: (kind?: LayoutKind) => void;
  untangle: () => void;
  selectIds: (ids: string[]) => void;
  focus: (id: string) => void;
}

export interface GraphCanvasProps {
  projectId: string;
  nodes: GNode[];
  edges: GEdge[];
  indirect: IndirectLink[];
  contextIds: string[];
  nodeTypes: NodeTypeDef[];
  mode: SelectMode;
  /** Modo foco: distância de cada nó até o centro (0 = centro). */
  distance?: Record<string, number>;
  centerId?: string;
  onSelectionChange: (nodeIds: string[], edgeIds: string[], indirect: IndirectLink | null) => void;
  onNodeDoubleTap?: (id: string) => void;
}

const posKey = (pid: string) => `hm.positions.${pid}`;

function loadPositions(pid: string): Record<string, { x: number; y: number }> {
  try {
    return JSON.parse(localStorage.getItem(posKey(pid)) ?? '{}');
  } catch {
    return {};
  }
}

function savePositions(pid: string, cy: Core) {
  try {
    const all = loadPositions(pid);
    cy.nodes().forEach((n) => { all[n.id()] = n.position(); });
    localStorage.setItem(posKey(pid), JSON.stringify(all));
  } catch {
    /* armazenamento indisponível: só não lembra as posições */
  }
}

function cssVar(name: string) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888';
}

export const GraphCanvas = forwardRef<GraphCanvasHandle, GraphCanvasProps>(function GraphCanvas(props, ref) {
  const container = useRef<HTMLDivElement>(null);
  const cyRef = useRef<Core | null>(null);
  const propsRef = useRef(props);
  propsRef.current = props;
  const needsFit = useRef(true);

  // cria o Cytoscape uma vez por projeto
  useEffect(() => {
    const text = cssVar('--text');
    const edge = cssVar('--edge');
    const accent = cssVar('--accent');
    const gold = cssVar('--gold');
    const warn = cssVar('--warn');
    const canvas = cssVar('--canvas');
    const cy = cytoscape({
      container: container.current,
      // zoom pela roda do mouse: ~20% por clique (o padrão do Cytoscape, 1, dá ~5% e fica lento)
      wheelSensitivity: 4,
      boxSelectionEnabled: true,
      selectionType: 'single',
      style: [
        {
          selector: 'node',
          style: {
            'background-color': 'data(color)', shape: 'data(shape)' as never, label: 'data(label)',
            width: 30, height: 30, color: text, 'font-size': 11, 'text-valign': 'bottom', 'text-margin-y': 4,
            'text-wrap': 'ellipsis', 'text-max-width': '130px', 'text-background-color': canvas, 'text-background-opacity': 0.75,
            'text-background-padding': '1px', 'border-width': 0,
          },
        },
        { selector: 'node[base = "Unit"]', style: { width: 44, height: 26 } },
        { selector: 'node[crit = "critical"]', style: { 'border-width': 3, 'border-color': warn } },
        { selector: 'node[crit = "foundational"]', style: { 'border-width': 4, 'border-color': gold, 'border-style': 'double' } },
        { selector: 'node.context', style: { opacity: 0.55, 'border-width': 2, 'border-style': 'dashed', 'border-color': edge } },
        { selector: 'node.focus-center', style: { 'border-width': 5, 'border-color': accent, 'border-style': 'solid', width: 40, height: 40, 'font-weight': 'bold' } },
        { selector: 'node.far', style: { opacity: 0.7 } },
        { selector: 'node:selected', style: { 'overlay-color': accent, 'overlay-opacity': 0.25, 'overlay-padding': 6 } },
        {
          selector: 'edge',
          style: {
            width: 1.6, 'line-color': edge, 'target-arrow-color': edge, 'target-arrow-shape': 'triangle', 'arrow-scale': 0.8,
            'curve-style': 'bezier', label: 'data(label)', 'font-size': 9, color: edge, 'text-rotation': 'autorotate',
            'text-background-color': canvas, 'text-background-opacity': 0.85, 'text-background-padding': '1px',
          },
        },
        { selector: 'edge[kind = "structure"]', style: { width: 1, 'line-style': 'solid', label: '', opacity: 0.6 } },
        { selector: 'edge[kind = "attr"]', style: { 'line-style': 'dotted' } },
        { selector: 'edge[crit = "critical"]', style: { 'line-color': warn, 'target-arrow-color': warn, width: 2.4 } },
        { selector: 'edge[crit = "foundational"]', style: { 'line-color': gold, 'target-arrow-color': gold, width: 3 } },
        {
          selector: 'edge.indirect',
          style: { 'line-style': 'dashed', 'line-dash-pattern': [6, 4], 'target-arrow-shape': 'none', opacity: 0.6, width: 1.4, 'line-color': accent, color: accent },
        },
        { selector: 'edge:selected', style: { 'line-color': accent, 'target-arrow-color': accent, width: 3 } },
      ],
      layout: { name: 'preset' },
    });
    cyRef.current = cy;
    needsFit.current = true;

    const emit = () => {
      const sel = cy.$(':selected');
      const nodeIds = sel.nodes().map((n) => n.id());
      const edgeEls = sel.edges();
      const real = edgeEls.filter((e) => !e.hasClass('indirect')).map((e) => e.id());
      const ind = edgeEls.filter((e) => e.hasClass('indirect'));
      propsRef.current.onSelectionChange(nodeIds, real, ind.length === 1 ? (ind[0].data('link') as IndirectLink) : null);
    };
    // junta rajadas de select/unselect (seleção em área dispara uma por nó) numa única atualização
    let pending: ReturnType<typeof setTimeout> | undefined;
    const schedule = () => {
      clearTimeout(pending);
      pending = setTimeout(emit, 16);
    };
    cy.on('select unselect', schedule);
    cy.on('dragfree', () => savePositions(propsRef.current.projectId, cy));
    cy.on('dbltap', 'node', (e) => propsRef.current.onNodeDoubleTap?.(e.target.id()));
    // o container muda de tamanho (painéis, janela): mantém o canvas do Cytoscape em sincronia
    const ro = new ResizeObserver(() => cy.resize());
    if (container.current) ro.observe(container.current);

    // botão do meio (roda) pressionado = mover a tela, em qualquer modo; soltar volta ao modo anterior.
    // Captura antes do Cytoscape e do laço para que eles não tratem o clique como seleção.
    const el = container.current;
    let last: { x: number; y: number } | null = null;
    const onPanMove = (e: MouseEvent) => {
      if (!last) return;
      cy.panBy({ x: e.clientX - last.x, y: e.clientY - last.y });
      last = { x: e.clientX, y: e.clientY };
    };
    const endPan = (e: MouseEvent) => {
      if (e.button !== 1 || !last) return;
      last = null;
      if (el) el.style.cursor = '';
      document.removeEventListener('mousemove', onPanMove, true);
      document.removeEventListener('mouseup', endPan, true);
    };
    const startPan = (e: MouseEvent) => {
      if (e.button !== 1) return;
      e.preventDefault(); // evita o autoscroll do Windows
      e.stopPropagation();
      last = { x: e.clientX, y: e.clientY };
      if (el) el.style.cursor = 'grabbing';
      document.addEventListener('mousemove', onPanMove, true);
      document.addEventListener('mouseup', endPan, true);
    };
    const blockAux = (e: MouseEvent) => { if (e.button === 1) e.preventDefault(); };
    el?.addEventListener('mousedown', startPan, true);
    el?.addEventListener('auxclick', blockAux);

    return () => {
      clearTimeout(pending);
      ro.disconnect();
      el?.removeEventListener('mousedown', startPan, true);
      el?.removeEventListener('auxclick', blockAux);
      document.removeEventListener('mousemove', onPanMove, true);
      document.removeEventListener('mouseup', endPan, true);
      cy.destroy();
      cyRef.current = null;
    };
  }, [props.projectId]);

  // modo de seleção
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    const lassoCy = cy as Core & { lassoSelectionEnabled: (b: boolean) => void };
    cy.userPanningEnabled(props.mode === 'pan');
    cy.boxSelectionEnabled(props.mode !== 'lasso');
    lassoCy.lassoSelectionEnabled(props.mode === 'lasso');
  }, [props.mode, props.projectId]);

  // sincroniza elementos sem perder posições
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    const colorOf = new Map(props.nodeTypes.map((t) => [t.name, t.color]));
    const ctx = new Set(props.contextIds);
    const saved = loadPositions(props.projectId);
    const dist = props.distance;
    const els: ElementDefinition[] = [];
    for (const n of props.nodes) {
      const classes = [ctx.has(n.id) ? 'context' : '', n.id === props.centerId ? 'focus-center' : '',
        dist && (dist[n.id] ?? 0) >= 2 ? 'far' : ''].filter(Boolean).join(' ');
      els.push({
        group: 'nodes',
        data: {
          id: n.id, label: n.name, base: n.base, crit: n.criticality ?? 'normal',
          color: colorOf.get(n.type) || BASE_COLOR[n.base ?? 'Note'] || '#888', shape: BASE_SHAPE[n.base ?? 'Note'] ?? 'ellipse',
        },
        classes,
      });
    }
    const nodeIds = new Set(props.nodes.map((n) => n.id));
    for (const e of props.edges) {
      if (!nodeIds.has(e.from) || !nodeIds.has(e.to)) continue;
      els.push({ group: 'edges', data: { id: e.id, source: e.from, target: e.to, label: e.kind === 'structure' ? '' : e.relType, kind: e.kind, crit: e.criticality ?? 'normal' } });
    }
    for (const l of props.indirect) {
      els.push({
        group: 'edges',
        data: { id: `ind_${l.from}_${l.to}`, source: l.from, target: l.to, label: `≈ ${l.hops} ${t('hops', 'saltos')}`, kind: 'indirect', link: l },
        classes: 'indirect',
      });
    }

    const wanted = new Set(els.map((e) => e.data.id as string));
    const fresh = new Set<string>();
    cy.batch(() => {
      cy.elements().filter((el) => !wanted.has(el.id())).remove();
      for (const el of els) {
        const existing = cy.getElementById(el.data.id as string);
        if (existing.nonempty()) {
          existing.data(el.data);
          existing.classes(el.classes as string);
        } else {
          const added = cy.add(el);
          if (added.isNode()) {
            const p = saved[added.id()];
            if (p) added.position(p);
            else fresh.add(added.id());
          }
        }
      }
    });
    const fitNow = needsFit.current && cy.nodes().length > 0;
    if (fitNow) needsFit.current = false;
    const pid = props.projectId;
    if (fresh.size > 0 && fresh.size === cy.nodes().length) {
      // nada posicionado ainda (primeira visita): layout completo sem sobreposição
      runLayout(cy, 'organico').then(() => savePositions(pid, cy));
    } else if (fresh.size > 0) {
      // nós novos (ex.: criados pela LLM): nascem perto de quem estão ligados, os antigos não se mexem,
      // e no fim só os novos são afastados do que estiverem encavalando
      seedPositions(cy, fresh);
      const layout = cy.layout({
        name: 'fcose', animate: !document.hidden, animationDuration: 400, randomize: false, fit: false, nodeDimensionsIncludeLabels: true,
        nodeRepulsion: 14000, idealEdgeLength: 130, nodeSeparation: 120,
        fixedNodeConstraint: cy.nodes().filter((n) => !fresh.has(n.id())).nodes().map((n) => ({ nodeId: n.id(), position: n.position() })),
      } as never);
      layout.one('layoutstop', async () => {
        await untangle(cy, fresh);
        // reenquadra se for a primeira carga ou se algum nó novo ficou fora da tela
        const ext = cy.extent();
        const outside = cy.nodes().toArray().some((el) => {
          const p = (el as cytoscape.NodeSingular).position();
          return fresh.has(el.id()) && (p.x < ext.x1 || p.x > ext.x2 || p.y < ext.y1 || p.y > ext.y2);
        });
        if (fitNow || outside) cy.animate({ fit: { eles: cy.nodes(), padding: 40 } }, { duration: 300 });
        savePositions(pid, cy);
      });
      layout.run();
    } else if (fitNow) {
      // posições restauradas da última visita: enquadra a câmera nelas
      cy.fit(undefined, 40);
    }
  }, [props.nodes, props.edges, props.indirect, props.contextIds, props.nodeTypes, props.projectId, props.distance, props.centerId]);

  // entrar/sair do foco: enquadra o que está visível
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy || cy.nodes().empty()) return;
    const t = setTimeout(() => cy.animate({ fit: { eles: cy.nodes(), padding: 50 } }, { duration: 300 }), 450);
    return () => clearTimeout(t);
  }, [props.centerId]);

  useImperativeHandle(ref, () => ({
    fit: () => cyRef.current?.fit(undefined, 40),
    relayout: (kind: LayoutKind = 'organico') => {
      const cy = cyRef.current;
      if (!cy) return;
      runLayout(cy, kind, { distance: propsRef.current.distance, centerId: propsRef.current.centerId })
        .then(() => savePositions(props.projectId, cy));
    },
    untangle: () => {
      const cy = cyRef.current;
      if (!cy) return;
      untangle(cy).then(() => savePositions(props.projectId, cy));
    },
    selectIds: (ids: string[]) => {
      const cy = cyRef.current;
      if (!cy) return;
      cy.$(':selected').unselect();
      ids.forEach((id) => cy.getElementById(id).select());
    },
    focus: (id: string) => {
      const cy = cyRef.current;
      if (!cy) return;
      const el = cy.getElementById(id);
      if (el.empty()) return;
      cy.$(':selected').unselect();
      el.select();
      cy.animate({ center: { eles: el }, zoom: Math.max(cy.zoom(), 1.1) }, { duration: 300 });
    },
  }), [props.projectId]);

  return <div ref={container} className="canvas" />;
});
