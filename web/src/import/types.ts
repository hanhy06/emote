import type { EmoteEvent, EmoteMetadata, Matrix16 } from "../format/emoteAnimation";

export type ImportSource = "bd_datapack" | "bd_project" | "animated_java_json";

export interface ImportedProject {
  source: ImportSource;
  sourceName: string;
  suggestedMetadata: EmoteMetadata;
  nodes: Record<string, ImportedNode>;
  animations: ImportedAnimation[];
  diagnostics: ImportDiagnostic[];
  artifacts: ImportedArtifact[];
}

export interface ImportedNodeBase {
  id: string;
  parentId: string | null;
  defaultMatrix: Matrix16;
  visible: boolean;
  entityNbt?: string;
}

export type ImportedNode =
  | (ImportedNodeBase & {
    type: "item_display";
    itemStackSnbt: string;
    itemDisplay: string;
    skin?: ImportedSkinPart;
  })
  | (ImportedNodeBase & { type: "block_display"; blockStateSnbt: string })
  | (ImportedNodeBase & { type: "text_display"; text: unknown })
  | (Omit<ImportedNodeBase, "visible" | "entityNbt"> & { type: "anchor" });

export interface ImportedSkinPart {
  part: "head" | "body" | "left_arm" | "right_arm" | "left_leg" | "right_leg";
  order: number;
}

export interface ImportedAnimation {
  id: string;
  name: string;
  durationSeconds: number;
  loop: "once" | "hold" | "loop";
  loopDelaySeconds: number;
  tracks: Record<string, ImportedNodeTrack>;
  events: {
    start: EmoteEvent[];
    timeline: ImportedTimelineEvent[];
    loop: EmoteEvent[];
    stop: EmoteEvent[];
  };
}

export interface ImportedNodeTrack {
  transforms: ImportedTransformKeyframe[];
  visibility: ImportedVisibilityKeyframe[];
}

export interface ImportedTransformKeyframe {
  timeSeconds: number;
  matrix: Matrix16;
  interpolation: ImportedInterpolation;
}

export type ImportedInterpolation =
  | { type: "step" }
  | { type: "linear" }
  | { type: "sampled_linear" };

export interface ImportedVisibilityKeyframe {
  timeSeconds: number;
  visible: boolean;
}

export interface ImportedTimelineEvent extends EmoteEvent {
  timeSeconds: number;
}

export interface ImportDiagnostic {
  severity: "warning" | "error";
  code: string;
  message: string;
  sourcePath?: string;
}

export interface ImportedArtifact {
  path: string;
  data: Uint8Array;
}
