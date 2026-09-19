export type MolangSourceToken =
  | { kind: "identifier"; start: number; end: number; value: string }
  | { kind: "string"; start: number; end: number; quote: "'" | '"'; encoded: string; value: string }
  | { kind: "comment"; start: number; end: number };

export function scanMolangSource(source: string): MolangSourceToken[] {
  const tokens: MolangSourceToken[] = [];
  for (let index = 0; index < source.length;) {
    const start = index;
    const character = source[index];
    if (character === "'" || character === '"') {
      const quote = character;
      index++;
      while (index < source.length) {
        if (source[index] === "\\") index += Math.min(2, source.length - index);
        else if (source[index++] === quote) break;
      }
      const encoded = source.slice(start + 1, source[index - 1] === quote ? index - 1 : index);
      tokens.push({ kind: "string", start, end: index, quote, encoded, value: decodeMolangString(encoded) });
      continue;
    }
    if (source.startsWith("//", index)) {
      index = source.indexOf("\n", index + 2);
      if (index < 0) index = source.length;
      tokens.push({ kind: "comment", start, end: index });
      continue;
    }
    if (source.startsWith("/*", index)) {
      const end = source.indexOf("*/", index + 2);
      index = end < 0 ? source.length : end + 2;
      tokens.push({ kind: "comment", start, end: index });
      continue;
    }
    if (isIdentifierStart(character)) {
      index++;
      while (index < source.length && isIdentifierPart(source[index])) index++;
      tokens.push({ kind: "identifier", start, end: index, value: source.slice(start, index) });
      continue;
    }
    index++;
  }
  return tokens;
}

export function nextMolangSourceCharacter(source: string, offset: number): string | undefined {
  for (let index = offset; index < source.length; index++) {
    if (!/\s/.test(source[index])) return source[index];
  }
  return undefined;
}

export function quoteMolangString(value: string, quote: "'" | '"'): string {
  const escaped = value
    .replaceAll("\\", "\\\\")
    .replaceAll(quote, `\\${quote}`)
    .replaceAll("\n", "\\n")
    .replaceAll("\r", "\\r")
    .replaceAll("\t", "\\t")
    .replaceAll("\b", "\\b")
    .replaceAll("\f", "\\f");
  return `${quote}${escaped}${quote}`;
}

function decodeMolangString(value: string): string {
  return value.replace(/\\([\\'"nrtbf])/g, (_escape, character: string) => {
    if (character === "n") return "\n";
    if (character === "r") return "\r";
    if (character === "t") return "\t";
    if (character === "b") return "\b";
    if (character === "f") return "\f";
    return character;
  });
}

function isIdentifierStart(value: string | undefined): boolean {
  return value !== undefined && /[A-Za-z_]/.test(value);
}

function isIdentifierPart(value: string): boolean {
  return /[A-Za-z0-9_.]/.test(value);
}
