import type { Matrix16 } from "./matrix";
import type { EmoteCallback, EmoteMetadata, EmotePlayerBehavior } from "./emoteDefinition";

export type ScalarIR = number | { molang: string };
export type TimeValueIR = string | { molang: string };
export type VectorValueIR = readonly ScalarIR[];
export type VisibilityIR = boolean | { molang: string };
export type RotationOrderIR = "XYZ" | "XZY" | "YXZ" | "YZX" | "ZXY" | "ZYX";

export type Vector3IR = [number, number, number];
export type TransformOperationIR = { id: string } & (
  | { op: "translate" | "scale"; value: Readonly<Vector3IR>; order?: never }
  | { op: "rotate_euler"; value: Readonly<Vector3IR>; order: RotationOrderIR }
  | { op: "rotate_quaternion"; value: readonly [number, number, number, number]; order?: never }
  | { op: "matrix"; value: Matrix16; order?: never }
);

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

export type EasingIR = { direction?: "in" | "out" | "in_out" } & (
  | { kernel: "linear" | "sine" | "expo" | "circ" | "blockbench_expo"; exponent?: never; overshoot?: never; frequency?: never; bounciness?: never; count?: never }
  | { kernel: "power"; exponent: ScalarIR; overshoot?: never; frequency?: never; bounciness?: never; count?: never }
  | { kernel: "back"; overshoot?: ScalarIR; exponent?: never; frequency?: never; bounciness?: never; count?: never }
  | { kernel: "blockbench_elastic"; frequency?: ScalarIR; exponent?: never; overshoot?: never; bounciness?: never; count?: never }
  | { kernel: "blockbench_bounce"; bounciness?: ScalarIR; exponent?: never; overshoot?: never; frequency?: never; count?: never }
  | { kernel: "steps" | "blockbench_steps"; count: ScalarIR; exponent?: never; overshoot?: never; frequency?: never; bounciness?: never }
);

export type BezierHandleIR = { out: { time: ScalarIR; value: ScalarIR; relative?: boolean; clamp?: "rescale_value" }; in: { time: ScalarIR; value: ScalarIR; relative?: boolean; clamp?: "rescale_value" } };

export type SegmentIR = {
  easing?: EasingIR;
  tension?: number;
  previous?: VectorValueIR;
  following?: VectorValueIR;
  out_tangent?: VectorValueIR;
  in_tangent?: VectorValueIR;
  control_order?: "time_value" | "in_out";
  handles?: BezierHandleIR[];
} & (
  | { interpolation: "step"; easing?: never; tension?: never; previous?: never; following?: never; out_tangent?: never; in_tangent?: never; handles?: never; control_order?: never }
  | { interpolation: "linear" | "slerp"; tension?: never; previous?: never; following?: never; out_tangent?: never; in_tangent?: never; handles?: never; control_order?: never }
  | { interpolation: "catmull_rom"; out_tangent?: never; in_tangent?: never; handles?: never; control_order?: never }
  | { interpolation: "hermite"; out_tangent: VectorValueIR; in_tangent: VectorValueIR; tension?: never; previous?: never; following?: never; handles?: never; control_order?: never }
  | { interpolation: "bezier"; handles: BezierHandleIR[]; tension?: never; previous?: never; following?: never; out_tangent?: never; in_tangent?: never }
);

export type CurveKeyIR = { time: string } & ({ value: VectorValueIR; pre?: never; post?: never } | { pre: VectorValueIR; post: VectorValueIR; value?: never });
export interface CurveIR {
  type: "curve";
  before?: "base" | "first_pre";
  at_key?: "pre" | "post" | "segment";
  evaluation?: "vector" | "component";
  keys: CurveKeyIR[];
  segments: SegmentIR[];
}
export interface NbtPatchIR {
  merge: string | { molang: string };
  remove?: string[];
}
export type StateDriverIR<T> = { type: "state"; before?: "base" | "first_pre"; keys: { time: string; value: T }[] };
export type ValueDriverIR = CurveIR | { type: "expression"; value: VectorValueIR };
export type VisibilityDriverIR = { type: "expression"; value: VisibilityIR } | StateDriverIR<VisibilityIR>;
export type NbtDriverIR = StateDriverIR<NbtPatchIR>;
export type DriverIR = ValueDriverIR | VisibilityDriverIR | NbtDriverIR;
export type SharedValueIR = ValueDriverIR & { base: readonly number[]; time_offset?: string };
export type TrackIR =
  | { target: { node: string; operation: string; attachment?: never }; channel: "value"; driver: ValueDriverIR; blend?: { base: Readonly<Vector3IR>; weight: ScalarIR } }
  | { target: { node: string; attachment?: string; operation?: never }; channel: "visible"; driver: VisibilityDriverIR; blend?: never }
  | { target: { node: string; attachment: string; operation?: never }; channel: "nbt"; driver: NbtDriverIR; blend?: never };

export interface EventIR {
  time?: string;
  direction?: "forward" | "backward" | "both";
  source: { type: "server" | "player" } | { type: "node"; node: string; attachment: string };
  origin: { type: "root"; offset?: readonly [number, number, number]; rotation?: "origin" } | { type: "node"; node: string; offset?: readonly [number, number, number]; rotation?: "origin" };
  action: { type: "commands"; commands: string[] } | { type: "external"; key: string; data: unknown };
}

export interface TimelineEventIR extends EventIR {
  time: string;
}

export interface ClipIR {
  duration: string;
  clock?: { type: "elapsed" } | { type: "molang"; expression: string };
  playback?: { mode?: "once" | "hold" | "loop" | "server_sync"; loop_start?: string; start_delay?: TimeValueIR; loop_delay?: TimeValueIR };
  molang?: { initialize?: string; tick?: string };
  values?: Record<string, SharedValueIR>;
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
    player?: EmotePlayerBehavior;
  };
  callbacks?: EmoteCallback[];
  skin?: SkinIR;
  nodes: Record<string, NodeIR>;
  animation: ClipIR;
  resources?: Record<string, { uri: string; kind: "item_model" | "texture" | "external" }>;
  source?: Record<string, unknown>;
}

export interface SkinIR {
  default?: string;
  override?: { texture: string; mask: string };
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

export function attachmentTargetId(nodeId: string, attachmentId: string): string {
  return JSON.stringify([nodeId, attachmentId]);
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
