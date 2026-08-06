import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Konteyner imajının sadece gereken node_modules'ü taşıması için (bkz frontend/Dockerfile,
  // Faz 5.5) — standalone çıktı kendi minimal server.js'ini üretir.
  output: "standalone",
};

export default nextConfig;
