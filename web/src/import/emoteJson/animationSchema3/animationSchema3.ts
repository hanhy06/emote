import type {
  EmoteAnimationSettings,
  EmoteEvents,
  EmoteMetadata,
  Matrix16,
  MinecraftTime,
  NodeSpace,
  Participant,
} from "../../../format/emoteAnimation";

export interface Schema3EmoteAnimation {
  type: "animation";
  schema_version: 3;
  id: string;
  metadata: EmoteMetadata;
  settings: EmoteAnimationSettings;
  nodes: Record<string, Schema3EmoteNode>;
  timeline: Schema3EmoteTimeline;
}

interface Schema3EmoteNodeBase {
  space: NodeSpace;
  visible?: boolean;
  default_matrix: Matrix16;
  entity_nbt?: string;
}

export type Schema3EmoteNode =
  | (Schema3EmoteNodeBase & {
    type: "item_display";
    item_stack_snbt: string;
    item_display: string;
    skin?: { participant: Participant; part: "head" | "body" | "left_arm" | "right_arm" | "left_leg" | "right_leg"; order: number };
  })
  | (Schema3EmoteNodeBase & { type: "block_display"; block_state_snbt: string })
  | (Schema3EmoteNodeBase & { type: "text_display"; text: unknown })
  | { type: "anchor"; space: NodeSpace; default_matrix: Matrix16 };

export interface Schema3EmoteTimeline {
  duration: MinecraftTime;
  keyframes: Schema3EmoteKeyframe[];
  events?: EmoteEvents;
}

export interface Schema3EmoteKeyframe {
  time: MinecraftTime;
  interpolation_duration?: MinecraftTime;
  node_transforms?: Record<string, { matrix: Matrix16; interpolation_duration?: MinecraftTime }>;
  node_states?: Record<string, { visible: boolean }>;
}
