import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: '0.0.0.0',
    allowedHosts: true,
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 5000,
  },
  optimizeDeps: {
    include: ['monaco-editor', '@xterm/xterm', '@xterm/addon-fit', 'lucide-react', 'zustand'],
  },
});
