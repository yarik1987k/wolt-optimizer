import { defineConfig, devices } from "@playwright/test";

const PORT = 3200;

export default defineConfig({
  testDir: "tests/e2e",
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  reporter: [["list"]],
  use: { baseURL: `http://localhost:${PORT}`, locale: "he-IL", timezoneId: "Asia/Jerusalem" },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
  ],
  webServer: {
    // Requires `npm run build` first (the test:e2e script does it).
    command: `node scripts/prepare-e2e-db.mjs && npx next start -p ${PORT}`,
    url: `http://localhost:${PORT}`,
    env: { DATABASE_URL: "file:./e2e.db" },
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
