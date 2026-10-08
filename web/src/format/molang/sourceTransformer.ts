import { quoteMolangString, scanMolangSource } from "./sourceScanner";

export function rewriteMolangIdentifiers(source: string, rewrite: (identifier: string) => string | undefined): string {
  return rewriteTokens(source, scanMolangSource(source)
    .filter((token) => token.kind === "identifier")
    .map((token) => ({ start: token.start, end: token.end, replacement: rewrite(token.value) })));
}

export function rewriteMolangStringLiterals(source: string, rewrite: (value: string) => string | undefined): string {
  return rewriteTokens(source, scanMolangSource(source)
    .filter((token) => token.kind === "string")
    .map((token) => {
      const replacement = rewrite(token.value);
      return { start: token.start, end: token.end, replacement: replacement === undefined ? undefined : quoteMolangString(replacement, token.quote) };
    }));
}

export function mapMolangResult(source: string, transform: (value: string) => string): string {
  const tokens = scanMolangSource(source);
  const masked = source.split("");
  for (const token of tokens) {
    if (token.kind === "identifier") continue;
    for (let index = token.start; index < token.end; index++) masked[index] = " ";
  }
  const returns = tokens.filter((token) => token.kind === "identifier" && token.value.toLowerCase() === "return");
  if (returns.length) {
    const replacements = returns.map((token) => {
      let depth = 0;
      let end = token.end;
      for (; end < source.length; end++) {
        const character = masked[end];
        if (depth === 0 && (character === ";" || character === "}")) break;
        if ("({[".includes(character)) depth++;
        if (")}]".includes(character)) depth--;
      }
      return { start: token.end, end, replacement: ` ${transform(source.slice(token.end, end).trim())}` };
    });
    return rewriteTokens(source, replacements);
  }
  let end = source.length;
  while (end > 0 && (masked[end - 1] === ";" || /\s/.test(masked[end - 1]))) end--;
  let start = 0;
  let depth = 0;
  for (let index = 0; index < end; index++) {
    const character = masked[index];
    if ("({[".includes(character)) depth++;
    if (")}]".includes(character)) depth--;
    if (character === ";" && depth === 0) start = index + 1;
  }
  if (start === 0) return transform(source);
  return `${source.slice(0, start)} return ${transform(source.slice(start, end).trim())};`;
}

function rewriteTokens(
  source: string,
  replacements: ReadonlyArray<{ start: number; end: number; replacement: string | undefined }>,
): string {
  let result = "";
  let offset = 0;
  for (const replacement of replacements) {
    if (replacement.replacement === undefined) continue;
    result += source.slice(offset, replacement.start) + replacement.replacement;
    offset = replacement.end;
  }
  return result + source.slice(offset);
}
