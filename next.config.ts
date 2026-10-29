import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Local-only app: no telemetry-dependent features, no remote images.
  serverExternalPackages: ["@prisma/client"],
};

export default nextConfig;
