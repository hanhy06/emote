export type Matrix16 = readonly [
  number, number, number, number,
  number, number, number, number,
  number, number, number, number,
  number, number, number, number,
];

export type MinecraftTime = string;

export interface EmoteAnimation {
  type: "animation";
  schema_version: 3;
  id: string;
  metadata: EmoteMetadata;
  settings: EmoteAnimationSettings;
  nodes: Record<string, EmoteNode>;
  timeline: EmoteTimeline;
}

export interface EmoteMetadata {
  name: string;
  description: string;
  [key: string]: unknown;
}

export interface EmoteAnimationSettings {
  standalone: boolean;
  cooldown: MinecraftTime;
  player: EmotePlayerBehavior;
  playback: {
    mode: "once" | "hold" | "loop" | "server_sync";
    loop_delay: MinecraftTime;
  };
}

export interface EmotePlayerBehavior {
  hidden: boolean;
  stop_conditions: {
    movement_distance: number;
    jump: boolean;
    submerge: boolean;
    ride: boolean;
    damage: boolean;
    attack: boolean;
    game_mode_change: boolean;
  };
}

export function createDefaultPlayerBehavior(): EmotePlayerBehavior {
  return {
    hidden: true,
    stop_conditions: {
      movement_distance: 0.1,
      jump: true,
      submerge: true,
      ride: true,
      damage: true,
      attack: true,
      game_mode_change: true,
    },
  };
}

export type NodeSpace = "scene" | "initiator" | "partner";
export type Participant = "initiator" | "partner";

interface EmoteNodeBase {
  space: NodeSpace;
  visible?: boolean;
  default_matrix: Matrix16;
  entity_nbt?: string;
}

export type EmoteNode =
  | (EmoteNodeBase & {
    type: "item_display";
    item_stack_snbt: string;
    item_display: string;
    skin?: { participant: Participant; part: "head" | "body" | "left_arm" | "right_arm" | "left_leg" | "right_leg"; order: number };
  })
  | (EmoteNodeBase & { type: "block_display"; block_state_snbt: string })
  | (EmoteNodeBase & { type: "text_display"; text: unknown })
  | { type: "anchor"; space: NodeSpace; default_matrix: Matrix16 };

export interface EmoteTimeline {
  duration: MinecraftTime;
  keyframes: EmoteKeyframe[];
  events?: {
    start?: EmoteEvent[];
    timeline?: EmoteTimelineEvent[];
    loop?: EmoteEvent[];
    stop?: EmoteEvent[];
  };
}

export interface EmoteKeyframe {
  time: MinecraftTime;
  interpolation_duration?: MinecraftTime;
  node_transforms?: Record<string, { matrix: Matrix16; interpolation_duration?: MinecraftTime }>;
  node_states?: Record<string, { visible: boolean }>;
}

export interface EmoteEvent {
  source: { type: "player" | "server" } | { type: "node"; node: string };
  origin: { type: "root"; offset?: readonly [number, number, number] }
    | { type: "node"; node: string; offset?: readonly [number, number, number] };
  commands: string[];
}

export interface EmoteTimelineEvent extends EmoteEvent {
  time: MinecraftTime;
}
