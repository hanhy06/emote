import type { ImportedProject, ImportSource } from "./types";
import type { EmoteSequence } from "./emoteJson/sequenceJsonConverter";

export interface ImportInput {
  name: string;
  bytes: Uint8Array;
}

export interface ProbeResult {
  confidence: number;
  reason: string;
}

export interface ImportAdapter<T extends ImportedSource = ImportedSource> {
  readonly id: ImportSource;
  readonly label: string;
  readonly extensions: readonly string[];

  probe(input: ImportInput): Promise<ProbeResult> | ProbeResult;
  import(input: ImportInput): Promise<T>;
}

export interface ImportedSequenceSource {
  kind: "sequence";
  sequence: EmoteSequence;
  fileName: string;
}

export type ImportedSource = ImportedProject | ImportedSequenceSource;

export function isImportedSequence(source: ImportedSource): source is Extract<ImportedSource, { kind: "sequence" }> {
  return "kind" in source && source.kind === "sequence";
}

export interface ImportAdapterLoader extends Pick<ImportAdapter, "id" | "label" | "extensions"> {
  load(): Promise<ImportAdapter>;
}
