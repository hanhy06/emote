import type { ImportDiagnostic } from "../../domain/conversionSeed";
import type { PreviewProjection } from "../../domain/previewProjection";
import { PreviewUnavailableError } from "../../foundation/diagnostics";

export interface PreviewFallback {
  preview: PreviewProjection;
  diagnostic: ImportDiagnostic;
}

export function createMolangPreviewFallback(
  animationName: string,
  durationTicks: number,
  reason: PreviewUnavailableError,
): PreviewFallback {
  const message = `${animationName}: some Molang could not be evaluated for preview. The converted file keeps the original expression, but the Create pose preview may not match in-game playback.`;
  return {
    preview: {
      durationTicks,
      tracks: {},
      availability: { status: "create_pose", reason: message },
    },
    diagnostic: {
      severity: "warning",
      code: "molang_preview_limited",
      message,
      sourcePath: reason.sourcePath,
    },
  };
}
