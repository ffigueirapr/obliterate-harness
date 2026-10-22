import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

const backend = process.env.BACKEND_URL ?? 'http://127.0.0.1:8180';

export default defineConfig({
  plugins: [react()],
  resolve: {
    // o 3D usa só WebGL: o renderizador WebGPU do Three.js (~2 MB) fica de fora
    alias: [{ find: /^three\/webgpu$/, replacement: fileURLToPath(new URL('./src/stubs/three-webgpu.ts', import.meta.url)) }],
  },
  server: {
    port: 5273,
    proxy: { '/api': backend, '/mcp': backend },
  },
});
