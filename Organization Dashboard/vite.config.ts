import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['icon-light.png', 'icon-dark.png'],
      manifest: {
        name: 'theSpaces. Organization Dashboard',
        short_name: 'theSpaces.',
        description: 'The organization setup and control plane for theSpaces.',
        theme_color: '#4F46E5',
        background_color: '#F3F4F6',
        display: 'standalone',
        start_url: '/',
        icons: [
          { src: '/icon-light.png', sizes: '1024x1024', type: 'image/png', purpose: 'any' },
          { src: '/icon-light.png', sizes: '1024x1024', type: 'image/png', purpose: 'maskable' }
        ]
      }
    })
  ],
  test: { environment: 'jsdom', setupFiles: './src/test/setup.ts', css: true }
})
