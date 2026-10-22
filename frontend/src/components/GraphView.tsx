import { forwardRef, lazy, Suspense, useCallback, useImperativeHandle, useRef, useState } from 'react';
import type { IndirectLink } from '../types';
import { t } from '../i18n';
import { GraphCanvas, type GraphCanvasHandle, type GraphCanvasProps } from './GraphCanvas';

// o 3D (Three.js/WebGL) fica num pedaço à parte do bundle: só baixa quando a visão 3D é usada
const GraphCanvas3D = lazy(() => import('./GraphCanvas3D'));

export type GraphDim = '2d' | '3d';
const KEY = 'hm.graphDim';

function initialDim(): GraphDim {
  try {
    return localStorage.getItem(KEY) === '2d' ? '2d' : '3d';
  } catch {
    return '3d';
  }
}

/** Visão 2D/3D lembrada neste navegador (3D por padrão). */
export function useGraphDim(): [GraphDim, (d: GraphDim) => void] {
  const [dim, setDimState] = useState<GraphDim>(initialDim);
  const setDim = useCallback((d: GraphDim) => {
    setDimState(d);
    try {
      localStorage.setItem(KEY, d);
    } catch {
      /* armazenamento indisponível: só não lembra a escolha */
    }
  }, []);
  return [dim, setDim];
}

export function DimToggle({ dim, setDim }: { dim: GraphDim; setDim: (d: GraphDim) => void }) {
  return (
    <div className="group" role="group" aria-label={t('View', 'Visão')}>
      <button className={dim === '2d' ? 'active' : ''} onClick={() => setDim('2d')}
        title={t('Flat view: best for Box/Lasso selection and bulk editing', 'Visão plana: melhor para seleção em Área/Laço e edição em lote')}>2D</button>
      <button className={dim === '3d' ? 'active' : ''} onClick={() => setDim('3d')}
        title={t('3D view: drag rotates, right button pans, wheel zooms; Shift/Ctrl+click adds to the selection', 'Visão 3D: arrastar gira, botão direito move, roda aproxima; Shift/Ctrl+clique soma à seleção')}>3D</button>
    </div>
  );
}

/** Mesmas props e mesmo handle do GraphCanvas; escolhe o renderizador e leva a seleção de um para o outro. */
export const GraphView = forwardRef<GraphCanvasHandle, GraphCanvasProps & { dim: GraphDim }>(function GraphView({ dim, ...props }, ref) {
  const inner = useRef<GraphCanvasHandle | null>(null);
  const lastSel = useRef<{ pid: string; ids: string[] }>({ pid: props.projectId, ids: [] });
  const onSelRef = useRef(props.onSelectionChange);
  onSelRef.current = props.onSelectionChange;
  const pidRef = useRef(props.projectId);
  pidRef.current = props.projectId;

  useImperativeHandle(ref, () => ({
    fit: () => inner.current?.fit(),
    relayout: (kind) => inner.current?.relayout(kind),
    untangle: () => inner.current?.untangle(),
    selectIds: (ids) => inner.current?.selectIds(ids),
    focus: (id) => inner.current?.focus(id),
  }), []);

  const onSelectionChange = useCallback((n: string[], e: string[], ind: IndirectLink | null) => {
    lastSel.current = { pid: pidRef.current, ids: n };
    onSelRef.current(n, e, ind);
  }, []);

  // renderizador novo montado (troca 2D/3D): reaplica a seleção de nós depois que ele carregar os elementos
  const attach = useCallback((h: GraphCanvasHandle | null) => {
    inner.current = h;
    if (!h) return;
    // mesmo projeto = troca 2D/3D; projeto novo começa sem seleção (como antes)
    if (lastSel.current.pid !== pidRef.current) return;
    const ids = lastSel.current.ids;
    onSelRef.current(ids, [], null);
    if (ids.length) setTimeout(() => { if (inner.current === h) h.selectIds(ids); }, 80);
  }, []);

  const canvasProps = { ...props, onSelectionChange };
  if (dim === '2d') return <GraphCanvas ref={attach} {...canvasProps} />;
  return (
    <Suspense fallback={<div className="canvas empty">{t('Loading 3D view…', 'Carregando visão 3D…')}</div>}>
      <GraphCanvas3D ref={attach} {...canvasProps} />
    </Suspense>
  );
});
