import * as React from "react";
import { cn } from "@/lib/utils";

const styles = {
  info: "bg-info-bg text-info-fg",
  warning: "bg-warning-bg text-warning-fg",
  error: "bg-danger-bg text-danger-fg",
  success: "bg-success-bg text-success-fg",
};

export function Alert({ level = "info", className, ...p }: React.HTMLAttributes<HTMLDivElement> & { level?: keyof typeof styles }) {
  return <div role={level === "error" ? "alert" : "status"} className={cn("rounded-md px-3 py-2 text-sm", styles[level], className)} {...p} />;
}
