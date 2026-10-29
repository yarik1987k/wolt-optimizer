import * as React from "react";
import { cn } from "@/lib/utils";

const field = "h-9 w-full rounded-md border border-border bg-surface px-3 text-sm focus-visible:outline-2 focus-visible:outline-primary";

export function Input({ className, ...p }: React.InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cn(field, className)} {...p} />;
}
export function Select({ className, ...p }: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return <select className={cn(field, "pe-8", className)} {...p} />;
}
export function Textarea({ className, ...p }: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={cn(field, "h-auto min-h-20 py-2", className)} {...p} />;
}
export function Label({ className, ...p }: React.LabelHTMLAttributes<HTMLLabelElement>) {
  return <label className={cn("flex flex-col gap-1 text-sm font-medium", className)} {...p} />;
}
export function Hint({ className, ...p }: React.HTMLAttributes<HTMLSpanElement>) {
  return <span className={cn("text-xs font-normal text-muted", className)} {...p} />;
}
