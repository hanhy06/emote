import type { EmoteEvent, EmoteMetadata, EmotePlayerBehavior, Matrix16, NodeSpace, Participant, PlayerSkinPart } from "../format/emoteAnimation";
import type { BlockStateData, ItemStackData } from "./minecraftData";
import type { GeneratedResource } from "./generatedResource";
import type { ConversionIssue } from "../foundation/diagnostics";
import type { SourceNodeBinding } from "./nodeBindings";
import type { PreviewNodeTrack, PreviewProjection, PreviewTransformKeyframe, PreviewVisibilityKeyframe } from "./previewProjection";
import type { AnimationRuntimeData, RuntimeExportAvailability } from "./runtimeProjection";

export type { PreviewAvailability, PreviewInterpolation, PreviewNodeTrack, PreviewProjection, PreviewTransformKeyframe, PreviewVisibilityKeyframe } from "./previewProjection";

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
  binding: SourceNodeBinding;
  defaultMatrix: Matrix16;
  visible: boolean;
  entityNbt?: string;
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
  sourceReferenceId?: string;
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
  preview: PreviewProjection;
  exportAvailability: RuntimeExportAvailability;
  runtime: AnimationRuntimeData;
}

export type ImportedTransformKeyframe = PreviewTransformKeyframe;
export type ImportedVisibilityKeyframe = PreviewVisibilityKeyframe;

export interface ImportedTimelineEvent extends EmoteEvent {
  tick: number;
}

export type ImportDiagnostic = ConversionIssue;
