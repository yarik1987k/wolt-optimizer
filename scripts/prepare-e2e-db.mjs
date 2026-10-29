// Fresh, migrated database used only by the Playwright run (prisma/e2e.db).
import { execSync } from "node:child_process";
import { rmSync } from "node:fs";

for (const f of ["prisma/e2e.db", "prisma/e2e.db-journal"]) rmSync(f, { force: true });
execSync("npx prisma migrate deploy", { stdio: "inherit", env: { ...process.env, DATABASE_URL: "file:./e2e.db" } });
