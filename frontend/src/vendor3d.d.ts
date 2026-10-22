declare module 'd3-force-3d' {
  interface Force<N> {
    (alpha: number): void;
    initialize?: (nodes: N[], ...args: unknown[]) => void;
    strength(s: number | ((n: N) => number)): Force<N>;
  }
  export function forceRadial<N>(radius: number | ((n: N) => number), x?: number, y?: number, z?: number): Force<N>;
  export function forceY<N>(y: number | ((n: N) => number)): Force<N>;
}
