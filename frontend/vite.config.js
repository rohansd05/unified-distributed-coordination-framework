import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const src = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'src')

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  // Read the root .env (docs/HANDOFF.md 10.3). Only VITE_* variables reach the browser.
  envDir: '..',
  resolve: {
    alias: { '@': src },
  },
  server: {
    port: 5173,
    strictPort: true,
  },
  test: {
    environment: 'jsdom',
  },
})
