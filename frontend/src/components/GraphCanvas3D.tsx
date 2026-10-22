import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import ForceGraph3D, { type ForceGraph3DInstance } from '3d-force-graph';
import {
  BoxGeometry, BufferGeometry, Color, CylinderGeometry, DodecahedronGeometry, Group, IcosahedronGeometry, Line,
  LineDashedMaterial, Mesh, MeshLambertMaterial, OctahedronGeometry, SphereGeometry, TetrahedronGeometry, TorusGeometry, Vector3,
  type BufferGeometry as Geometry, type Object3D,
} from 'three';
import SpriteText from 'three-spritetext';
import { forceRadial, forceY } from 'd3-force-3d';
import { t } from '../i18n';
import type { IndirectLink } from '../types';
import { BASE_COLOR, type GraphCanvasHandle, type GraphCanvasProps } from './GraphCanvas';
import type { LayoutKind } from './graphLayout';

/** Nó do grafo 3D: dados do domínio + o que o motor de forças acrescenta (x, y, z, vx…, fx…). */
interface N3 {
  id: string;
  name: string;
  type: string;
  base: string;
  crit: string;
  color: string;
  context: boolean;
  dist?: number;
  x?: number; y?: number; z?: number;
  fx?: number; fy?: number; fz?: number;
  __obj?: NodeObj;
}

interface L3 {
  id: string;
  source: string | N3;
  target: string | N3;
  label: string;
  kind: string;
  crit: string;
  indirect?: IndirectLink;
}

interface NodeObj {
  group: Group;
  mesh: Mesh;
  mat: MeshLambertMaterial;
  halo: Mesh;
  haloMat: MeshLambertMaterial;
  label?: SpriteText;
}

type Graph = ForceGraph3DInstance<N3 & object, L3 & object>;

// acima disso só mostra rótulo de quem está em destaque (selecionado, vizinho, centro do foco, sob o mouse)
const LABEL_LIMIT = 400;
// acima disso só desenha setas nas ligações em destaque: cada seta é um objeto (draw call) a mais por quadro
const ARROW_LIMIT = 1000;
const R = 4;

const GEOMETRY: Record<string, Geometry> = {
  Agent: new SphereGeometry(R, 18, 14),
  Place: new CylinderGeometry(R, R, R * 1.3, 6),
  Object: new OctahedronGeometry(R * 1.15),
  Ability: new IcosahedronGeometry(R * 1.1),
  Concept: new DodecahedronGeometry(R * 1.05),
  Event: new TetrahedronGeometry(R * 1.3),
  Media: new TorusGeometry(R * 0.8, R * 0.35, 8, 16),
  Note: new BoxGeometry(R * 1.6, R * 1.6, R * 0.6),
};
const HALO = new SphereGeometry(R * 1.9, 18, 14);

const posKey = (pid: string) => `hm.positions3d.${pid}`;

function loadPositions(pid: string): Record<string, [number, number, number]> {
  try {
    return JSON.parse(localStorage.getItem(posKey(pid)) ?? '{}');
  } catch {
    return {};
  }
}

function savePositions(pid: string, nodes: N3[]) {
  try {
    const all = loadPositions(pid);
    for (const n of nodes) if (n.x != null) all[n.id] = [Math.round(n.x), Math.round(n.y ?? 0), Math.round(n.z ?? 0)];
    localStorage.setItem(posKey(pid), JSON.stringify(all));
  } catch {
    /* armazenamento indisponível: só não lembra as posições */
  }
}

function cssVar(name: string) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#888';
}

function rgba(hex: string, alpha: number) {
  const c = new Color(hex);
  return `rgba(${Math.round(c.r * 255)},${Math.round(c.g * 255)},${Math.round(c.b * 255)},${alpha})`;
}

const endId = (e: string | N3) => (typeof e === 'string' ? e : e.id);

function escapeHtml(s: string) {
  return s.replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch]!);
}

export const GraphCanvas3D = forwardRef<GraphCanvasHandle, GraphCanvasProps>(function GraphCanvas3D(props, ref) {
  const container = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Graph | null>(null);
  const propsRef = useRef(props);
  propsRef.current = props;
  const nodesRef = useRef(new Map<string, N3>());
  const linksRef = useRef<L3[]>([]);
  const selNodes = useRef(new Set<string>());
  const selLink = useRef<L3 | null>(null);
  const hovered = useRef<string | null>(null);
  const restyleRef = useRef<() => void>(() => {});
  const wakeRef = useRef<(ms?: number) => void>(() => {});
  // enquadra sozinho quando a simulação estabilizar depois de uma carga de dados, se o usuário não mexeu na câmera
  const needsFit = useRef(true);
  const userMoved = useRef(false);
  const degree = useRef(new Map<string, number>());

  // cria a cena uma vez por projeto
  useEffect(() => {
    const el = container.current!;
    const colors = {
      text: cssVar('--text'), edge: cssVar('--edge'), accent: cssVar('--accent'), gold: cssVar('--gold'),
      warn: cssVar('--warn'), canvas: cssVar('--canvas'),
    };
    const graph = new ForceGraph3D(el, { controlType: 'trackball' }) as unknown as Graph;
    graphRef.current = graph;
    needsFit.current = true;
    const pid = props.projectId;

    // ---- render sob demanda: o laço de animação para quando a simulação esfria e ninguém mexe na câmera ----
    let engineRunning = true;
    let idle: ReturnType<typeof setTimeout> | undefined;
    let paused = false;
    const wake = (ms = 1500) => {
      if (paused) { graph.resumeAnimation(); paused = false; }
      clearTimeout(idle);
      idle = setTimeout(() => {
        if (!engineRunning) { graph.pauseAnimation(); paused = true; }
      }, ms);
    };
    wakeRef.current = wake;
    const onInput = () => wake();
    const onCamera = () => { userMoved.current = true; };
    for (const ev of ['pointerdown', 'pointermove', 'wheel', 'keydown'] as const) el.addEventListener(ev, onInput, { passive: true });
    for (const ev of ['pointerdown', 'wheel'] as const) el.addEventListener(ev, onCamera, { passive: true });

    // ---- destaque: selecionados + vizinhos acesos, o resto apagado ----
    const highlight = () => {
      const lit = new Set<string>();
      const sel = selNodes.current;
      const link = selLink.current;
      if (sel.size > 0 || link) {
        sel.forEach((id) => lit.add(id));
        if (link) { lit.add(endId(link.source)); lit.add(endId(link.target)); }
        for (const l of linksRef.current) {
          const s = endId(l.source), tg = endId(l.target);
          if (sel.has(s)) lit.add(tg);
          if (sel.has(tg)) lit.add(s);
        }
      }
      return lit;
    };

    const ensureLabel = (n: N3, o: NodeObj) => {
      if (o.label) return o.label;
      const name = n.name.length > 32 ? n.name.slice(0, 31) + '…' : n.name;
      const s = new SpriteText(name, 5, colors.text);
      s.backgroundColor = rgba(colors.canvas, 0.65);
      s.padding = 0.8;
      s.borderRadius = 1;
      s.fontFace = 'system-ui, sans-serif';
      s.position.set(0, -(R * 2.6), 0);
      s.material.depthWrite = false;
      o.group.add(s);
      o.label = s;
      return s;
    };

    let lastLit = new Set<string>();
    const restyle = () => {
      const lit = highlight();
      lastLit = lit;
      const anyLit = lit.size > 0;
      const center = propsRef.current.centerId;
      const many = nodesRef.current.size > LABEL_LIMIT;
      nodesRef.current.forEach((n) => {
        const o = n.__obj;
        if (!o) return;
        const selected = selNodes.current.has(n.id);
        const isCenter = n.id === center;
        const dim = anyLit ? !lit.has(n.id) : (n.dist ?? 0) >= 2;
        let alpha = n.context ? 0.45 : 1;
        if (dim) alpha *= anyLit ? 0.12 : 0.55;
        o.mat.opacity = alpha;
        if (o.mat.transparent !== alpha < 1) {
          // trocar opaco/translúcido exige recompilar o material
          o.mat.transparent = alpha < 1;
          o.mat.depthWrite = alpha >= 1;
          o.mat.needsUpdate = true;
        }
        o.mat.emissive.set(selected || isCenter ? colors.accent : '#000000');
        o.mat.emissiveIntensity = selected ? 0.55 : 0.35;
        const scale = isCenter ? 1.5 : selected ? 1.25 : 1;
        o.mesh.scale.setScalar(scale);
        // halo: seleção/centro em destaque; crítico e fundacional sempre
        const haloColor = selected || isCenter ? colors.accent : n.crit === 'foundational' ? colors.gold : n.crit === 'critical' ? colors.warn : null;
        o.halo.visible = !!haloColor && !(dim && anyLit);
        if (haloColor) o.haloMat.color.set(haloColor);
        o.halo.scale.setScalar(scale);
        const showLabel = !(dim && anyLit) && (!many || lit.has(n.id) || isCenter || hovered.current === n.id);
        if (showLabel) {
          const s = ensureLabel(n, o);
          s.visible = true;
          s.material.opacity = dim ? 0.45 : 1;
          s.position.y = -(R * 2.6 * scale);
        } else if (o.label) {
          o.label.visible = false;
        }
      });
      const selL = selLink.current;
      const fewLinks = linksRef.current.length <= ARROW_LIMIT;
      graph
        .linkDirectionalArrowLength((l: L3) => {
          if (l.indirect) return 0;
          if (fewLinks) return 3.5;
          const s = endId(l.source), tg = endId(l.target);
          return anyLit && (selNodes.current.has(s) || selNodes.current.has(tg) || l === selL) ? 3.5 : 0;
        })
        .linkColor((l: L3) => {
          const s = endId(l.source), tg = endId(l.target);
          const on = !anyLit || (lit.has(s) && lit.has(tg) && (selNodes.current.has(s) || selNodes.current.has(tg) || l === selL));
          let c = l.indirect ? colors.accent : l.crit === 'foundational' ? colors.gold : l.crit === 'critical' ? colors.warn : colors.edge;
          if (l === selL) c = colors.accent;
          return rgba(c, on ? 1 : 0.06);
        })
        .linkDirectionalArrowColor((l: L3) => {
          const s = endId(l.source), tg = endId(l.target);
          const on = !anyLit || (lit.has(s) && lit.has(tg));
          const c = l === selL ? colors.accent : l.crit === 'foundational' ? colors.gold : l.crit === 'critical' ? colors.warn : colors.edge;
          return rgba(c, on ? 0.33 : 0.02);
        });
      wake();
    };
    // os objetos 3D são criados no próximo ciclo do 3d-force-graph: estiliza quando eles existirem
    let restylePending = 0;
    const scheduleRestyle = () => {
      if (!restylePending) restylePending = requestAnimationFrame(() => { restylePending = 0; restyle(); });
    };
    restyleRef.current = scheduleRestyle;

    const emit = () => {
      const l = selLink.current;
      propsRef.current.onSelectionChange(
        [...selNodes.current],
        l && !l.indirect ? [l.id] : [],
        l?.indirect ?? null,
      );
      restyle();
    };

    let lastClick = { id: '', at: 0 };
    let ticked = false;
    graph
      .backgroundColor(colors.canvas)
      .showNavInfo(false)
      .nodeId('id')
      .nodeThreeObject((n: N3) => {
        const mat = new MeshLambertMaterial({ color: n.color });
        const mesh = new Mesh(GEOMETRY[n.base] ?? GEOMETRY.Agent, mat);
        const haloMat = new MeshLambertMaterial({ color: colors.accent, transparent: true, opacity: 0.22, depthWrite: false });
        const halo = new Mesh(HALO, haloMat);
        halo.visible = false;
        const group = new Group();
        group.add(mesh, halo);
        n.__obj = { group, mesh, mat, halo, haloMat };
        scheduleRestyle();
        return group;
      })
      .nodeLabel((n: N3) => `<b>${escapeHtml(n.name)}</b><br><span style="opacity:.75">${escapeHtml(n.type)}</span>`)
      .linkLabel((l: L3) => escapeHtml(l.label || l.kind))
      .linkWidth(0)
      .linkOpacity(0.6)
      .linkDirectionalArrowRelPos(1)
      .linkHoverPrecision(2)
      .linkThreeObject((l: L3) => {
        if (!l.indirect) return undefined as unknown as Object3D;
        const geo = new BufferGeometry().setFromPoints([new Vector3(), new Vector3()]);
        return new Line(geo, new LineDashedMaterial({ color: colors.accent, dashSize: 3, gapSize: 2.5, transparent: true, opacity: 0.7 }));
      })
      .linkPositionUpdate((obj: Object3D, { start, end }: { start: { x: number; y: number; z: number }; end: { x: number; y: number; z: number } }, l: L3) => {
        if (!l.indirect) return false;
        const line = obj as Line;
        const pos = line.geometry.getAttribute('position');
        pos.setXYZ(0, start.x, start.y, start.z);
        pos.setXYZ(1, end.x, end.y, end.z);
        pos.needsUpdate = true;
        line.geometry.computeBoundingSphere();
        line.computeLineDistances();
        return true;
      })
      .cooldownTime(8000)
      // o three-forcegraph usa 0 (só para pelo tempo); 0,001 é o padrão do d3: para quando o layout converge
      .d3AlphaMin(0.001)
      .onNodeHover((n: N3 | null) => {
        el.style.cursor = n ? 'pointer' : '';
        const before = hovered.current;
        hovered.current = n?.id ?? null;
        if (nodesRef.current.size <= LABEL_LIMIT || before === hovered.current) return;
        // grafo grande: só o rótulo de quem está sob o mouse aparece e some (sem refazer o estilo de tudo)
        if (n?.__obj) ensureLabel(n, n.__obj).visible = true;
        const prev = before ? nodesRef.current.get(before) : undefined;
        if (prev?.__obj?.label && !lastLit.has(prev.id) && prev.id !== propsRef.current.centerId) prev.__obj.label.visible = false;
        wake();
      })
      .onLinkHover((l: L3 | null) => { el.style.cursor = l ? 'pointer' : ''; })
      .onNodeClick((n: N3, e: MouseEvent) => {
        const now = performance.now();
        if (lastClick.id === n.id && now - lastClick.at < 350) {
          lastClick = { id: '', at: 0 };
          propsRef.current.onNodeDoubleTap?.(n.id);
          return;
        }
        lastClick = { id: n.id, at: now };
        if (e.shiftKey || e.ctrlKey || e.metaKey) {
          if (selNodes.current.has(n.id)) selNodes.current.delete(n.id); else selNodes.current.add(n.id);
        } else {
          selNodes.current = new Set([n.id]);
        }
        selLink.current = null;
        emit();
      })
      .onLinkClick((l: L3) => {
        selNodes.current = new Set();
        selLink.current = l;
        emit();
      })
      .onBackgroundClick(() => {
        if (selNodes.current.size === 0 && !selLink.current) return;
        selNodes.current = new Set();
        selLink.current = null;
        emit();
      })
      .onNodeDragEnd((n: N3) => {
        // solta onde o usuário largou (como no 2D): fixa o nó
        n.fx = n.x; n.fy = n.y; n.fz = n.z;
        savePositions(propsRef.current.projectId, [n]);
      })
      .onEngineTick(() => { engineRunning = true; ticked = true; })
      .onEngineStop(() => {
        engineRunning = false;
        // o 3d-force-graph também "para" depois de qualquer mudança de estilo; só age se o layout andou
        if (!ticked && !needsFit.current) { wake(300); return; }
        ticked = false;
        // nós que estavam fixos só para os novos se acomodarem voltam a ser livres
        nodesRef.current.forEach((n) => {
          if ((n as N3 & { __pinned?: boolean }).__pinned) { delete n.fx; delete n.fy; delete n.fz; delete (n as N3 & { __pinned?: boolean }).__pinned; }
        });
        savePositions(propsRef.current.projectId, [...nodesRef.current.values()]);
        if (needsFit.current && nodesRef.current.size > 0) {
          needsFit.current = false;
          // depois do posicionamento inicial de câmera que o 3d-force-graph faz na primeira carga
          if (!userMoved.current) setTimeout(() => { wake(1000); graph.zoomToFit(500, 40); }, 60);
        } else {
          wake(300);
        }
      });

    // ligações indiretas não puxam o layout; as reais puxam como o padrão do d3 (1 / grau do menor lado)
    const linkForce = graph.d3Force('link') as unknown as {
      strength: (fn: (l: L3) => number) => void; distance: (d: number) => void;
    };
    linkForce.distance(55);
    linkForce.strength((l: L3) => {
      if (l.indirect) return 0;
      const deg = degree.current;
      return 1 / Math.min(deg.get(endId(l.source)) ?? 1, deg.get(endId(l.target)) ?? 1);
    });
    (graph.d3Force('charge') as unknown as { strength: (s: number) => void }).strength(-140);
    // gravidade fraca: nós soltos e componentes isolados não se afastam a ponto de o "Enquadrar" virar um ponto
    graph.d3Force('gravity', forceRadial<N3>(0).strength((n) => (degree.current.get(n.id) ? 0.012 : 0.1)) as never);

    let refit: ReturnType<typeof setTimeout> | undefined;
    const fitSize = () => {
      if (el.clientWidth && el.clientHeight) graph.width(el.clientWidth).height(el.clientHeight);
      wake();
      // painéis/janela mudaram de tamanho: reenquadra se a câmera ainda é a do enquadramento automático
      clearTimeout(refit);
      refit = setTimeout(() => {
        if (!userMoved.current && !needsFit.current && nodesRef.current.size > 0) { wake(800); graph.zoomToFit(300, 40); }
      }, 200);
    };
    // o 3d-force-graph nasce do tamanho da janela; acompanha o container (painéis, janela)
    fitSize();
    const ro = new ResizeObserver(fitSize);
    ro.observe(el);
    wake();

    return () => {
      clearTimeout(idle);
      clearTimeout(refit);
      cancelAnimationFrame(restylePending);
      ro.disconnect();
      for (const ev of ['pointerdown', 'pointermove', 'wheel', 'keydown'] as const) el.removeEventListener(ev, onInput);
      for (const ev of ['pointerdown', 'wheel'] as const) el.removeEventListener(ev, onCamera);
      savePositions(pid, [...nodesRef.current.values()]);
      graph._destructor();
      el.innerHTML = '';
      graphRef.current = null;
      nodesRef.current = new Map();
      linksRef.current = [];
      selNodes.current = new Set();
      selLink.current = null;
    };
  }, [props.projectId]);

  // sincroniza dados sem reiniciar o layout: reaproveita os objetos (e as posições) de quem já estava
  useEffect(() => {
    const graph = graphRef.current;
    if (!graph) return;
    const colorOf = new Map(props.nodeTypes.map((nt) => [nt.name, nt.color]));
    const ctx = new Set(props.contextIds);
    const old = nodesRef.current;
    const saved = loadPositions(props.projectId);
    const next = new Map<string, N3>();
    const fresh: N3[] = [];
    for (const n of props.nodes) {
      const base = n.base ?? 'Note';
      const color = colorOf.get(n.type) || BASE_COLOR[base] || '#888';
      const prev = old.get(n.id);
      const node: N3 = prev ?? { id: n.id, name: n.name, type: n.type, base, crit: 'normal', color, context: false };
      if (prev && (prev.color !== color || prev.base !== base) && prev.__obj) {
        prev.__obj.mat.color.set(color);
        prev.__obj.mesh.geometry = GEOMETRY[base] ?? GEOMETRY.Agent;
      }
      if (prev && prev.name !== n.name && prev.__obj?.label) {
        prev.__obj.group.remove(prev.__obj.label);
        prev.__obj.label = undefined;
      }
      Object.assign(node, {
        name: n.name, type: n.type, base, color, crit: n.criticality ?? 'normal', context: ctx.has(n.id),
        dist: props.distance?.[n.id],
      });
      if (!prev) {
        const p = saved[n.id];
        if (p) [node.x, node.y, node.z] = p;
        else fresh.push(node);
      }
      next.set(n.id, node);
    }
    const links: L3[] = [];
    for (const e of props.edges) {
      if (!next.has(e.from) || !next.has(e.to)) continue;
      links.push({ id: e.id, source: e.from, target: e.to, label: e.relType, kind: e.kind, crit: e.criticality ?? 'normal' });
    }
    for (const l of props.indirect) {
      if (!next.has(l.from) || !next.has(l.to)) continue;
      links.push({ id: `ind_${l.from}_${l.to}`, source: l.from, target: l.to, label: `≈ ${l.hops} ${t('hops', 'saltos')}`, kind: 'indirect', crit: 'normal', indirect: l });
    }
    const deg = new Map<string, number>();
    for (const l of links) {
      if (l.indirect) continue;
      const s = endId(l.source), tg = endId(l.target);
      deg.set(s, (deg.get(s) ?? 0) + 1);
      deg.set(tg, (deg.get(tg) ?? 0) + 1);
    }

    const sameNodes = next.size === old.size && [...next.keys()].every((id) => old.has(id));
    const linkSig = (ls: L3[]) => ls.map((l) => `${l.id}|${endId(l.source)}|${endId(l.target)}|${l.label}|${l.crit}`).sort().join('\n');
    const sameLinks = linkSig(links) === linkSig(linksRef.current);
    nodesRef.current = next;
    // descarta da seleção o que sumiu da visão
    selNodes.current = new Set([...selNodes.current].filter((id) => next.has(id)));

    if (!sameNodes) {
      // carga nova (projeto, filtro, foco, troca 2D→3D): enquadra de novo quando estabilizar
      needsFit.current = true;
      userMoved.current = false;
    }
    if (!sameNodes || !sameLinks) {
      degree.current = deg;
      linksRef.current = links;
      const restored = next.size - fresh.length;
      if (fresh.length > 0 && restored > 0) {
        // nós novos nascem perto de quem estão ligados; os antigos ficam parados enquanto eles se acomodam
        for (const n of fresh) {
          const anchors = links.filter((l) => endId(l.source) === n.id || endId(l.target) === n.id)
            .map((l) => next.get(endId(l.source) === n.id ? endId(l.target) : endId(l.source))!)
            .filter((m) => m.x != null);
          const c = anchors.length ? anchors.reduce((a, m) => ({ x: a.x + m.x! / anchors.length, y: a.y + m.y! / anchors.length, z: a.z + m.z! / anchors.length }), { x: 0, y: 0, z: 0 }) : { x: 0, y: 0, z: 0 };
          n.x = c.x + (Math.random() - 0.5) * 60; n.y = c.y + (Math.random() - 0.5) * 60; n.z = c.z + (Math.random() - 0.5) * 60;
        }
        next.forEach((n) => {
          if (!fresh.includes(n) && n.fx == null) {
            n.fx = n.x; n.fy = n.y; n.fz = n.z;
            (n as N3 & { __pinned?: boolean }).__pinned = true;
          }
        });
      }
      // tudo restaurado da última visita: fixa todos e roda só 2 passos, para os objetos 3D irem às posições
      // salvas (com 0 passos eles ficariam empilhados na origem); o onEngineStop solta de novo
      const allRestored = fresh.length === 0 && restored > 0 && old.size === 0;
      if (allRestored) {
        next.forEach((n) => {
          if (n.fx == null) { n.fx = n.x; n.fy = n.y; n.fz = n.z; (n as N3 & { __pinned?: boolean }).__pinned = true; }
        });
      }
      graph.cooldownTicks(allRestored ? 2 : Infinity);
      // grafo grande sem posições: calcula o layout antes de desenhar (~300 passos do d3 custam bem menos
      // que desenhar e atualizar milhares de objetos a cada passo)
      const big = fresh.length > 500;
      graph.warmupTicks(big ? 300 : 0).cooldownTime(8000);
      graph.graphData({ nodes: [...next.values()], links });
      wakeRef.current();
    }
    restyleRef.current();
  }, [props.nodes, props.edges, props.indirect, props.contextIds, props.nodeTypes, props.projectId, props.distance, props.centerId]);

  useImperativeHandle(ref, () => ({
    fit: () => {
      wakeRef.current(1000);
      graphRef.current?.zoomToFit(500, 40);
    },
    relayout: (kind: LayoutKind = 'organico') => {
      const graph = graphRef.current;
      if (!graph) return;
      arrange(graph, [...nodesRef.current.values()], linksRef.current, kind, propsRef.current);
      needsFit.current = true;
      userMoved.current = false;
      graph.cooldownTicks(Infinity).d3ReheatSimulation();
      wakeRef.current();
    },
    untangle: () => {
      const graph = graphRef.current;
      if (!graph) return;
      // solta quem foi fixado arrastando e deixa as forças separarem de novo
      nodesRef.current.forEach((n) => { delete n.fx; delete n.fy; delete n.fz; });
      graph.cooldownTicks(Infinity).d3ReheatSimulation();
      wakeRef.current();
    },
    selectIds: (ids: string[]) => {
      selNodes.current = new Set(ids.filter((id) => nodesRef.current.has(id)));
      selLink.current = null;
      propsRef.current.onSelectionChange([...selNodes.current], [], null);
      restyleRef.current();
    },
    focus: (id: string) => {
      const graph = graphRef.current;
      const n = nodesRef.current.get(id);
      if (!graph || !n) return;
      selNodes.current = new Set([id]);
      selLink.current = null;
      propsRef.current.onSelectionChange([id], [], null);
      restyleRef.current();
      const x = n.x ?? 0, y = n.y ?? 0, z = n.z ?? 0;
      const d = Math.hypot(x, y, z) || 1;
      const ratio = 1 + 170 / d;
      wakeRef.current(1400);
      graph.cameraPosition({ x: x * ratio, y: y * ratio, z: z * ratio }, { x, y, z }, 800);
    },
  }), []);

  return <div ref={container} className="canvas" />;
});

/**
 * Reorganiza em 3D. Orgânico = embaralha e deixa as forças agirem; Hierárquico = forças puxam cada nível para uma altura;
 * Anéis = cascas esféricas pela distância ao centro; Grade = plano fixo ordenado por tipo.
 */
function arrange(graph: Graph, nodes: N3[], links: L3[], kind: LayoutKind, props: GraphCanvasProps) {
  for (const n of nodes) { delete n.fx; delete n.fy; delete n.fz; }
  graph.d3Force('levels', null).d3Force('rings', null);
  const adj = new Map<string, string[]>();
  for (const l of links) {
    if (l.indirect) continue;
    const s = endId(l.source), tg = endId(l.target);
    adj.set(s, [...(adj.get(s) ?? []), tg]);
    adj.set(tg, [...(adj.get(tg) ?? []), s]);
  }
  const deg = (id: string) => adj.get(id)?.length ?? 0;
  switch (kind) {
    case 'hierarquico': {
      // níveis por distância (BFS) a partir do centro do foco, das obras, ou do nó mais ligado
      // raiz: o centro do foco, ou o nó mais ligado
      const roots = props.centerId ? [props.centerId] : nodes.length ? [nodes.reduce((a, b) => (deg(b.id) > deg(a.id) ? b : a)).id] : [];
      const level = new Map<string, number>(roots.map((r) => [r, 0]));
      const queue = [...roots];
      while (queue.length) {
        const cur = queue.shift()!;
        for (const nb of adj.get(cur) ?? []) if (!level.has(nb)) { level.set(nb, level.get(cur)! + 1); queue.push(nb); }
      }
      const maxL = Math.max(0, ...level.values());
      graph.d3Force('levels', forceY<N3>((n) => (maxL / 2 - (level.get(n.id) ?? maxL + 1)) * 70).strength(0.9) as never);
      break;
    }
    case 'aneis': {
      const maxDeg = Math.max(1, ...nodes.map((n) => deg(n.id)));
      const ring = (n: N3) => (props.distance ? (props.distance[n.id] ?? 3) : Math.round((1 - deg(n.id) / maxDeg) * 4));
      graph.d3Force('rings', forceRadial<N3>((n) => ring(n) * 70).strength(0.9) as never);
      break;
    }
    case 'grade': {
      const sorted = [...nodes].sort((a, b) => (a.base + a.name).localeCompare(b.base + b.name));
      const side = Math.ceil(Math.sqrt(sorted.length));
      sorted.forEach((n, i) => { n.fx = (i % side - side / 2) * 30; n.fy = -(Math.floor(i / side) - side / 2) * 30; n.fz = 0; });
      break;
    }
    default: {
      const spread = Math.cbrt(nodes.length) * 40;
      for (const n of nodes) {
        n.x = (Math.random() - 0.5) * spread; n.y = (Math.random() - 0.5) * spread; n.z = (Math.random() - 0.5) * spread;
      }
    }
  }
}

export default GraphCanvas3D;
