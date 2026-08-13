const RESERVED_KEYS = new Set(["name", "description"]);

export function addMetadataEntry(metadata: Record<string, unknown>): Record<string, unknown> {
  let index = 1;
  while (Object.hasOwn(metadata, `custom_${index}`)) index++;
  return { ...metadata, [`custom_${index}`]: "" };
}

export function renameMetadataEntry(
  metadata: Record<string, unknown>,
  previousKey: string,
  nextKeyInput: string,
): Record<string, unknown> {
  const nextKey = nextKeyInput.trim();
  if (!nextKey) throw new Error("Metadata key must not be empty.");
  if (RESERVED_KEYS.has(nextKey)) throw new Error(`${nextKey} is edited in its dedicated field.`);
  if (nextKey !== previousKey && Object.hasOwn(metadata, nextKey)) throw new Error("Metadata key already exists.");
  return Object.fromEntries(Object.entries(metadata).map(([key, value]) => [key === previousKey ? nextKey : key, value]));
}

export function parseMetadataJson(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    throw new Error("Value must be valid JSON. Strings need double quotes.");
  }
}
