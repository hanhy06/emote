export interface SnbtField {
  name: string;
  value: string;
  raw: string;
}

export function serializeSnbtCompound(entries: Iterable<readonly [string, string | undefined]>): string {
  const fields: string[] = [];
  for (const [name, value] of entries) {
    if (value !== undefined) fields.push(`${serializeSnbtKey(name)}:${value}`);
  }
  return `{${fields.join(",")}}`;
}

export function serializeSnbtString(value: string): string {
  return JSON.stringify(value);
}

export function parseSnbtCompound(compound: string, label = "SNBT compound"): SnbtField[] {
  const trimmed = compound.trim();
  if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) throw new Error(`${label} must be a compound.`);
  return splitSnbtTopLevel(trimmed.slice(1, -1)).map((raw) => {
    const separator = findTopLevelSeparator(raw, ":");
    if (separator < 0) throw new Error(`${label} contains a field without a value: ${raw}`);
    return {
      name: parseSnbtKey(raw.slice(0, separator).trim(), label),
      value: raw.slice(separator + 1).trim(),
      raw,
    };
  });
}

export function readSnbtRawField(compound: string, name: string): string | null {
  return parseSnbtCompound(compound).find((field) => field.name === name)?.value ?? null;
}

export function readSnbtStringField(compound: string, name: string): string | null {
  const raw = readSnbtRawField(compound, name);
  if (!raw) return null;
  if (raw.startsWith('"') && raw.endsWith('"')) {
    try {
      const value = JSON.parse(raw) as unknown;
      return typeof value === "string" ? value : null;
    } catch {
      return null;
    }
  }
  if (raw.startsWith("'") && raw.endsWith("'")) return raw.slice(1, -1).replaceAll("\\'", "'").replaceAll("\\\\", "\\");
  return null;
}

export function readSnbtCompoundField(compound: string, name: string): string | null {
  const raw = readSnbtRawField(compound, name);
  return raw?.startsWith("{") && raw.endsWith("}") ? raw : null;
}

export function omitSnbtFields(compound: string, omittedNames: ReadonlySet<string>): string | undefined {
  const fields = parseSnbtCompound(compound).filter((field) => !omittedNames.has(field.name));
  return fields.length ? `{${fields.map((field) => field.raw).join(",")}}` : undefined;
}

export function splitSnbtTopLevel(text: string): string[] {
  const parts: string[] = [];
  let start = 0;
  let braces = 0;
  let brackets = 0;
  let quote: '"' | "'" | null = null;
  let escaped = false;
  for (let index = 0; index < text.length; index++) {
    const character = text[index];
    if (quote) {
      if (escaped) escaped = false;
      else if (character === "\\") escaped = true;
      else if (character === quote) quote = null;
    } else if (character === '"' || character === "'") quote = character;
    else if (character === "{") braces++;
    else if (character === "}") braces--;
    else if (character === "[") brackets++;
    else if (character === "]") brackets--;
    else if (character === "," && braces === 0 && brackets === 0) {
      parts.push(text.slice(start, index).trim());
      start = index + 1;
    }
    if (braces < 0 || brackets < 0) throw new Error("SNBT contains an unmatched closing delimiter.");
  }
  if (quote || braces !== 0 || brackets !== 0) throw new Error("SNBT contains an unclosed string or delimiter.");
  parts.push(text.slice(start).trim());
  return parts.filter(Boolean);
}

export function splitSnbtPair(text: string, separator: ":" | "="): [string, string] | null {
  const index = findTopLevelSeparator(text, separator);
  return index < 0 ? null : [text.slice(0, index).trim(), text.slice(index + 1).trim()];
}

export function findMatchingSnbtDelimiter(text: string, start: number, open: "{" | "[", close: "}" | "]"): number {
  let depth = 0;
  let quote: '"' | "'" | null = null;
  let escaped = false;
  for (let index = start; index < text.length; index++) {
    const character = text[index];
    if (quote) {
      if (escaped) escaped = false;
      else if (character === "\\") escaped = true;
      else if (character === quote) quote = null;
    } else if (character === '"' || character === "'") quote = character;
    else if (character === open) depth++;
    else if (character === close && --depth === 0) return index;
  }
  throw new Error(`Unclosed ${open} in SNBT.`);
}

function findTopLevelSeparator(text: string, separator: ":" | "="): number {
  let braces = 0;
  let brackets = 0;
  let quote: '"' | "'" | null = null;
  let escaped = false;
  for (let index = 0; index < text.length; index++) {
    const character = text[index];
    if (quote) {
      if (escaped) escaped = false;
      else if (character === "\\") escaped = true;
      else if (character === quote) quote = null;
    } else if (character === '"' || character === "'") quote = character;
    else if (character === "{") braces++;
    else if (character === "}") braces--;
    else if (character === "[") brackets++;
    else if (character === "]") brackets--;
    else if (character === separator && braces === 0 && brackets === 0) return index;
  }
  return -1;
}

function serializeSnbtKey(value: string): string {
  return /^[A-Za-z0-9._+-]+$/.test(value) ? value : serializeSnbtString(value);
}

function parseSnbtKey(value: string, label: string): string {
  if (value.startsWith('"') && value.endsWith('"')) {
    try {
      const parsed = JSON.parse(value) as unknown;
      if (typeof parsed === "string") return parsed;
    } catch {
      // Report the common invalid-key error below.
    }
  } else if (value.startsWith("'") && value.endsWith("'")) {
    return value.slice(1, -1).replaceAll("\\'", "'").replaceAll("\\\\", "\\");
  } else if (/^[A-Za-z0-9._+-]+$/.test(value)) {
    return value;
  }
  throw new Error(`${label} contains an invalid key: ${value}`);
}
