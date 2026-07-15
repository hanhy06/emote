import type { ImportAdapter, ImportInput, ProbeResult } from "./adapter";

export interface DetectedAdapter {
  adapter: ImportAdapter;
  probe: ProbeResult;
}

export async function detectAdapter(adapters: readonly ImportAdapter[], input: ImportInput): Promise<DetectedAdapter> {
  const matches = (await Promise.all(adapters.map(async (adapter) => ({
    adapter,
    probe: await adapter.probe(input),
  }))))
    .filter((match) => match.probe.confidence > 0)
    .sort((first, second) => second.probe.confidence - first.probe.confidence);

  if (matches.length === 0) {
    throw new Error(`Unsupported input format: ${input.name}`);
  }
  if (matches.length > 1 && matches[0].probe.confidence === matches[1].probe.confidence) {
    throw new Error(`Input format is ambiguous between ${matches[0].adapter.label} and ${matches[1].adapter.label}.`);
  }
  return matches[0];
}
