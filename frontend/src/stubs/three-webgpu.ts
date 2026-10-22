// O three-render-objects (dependência do 3d-force-graph) importa o WebGPURenderer só para a opção `useWebGPU`,
// que não usamos. O alias no vite.config.ts aponta `three/webgpu` para cá e tira ~2 MB de código do pedaço 3D.
export class WebGPURenderer {
  constructor() {
    throw new Error('WebGPURenderer não está incluído neste build (use WebGL).');
  }
}
