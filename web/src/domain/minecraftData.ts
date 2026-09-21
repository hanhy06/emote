import type { Matrix16 } from "./matrix";

export interface RawNbtField {
  name: string;
  value: string;
}

export interface BlockStateData {
  id: string;
  properties?: Record<string, string>;
  extraFields?: RawNbtField[];
}

export interface ItemStackData {
  id: string;
  count?: number;
  components?: RawNbtField[];
  extraFields?: RawNbtField[];
}

export interface DisplayNbtPatch {
  blockState?: Partial<BlockStateData>;
  itemStack?: Partial<ItemStackData>;
  rawFields: RawNbtField[];
}

export interface BakedRuntimeTransformKeyframe {
  tick: number;
  matrix: Matrix16;
  interpolation: { type: "step" } | { type: "linear"; durationTicks?: number };
}

export interface BakedRuntimeVisibilityKeyframe {
  tick: number;
  visible: boolean;
}

export interface BakedRuntimeNbtKeyframe {
  tick: number;
  value: DisplayNbtPatch;
}

export interface BakedRuntimeNodeTracks {
  transforms: BakedRuntimeTransformKeyframe[];
  visibility: BakedRuntimeVisibilityKeyframe[];
  nbt: BakedRuntimeNbtKeyframe[];
}

export type DisplayNbtValue = DisplayNbtPatch | { molang: string };

export type RuntimeScalar = number | string;
export type RuntimeVector = readonly [RuntimeScalar, RuntimeScalar, RuntimeScalar];
export type RuntimeVec3 = readonly [number, number, number];
export type RuntimeNodeSpace = "scene" | "initiator" | "partner";

export interface RuntimeLocalTransform {
  position: RuntimeVec3;
  rotation: RuntimeVec3;
  scale: RuntimeVec3;
}

interface RuntimeNodeBase {
  parent?: string;
  space?: RuntimeNodeSpace;
  transform: RuntimeLocalTransform;
}

interface RuntimeDisplayNodeBase extends RuntimeNodeBase {
  visible?: boolean;
  entityNbt?: string;
}

export type RuntimeNode =
  | (RuntimeDisplayNodeBase & { type: "item_display"; itemStack: ItemStackData; itemDisplay: string })
  | (RuntimeDisplayNodeBase & { type: "block_display"; blockState: BlockStateData })
  | (RuntimeDisplayNodeBase & { type: "text_display"; text: unknown })
  | (RuntimeNodeBase & { type: "anchor" });

export type RuntimeInterpolation = "step" | "linear";
export type RuntimeEasing =
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

export interface RuntimeVectorKeyframe {
  tick: number;
  value?: RuntimeVector;
  pre?: RuntimeVector;
  post?: RuntimeVector;
  interpolation?: RuntimeInterpolation;
  easing?: RuntimeEasing;
}

export interface RuntimeVisibilityKeyframe {
  tick: number;
  value: boolean | string;
}

export interface RuntimeNbtKeyframe {
  tick: number;
  value: DisplayNbtValue;
}

export interface RuntimeNodeTracks {
  position?: RuntimeVectorKeyframe[];
  rotation?: RuntimeVectorKeyframe[];
  scale?: RuntimeVectorKeyframe[];
  visible?: RuntimeVisibilityKeyframe[];
  nbt?: RuntimeNbtKeyframe[];
}

export interface RuntimeMolangPrograms {
  initialize?: string;
  tick?: string;
}
