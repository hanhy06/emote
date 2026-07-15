import type { ImportAdapter, ImportInput, ProbeResult } from "./adapter";
import { ConversionError } from "./errors";
import type { ImportedProject } from "./types";

export interface DetectedAdapter {
  adapter: ImportAdapter;
  probe: ProbeResult;
}

export async function detectAdapter(adapters: readonly ImportAdapter[], input: ImportInput): Promise<DetectedAdapter> {
  const extension = input.name.toLowerCase().split(".").at(-1);
  const extensionMatches = extension ? adapters.filter((adapter) => adapter.extensions.includes(extension)) : [];
  const candidates = extensionMatches.length > 0 ? extensionMatches : adapters;
  const matches = (await Promise.all(candidates.map(async (adapter) => ({
    adapter,
    probe: await adapter.probe(input),
  }))))
    .filter((match) => match.probe.confidence > 0)
    .sort((first, second) => second.probe.confidence - first.probe.confidence);

  if (matches.length === 0) {
    throw new ConversionError("unsupported_input", `Unsupported input format: ${input.name}`, input.name);
  }
  if (matches.length > 1 && matches[0].probe.confidence === matches[1].probe.confidence) {
    throw new ConversionError("ambiguous_input", `Input format is ambiguous between ${matches[0].adapter.label} and ${matches[1].adapter.label}.`, input.name);
  }
  return matches[0];
}

export async function importDetected(detected: DetectedAdapter, input: ImportInput): Promise<ImportedProject> {
  try {
    return await detected.adapter.import(input);
  } catch (reason) {
    throw ConversionError.fromUnknown(
      reason,
      `${detected.adapter.id}_import_failed`,
      `Could not import ${input.name} as ${detected.adapter.label}.`,
      input.name,
    );
  }
}
