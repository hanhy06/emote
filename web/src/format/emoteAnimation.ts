export type Matrix16 = readonly [
  number, number, number, number,
  number, number, number, number,
  number, number, number, number,
  number, number, number, number,
];

export type Vec3 = readonly [number, number, number];
export type MolangScalar = number | string;
export type MinecraftTime = string;

export interface EmoteAnimation {
  type: "animation";
  schema_version: 4;
  id: string;
  metadata: EmoteMetadata;
  settings: EmoteAnimationSettings;
  molang?: { initialize?: string; tick?: string };
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

export interface LocalTransform {
  position: Vec3;
  rotation: Vec3;
  scale: Vec3;
}

interface EmoteNodeBase {
  parent?: string;
  space?: NodeSpace;
  transform: LocalTransform;
}

interface EmoteDisplayNodeBase extends EmoteNodeBase {
  visible?: boolean;
  entity_nbt?: string;
}

export type EmoteNode =
  | (EmoteDisplayNodeBase & {
    type: "item_display";
    item_stack_snbt: string;
    item_display: string;
    skin?: { participant: Participant; part: "head" | "body" | "left_arm" | "right_arm" | "left_leg" | "right_leg"; order: number };
  })
  | (EmoteDisplayNodeBase & { type: "block_display"; block_state_snbt: string })
  | (EmoteDisplayNodeBase & { type: "text_display"; text: unknown })
  | (EmoteNodeBase & { type: "anchor" });

export interface EmoteTimeline {
  duration: MinecraftTime;
  tracks: Record<string, EmoteNodeTracks>;
  events?: EmoteEvents;
}

export interface EmoteNodeTracks {
  position?: EmoteVectorKeyframe[];
  rotation?: EmoteVectorKeyframe[];
  scale?: EmoteVectorKeyframe[];
  visible?: EmoteVisibilityKeyframe[];
}

export type EmoteInterpolation = "step" | "linear";
export type EmoteEasing =
  | "linear"
  | "ease_in_sine" | "ease_out_sine" | "ease_in_out_sine"
  | "ease_in_quad" | "ease_out_quad" | "ease_in_out_quad"
  | "ease_in_cubic" | "ease_out_cubic" | "ease_in_out_cubic"
  | "ease_in_quart" | "ease_out_quart" | "ease_in_out_quart"
  | "ease_in_quint" | "ease_out_quint" | "ease_in_out_quint"
  | "ease_in_expo" | "ease_out_expo" | "ease_in_out_expo"
  | "ease_in_circ" | "ease_out_circ" | "ease_in_out_circ"
  | "ease_in_back" | "ease_out_back" | "ease_in_out_back"
  | "ease_in_elastic" | "ease_out_elastic" | "ease_in_out_elastic"
  | "ease_in_bounce" | "ease_out_bounce" | "ease_in_out_bounce";

export interface EmoteVectorKeyframe {
  time: MinecraftTime;
  value?: readonly [MolangScalar, MolangScalar, MolangScalar];
  pre?: readonly [MolangScalar, MolangScalar, MolangScalar];
  post?: readonly [MolangScalar, MolangScalar, MolangScalar];
  interpolation?: EmoteInterpolation;
  easing?: EmoteEasing;
}

export interface EmoteVisibilityKeyframe {
  time: MinecraftTime;
  value: boolean | string;
}

export interface EmoteEvents {
  start?: EmoteEvent[];
  timeline?: EmoteTimelineEvent[];
  loop?: EmoteEvent[];
  stop?: EmoteEvent[];
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

// Schema 3 is import-only. New conversion output always uses Schema 4.
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
