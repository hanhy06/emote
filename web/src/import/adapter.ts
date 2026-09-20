import type { ImportedProject, ImportSource } from "../domain/conversionSeed";

export interface ImportInput {
  name: string;
  bytes: Uint8Array;
}

export interface ProbeResult {
  confidence: number;
  reason: string;
}

export interface ImportAdapter<T extends ImportedProject = ImportedProject> {
  readonly id: ImportSource;
  readonly label: string;
  readonly extensions: readonly string[];

  probe(input: ImportInput): Promise<ProbeResult> | ProbeResult;
  import(input: ImportInput): Promise<T>;
}

export interface ImportAdapterLoader extends Pick<ImportAdapter, "id" | "label" | "extensions"> {
  load(): Promise<ImportAdapter>;
}
