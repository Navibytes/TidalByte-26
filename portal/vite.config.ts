import { defineConfig } from 'vite';

// base './' lets `npm run build` output be served from any path (e.g. `npm run preview`).
// The bundled mock data (~0.7 MB) makes the single chunk large; that's fine for a local demo.
export default defineConfig({ base: './', build: { chunkSizeWarningLimit: 1500 } });
