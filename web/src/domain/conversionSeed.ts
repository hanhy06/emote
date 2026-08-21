import type { EmoteAnimation, EmoteEvent, EmoteMetadata, EmoteNode, EmotePlayerBehavior, EmoteTimeline, HeldItemArm, Matrix16, NodeSpace, Participant } from "../format/emoteAnimation";
import type { ConversionIssue } from "../foundation/diagnostics";

// Source adapters produce this neutral seed; the editable document consumes it once.

export type ImportSource = "bd_project" | "bd_datapack" | "animated_java_json" | "geckolib_bbmodel" | "bedrock_animation_json" | "emote_json" | "emote_sequence";

export interface ImportedProject {
  source: ImportSource;
  sourceName: string;
  suggestedMetadata: EmoteMetadata;
  suggestedPlayer: EmotePlayerBehavior;
  suggestedMinecraftVersion?: string;
  suggestedNamespace?: string;
  suggestedStandalone?: boolean;
  suggestedCooldown?: string;
  nodes: Record<string, ImportedNode>;
  animations: ImportedAnimation[];
  diagnostics: ImportDiagnostic[];
  resources: Map<string, Uint8Array>;
  resourceMinecraftVersion?: string;
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
    itemStackSnbt: string;
    itemDisplay: string;
    skin?: ImportedSkinPart;
    suggestedSkin?: ImportedSkinPart;
    playerHeadConversion?: { matrix: Matrix16 };
  })
  | (ImportedNodeBase & { type: "block_display"; blockStateSnbt: string })
  | (ImportedNodeBase & { type: "text_display"; text: unknown })
  | (Omit<ImportedNodeBase, "visible" | "entityNbt"> & { type: "anchor"; suggestedHeldItemArm?: HeldItemArm });

export interface ImportedSkinPart {
  participant?: Participant;
  part: "head" | "body" | "left_arm" | "right_arm" | "left_leg" | "right_leg";
  order: number;
}

export interface ImportedAnimation {
  id: string;
  name: string;
  suggestedMetadata?: EmoteMetadata;
  durationTicks: number;
  loop: "once" | "hold" | "loop" | "server_sync";
  loopDelayTicks: number;
  tracks: Record<string, ImportedNodeTrack>;
  events: {
    start: EmoteEvent[];
    timeline: ImportedTimelineEvent[];
    loop: EmoteEvent[];
    stop: EmoteEvent[];
  };
  availability?: ImportedAnimationAvailability;
  preview?: {
    durationTicks: number;
    tracks: Record<string, ImportedNodeTrack>;
  };
  runtime?: {
    molang?: EmoteAnimation["molang"];
    nodes: Record<string, EmoteNode>;
    timeline: EmoteTimeline;
  };
}

export interface ImportedAnimationAvailability {
  preview: "full" | "create_pose" | "unavailable";
  exportable: boolean;
  reason?: string;
}

export const DEFAULT_ANIMATION_AVAILABILITY: ImportedAnimationAvailability = {
  preview: "full",
  exportable: true,
};

export function animationAvailability(animation: ImportedAnimation): ImportedAnimationAvailability {
  return animation.availability ?? DEFAULT_ANIMATION_AVAILABILITY;
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
