import type { ImportedProject, ImportSource } from "./types";

export interface ImportInput {
  name: string;
  bytes: Uint8Array;
}

export interface ProbeResult {
  confidence: number;
  reason: string;
}

export interface ImportAdapter {
  readonly id: ImportSource;
  readonly label: string;
  readonly extensions: readonly string[];

  probe(input: ImportInput): Promise<ProbeResult> | ProbeResult;
  import(input: ImportInput): Promise<ImportedProject>;
}

export interface ImportAdapterLoader extends Pick<ImportAdapter, "id" | "label" | "extensions"> {
  load(): Promise<ImportAdapter>;
}
