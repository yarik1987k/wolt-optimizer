import { execSync } from "node:child_process";
import { rmSync } from "node:fs";
import { resolve } from "node:path";

// Throwaway SQLite database used only by the test suite (prisma/test.db):
// remove it and apply the migrations to a fresh file.
export default function setup() {
  for (const f of ["test.db", "test.db-journal"]) rmSync(resolve(__dirname, "../prisma", f), { force: true });
  execSync("npx prisma migrate deploy", {
    stdio: "inherit",
    env: { ...process.env, DATABASE_URL: "file:./test.db" },
  });
}
