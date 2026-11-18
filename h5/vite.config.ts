import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Independent of the marketing site on :5174.
// VITE_PROXY_TARGET lets a dev run read the production API through the proxy
// (same-origin, so no CORS) instead of the local Java service.
const target = process.env.VITE_PROXY_TARGET ?? "http://127.0.0.1:18110";

// Phone / tunnel access for local development:
//   VITE_DEV_HOST=0.0.0.0            listen on every interface (LAN, hotspot)
//   VITE_ALLOWED_HOSTS=.trycloudflare.com,.ngrok-free.app
//                                    accept these Host headers (Vite 5.4 blocks
//                                    unknown hosts with "Blocked request").
// Both are development-only; production builds ignore them.
const devHost = process.env.VITE_DEV_HOST || "localhost";
const allowedHosts = (process.env.VITE_ALLOWED_HOSTS ?? "")
  .split(",")
  .map((host) => host.trim())
  .filter(Boolean);

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5175,
    host: devHost,
    ...(allowedHosts.length > 0 ? { allowedHosts } : {}),
    proxy: {
      "/v1": { target, changeOrigin: true, secure: true },
    },
  },
});
