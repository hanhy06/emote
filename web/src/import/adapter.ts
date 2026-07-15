import type { ImportedProject } from "./types";

export interface ImportInput {
  name: string;
  bytes: Uint8Array;
}

export interface ProbeResult {
  confidence: number;
  reason: string;
}

export interface ImportAdapter {
  readonly id: string;
  readonly label: string;
  readonly extensions: readonly string[];

  probe(input: ImportInput): Promise<ProbeResult> | ProbeResult;
  import(input: ImportInput): Promise<ImportedProject>;
}
