import type { EmoteAnimation, EmoteEvent, EmoteMetadata, EmotePlayerBehavior, Matrix16, NodeSpace, Participant, PlayerSkinPart } from "../format/emoteAnimation";
import type { BlockStateData, DisplayNbtPatch, ItemStackData, RuntimeNode, RuntimeNodeTracks } from "./minecraftData";
import type { GeneratedResource } from "./generatedResource";
import type { ConversionIssue } from "../foundation/diagnostics";

// Source adapters produce this neutral seed; the editable document consumes it once.

export type ImportSource = "bd_datapack" | "animated_java_blueprint" | "geckolib_bbmodel" | "bedrock_animation_json" | "emotecraft_binary" | "emote_json" | "emote_sequence";

export interface ImportedProject {
  source: ImportSource;
  sourceName: string;
  suggestedMetadata: EmoteMetadata;
  suggestedPlayer: EmotePlayerBehavior;
  suggestedMinecraftVersion?: string;
  suggestedNamespace?: string;
  suggestedStandalone?: boolean;
  suggestedCooldown?: string;
  suggestedRotationDeadzone?: number;
  suggestedDisplayInterpolation?: string;
  nodes: Record<string, ImportedNode>;
  animations: ImportedAnimation[];
  diagnostics: ImportDiagnostic[];
  resources: Map<string, GeneratedResource>;
}

export interface ImportedNodeBase {
  id: string;
  defaultMatrix: Matrix16;
  visible: boolean;
  entityNbt?: string;
  skinAssignmentGroup?: string;
  spaceAssignmentGroup?: string;
  space?: NodeSpace;
}

export type ImportedNode =
  | (ImportedNodeBase & {
    type: "item_display";
    itemStack: ItemStackData;
    itemDisplay: string;
    skin?: ImportedSkinPart;
    suggestedSkin?: ImportedSkinPart;
    playerHeadConversion?: { matrix: Matrix16 };
  })
  | (ImportedNodeBase & { type: "block_display"; blockState: BlockStateData })
  | (ImportedNodeBase & { type: "text_display"; text: unknown })
  | (Omit<ImportedNodeBase, "visible" | "entityNbt"> & { type: "anchor" });

export interface ImportedSkinPart {
  participant?: Participant;
  part: PlayerSkinPart;
  order: number;
}

export interface ImportedAnimation {
  id: string;
  name: string;
  suggestedMetadata?: EmoteMetadata;
  durationTicks: number;
  playbackMode: "once" | "hold" | "loop" | "server_sync";
  loopStartTicks?: number;
  loopEndTicks?: number;
  loopDelayTicks: number;
  events: {
    start: EmoteEvent[];
    timeline: ImportedTimelineEvent[];
    loop: EmoteEvent[];
    stop: EmoteEvent[];
  };
  preview: {
    durationTicks: number;
    tracks: Record<string, ImportedNodeTrack>;
    availability: ImportedPreviewAvailability;
  };
  exportAvailability: ImportedExportAvailability;
  runtime: ImportedAnimationRuntime;
}

export type ImportedAnimationRuntime =
  | {
    kind: "baked";
    tracks: Record<string, ImportedNodeTrack>;
  }
  | {
    kind: "native";
    molang?: EmoteAnimation["molang"];
    nodes: Record<string, RuntimeNode>;
    tracks: Record<string, RuntimeNodeTracks>;
    bindings: NativeRuntimeBindings;
  };

export interface NativeRuntimeBindings {
  editorNodeByRuntimeNode: Record<string, string>;
  spaceGroupByRuntimeRoot: Record<string, string>;
}

export interface ImportedPreviewAvailability {
  preview: "full" | "create_pose" | "unavailable";
  reason?: string;
}

export interface ImportedExportAvailability {
  exportable: boolean;
  reason?: string;
}

export const DEFAULT_PREVIEW_AVAILABILITY: ImportedPreviewAvailability = {
  preview: "full",
};

export const DEFAULT_EXPORT_AVAILABILITY: ImportedExportAvailability = {
  exportable: true,
};

export function animationPreviewAvailability(animation: ImportedAnimation): ImportedPreviewAvailability {
  return animation.preview.availability;
}

export function animationExportAvailability(animation: ImportedAnimation): ImportedExportAvailability {
  return animation.exportAvailability;
}

export interface ImportedNodeTrack {
  transforms: ImportedTransformKeyframe[];
  visibility: ImportedVisibilityKeyframe[];
  nbt: ImportedNbtKeyframe[];
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

export interface ImportedNbtKeyframe {
  tick: number;
  value: DisplayNbtPatch;
}

export interface ImportedTimelineEvent extends EmoteEvent {
  tick: number;
}

export type ImportDiagnostic = ConversionIssue;
