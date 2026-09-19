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
