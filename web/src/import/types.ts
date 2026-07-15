import type { EmoteEvent, EmoteMetadata, Matrix16 } from "../format/emoteAnimation";
import type { ConversionIssue } from "./errors";

export type ImportSource = "bd_project" | "animated_java_json" | "emote_json";

export interface ImportedProject {
  source: ImportSource;
  sourceName: string;
  suggestedMetadata: EmoteMetadata;
  suggestedMinecraftVersion?: string;
  suggestedNamespace?: string;
  nodes: Record<string, ImportedNode>;
  animations: ImportedAnimation[];
  diagnostics: ImportDiagnostic[];
  artifacts: Map<string, Uint8Array>;
  artifactMinecraftVersion?: string;
}

export interface ImportedNodeBase {
  id: string;
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
  durationTicks: number;
  loop: "once" | "loop";
  loopDelayTicks: number;
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
  tick: number;
  matrix: Matrix16;
  interpolation: ImportedInterpolation;
}

export type ImportedInterpolation =
  | { type: "step" }
  | { type: "linear"; durationTicks?: number };

export interface ImportedVisibilityKeyframe {
  tick: number;
  visible: boolean;
}

export interface ImportedTimelineEvent extends EmoteEvent {
  tick: number;
}

export type ImportDiagnostic = ConversionIssue;
