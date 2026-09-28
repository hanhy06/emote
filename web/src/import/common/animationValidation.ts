import type { ImportDiagnostic } from "../../domain/conversionSeed";
import { skippedAnimationIssue } from "../../foundation/diagnostics";

export interface ValidatedAnimations<T> {
  animations: T[];
  sourceIndices: number[];
  diagnostics: ImportDiagnostic[];
}

export function validateSourceAnimations<T>(
  entries: readonly unknown[],
  validate: (entry: unknown, path: string) => void,
): ValidatedAnimations<T> {
  const animations: T[] = [];
  const sourceIndices: number[] = [];
  const diagnostics: ImportDiagnostic[] = [];
  entries.forEach((entry, index) => {
    const path = `animations[${index}]`;
    try {
      validate(entry, path);
      animations.push(entry as T);
      sourceIndices.push(index);
    } catch (reason) {
      const name = typeof entry === "object" && entry !== null && "name" in entry && typeof entry.name === "string"
        ? entry.name : `Animation ${index + 1}`;
      diagnostics.push(skippedAnimationIssue(name, path, reason));
    }
  });
  return { animations, sourceIndices, diagnostics };
}
