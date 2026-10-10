import type { EmoteCallback, EmoteMetadata, EmotePlayerBehavior } from "./emoteDefinition";

export type ScalarIR = number | { molang: string };
export type TimeValueIR = string | { molang: string };
export type VectorValueIR = readonly ScalarIR[];
export type VisibilityIR = boolean | { molang: string };
export type RotationOrderIR = "XYZ" | "XZY" | "YXZ" | "YZX" | "ZXY" | "ZYX";

export interface TransformOperationIR {
  id: string;
  op: "translate" | "rotate_euler" | "rotate_quaternion" | "scale" | "matrix";
  order?: RotationOrderIR;
  value: readonly number[];
}

export type AttachmentIR = {
  visible?: boolean;
  entity_nbt?: string;
} & (
  | { type: "item_display"; item_stack_snbt: string; item_display: string }
  | { type: "block_display"; block_state_snbt: string }
  | { type: "text_display"; text: unknown }
  | { type: "player_skin"; part: "head" | "body" | "left_arm" | "right_arm" | "left_leg" | "right_leg"; region: { from: number; to: number } }
  | { type: "external"; key: string; data: unknown }
);

export interface NodeIR {
  name?: string;
  source?: Record<string, unknown>;
  parent?: string;
  inherit?: { rotation?: "parent" | "entity"; scale?: boolean; visibility?: boolean };
  visible?: boolean;
  transform?: TransformOperationIR[];
  attachments?: Record<string, AttachmentIR>;
}

export interface EasingIR {
  kernel: "linear" | "power" | "sine" | "expo" | "circ" | "back" | "blockbench_elastic" | "blockbench_bounce" | "steps";
  direction?: "in" | "out" | "in_out";
  exponent?: number;
  overshoot?: number;
  frequency?: number;
  bounciness?: number;
  count?: number;
}

export interface SegmentIR {
  interpolation: "step" | "linear" | "slerp" | "catmull_rom" | "hermite" | "bezier";
  easing?: EasingIR;
  tension?: number;
  previous?: VectorValueIR;
  following?: VectorValueIR;
  out_tangent?: VectorValueIR;
  in_tangent?: VectorValueIR;
  handles?: { out: { time: number; value: ScalarIR }; in: { time: number; value: ScalarIR } }[];
}

export type CurveKeyIR = { time: string } & ({ value: VectorValueIR; pre?: never; post?: never } | { pre: VectorValueIR; post: VectorValueIR; value?: never });
export interface CurveIR {
  type: "curve";
  before?: "base" | "first_pre";
  keys: CurveKeyIR[];
  segments: SegmentIR[];
}
export interface NbtPatchIR {
  merge: string | { molang: string };
  remove?: string[];
}
export type DriverIR = CurveIR | { type: "expression"; value: VectorValueIR | VisibilityIR }
  | { type: "state"; keys: { time: string; value: VisibilityIR | NbtPatchIR }[] };
export interface TrackIR {
  target: { node: string; operation?: string; attachment?: string };
  channel: "value" | "visible" | "nbt";
  driver: DriverIR;
}

export interface EventIR {
  time?: string;
  direction?: "forward" | "backward" | "both";
  source: { type: "server" | "player" } | { type: "node"; node: string; attachment: string };
  origin: { type: "root"; offset?: readonly [number, number, number] } | { type: "node"; node: string; offset?: readonly [number, number, number] };
  action: { type: "commands"; commands: string[] } | { type: "external"; key: string; data: unknown };
}

export interface TimelineEventIR extends EventIR {
  time: string;
}

export interface ClipIR {
  duration: string;
  clock?: { type: "elapsed" } | { type: "molang"; expression: string };
  playback?: { mode?: "once" | "hold" | "loop" | "server_sync"; loop_start?: string; start_delay?: TimeValueIR; loop_delay?: TimeValueIR };
  programs?: { initialize?: string; update?: string };
  tracks: TrackIR[];
  events?: { start?: EventIR[]; timeline?: TimelineEventIR[]; loop?: EventIR[]; stop?: EventIR[] };
}

export interface AnimationIR {
  id: string;
  metadata: EmoteMetadata;
  target_minecraft_version?: string;
  settings?: {
    standalone?: boolean;
    cooldown?: string;
    rotation_deadzone?: number;
    display_interpolation_ticks?: number;
    player?: EmotePlayerBehavior;
  };
  callbacks?: EmoteCallback[];
  nodes: Record<string, NodeIR>;
  animation: ClipIR;
  resources?: Record<string, { uri: string; kind: "item_model" | "texture" | "external" }>;
  source?: Record<string, unknown>;
}

export interface AnimationEntryIR extends Omit<AnimationIR, "nodes" | "animation" | "target_minecraft_version"> {
  nodeIds: string[];
  clip: ClipIR;
}

export interface AnimationSetIR {
  nodes: Record<string, NodeIR>;
  animations: AnimationEntryIR[];
  targetMinecraftVersion: string;
}

export function orderedNodeIds(nodes: Record<string, NodeIR>): string[] {
  const result: string[] = [];
  const pending = new Set(Object.keys(nodes));
  while (pending.size) {
    const ready = [...pending].filter((id) => !nodes[id].parent || !pending.has(nodes[id].parent!)).sort();
    if (!ready.length) throw new Error("Animation IR node hierarchy contains a cycle.");
    for (const id of ready) {
      if (nodes[id].parent && !nodes[nodes[id].parent!]) throw new Error(`Node ${id} references missing parent ${nodes[id].parent}.`);
      pending.delete(id);
      result.push(id);
    }
  }
  return result;
}
