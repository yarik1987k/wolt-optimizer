import { redirect } from "next/navigation";

/** The engine opens on the before-shift plan; the old payments dashboard lives under /dashboard (מתקדם). */
export default function Home() {
  redirect("/plan");
}
