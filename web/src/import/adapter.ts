import type { ImportedProject, ImportSource } from "../domain/conversionSeed";
import type { ImportedSequence } from "../domain/emoteDefinition";
import type { ImportInput, ProbeResult } from "./input";

export type { ImportInput, ProbeResult } from "./input";

export type ImportedSource = ImportedProject | ImportedSequence;

export function isImportedSequence(source: ImportedSource): source is ImportedSequence {
  return "kind" in source && source.kind === "sequence";
}

export interface ImportAdapter<T extends ImportedSource = ImportedSource> {
  readonly id: ImportSource;
  readonly label: string;
  readonly extensions: readonly string[];

  probe(input: ImportInput): Promise<ProbeResult> | ProbeResult;
  import(input: ImportInput): Promise<T>;
}

export interface ImportAdapterLoader extends Pick<ImportAdapter, "id" | "label" | "extensions"> {
  load(): Promise<ImportAdapter>;
}
