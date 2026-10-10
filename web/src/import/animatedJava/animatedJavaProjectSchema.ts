import type { ImportDiagnostic } from "../../domain/conversionSeed";

export type AjProjectExpression = string | number;

export interface ProjectTransformGraph {
  groups: ReadonlyMap<string, AjProjectGroup>;
  groupParents: ReadonlyMap<string, string | undefined>;
  elementParents: ReadonlyMap<string, string | undefined>;
}

export interface AjProject {
  meta: { format: string; format_version: string };
  name?: string;
  resolution: { width: number; height: number };
  blueprint_settings?: Record<string, unknown>;
  elements: AjProjectElement[];
  groups: AjProjectGroup[];
  outliner: AjProjectOutlinerEntry[];
  textures: AjProjectTexture[];
  variants?: Record<string, unknown>;
  collections?: unknown[];
  animations: AjProjectAnimation[];
  animationSourceIndices?: number[];
  animationDiagnostics?: ImportDiagnostic[];
  animation_controllers?: unknown[];
}

export type AjProjectElement = AjProjectCube | AjProjectLocator | AjProjectDisplayElement | AjProjectUnknownElement;

export interface AjProjectElementBase {
  uuid: string;
  name: string;
  type: string;
}

export interface AjProjectDisplayElement extends AjProjectElementBase {
  type: "animated_java:vanilla_block_display" | "animated_java:vanilla_item_display" | "animated_java:vanilla_text_display" | "animated_java:text_display";
  position: number[];
  rotation: number[];
  scale: number[];
  visibility: boolean;
  block?: string;
  item?: string;
  itemDisplay?: string;
  item_display?: string;
  text?: unknown;
  config?: Record<string, unknown>;
  configs?: { default?: Record<string, unknown>; variants?: Record<string, unknown> };
  onSummonFunction?: string;
}

export interface AjProjectLocator extends AjProjectElementBase {
  type: "locator" | "camera";
  position: number[];
  rotation: number[];
  visibility?: boolean;
  ignore_inherited_scale?: boolean;
}

export interface AjProjectCube extends AjProjectElementBase {
  type: "cube";
  from: number[];
  to: number[];
  origin?: number[];
  rotation?: number[];
  inflate?: number;
  faces: Record<string, AjProjectFace>;
}

export interface AjProjectUnknownElement extends AjProjectElementBase {
  [key: string]: unknown;
}

export interface AjProjectFace {
  uv?: number[];
  texture?: number | string | null;
  rotation?: number;
  enabled?: boolean;
}

export interface AjProjectGroup {
  uuid: string;
  name: string;
  origin: number[];
  rotation: number[];
  children?: AjProjectOutlinerEntry[];
  configs?: { default?: Record<string, unknown>; variants?: Record<string, unknown> };
  onSummonFunction?: string;
  visibility?: boolean;
}

export type AjProjectOutlinerEntry = string | (Partial<AjProjectGroup> & { uuid: string; children: AjProjectOutlinerEntry[] });

export interface AjProjectTexture {
  id?: string;
  uuid?: string;
  name?: string;
  source?: string;
  frame_time?: number;
  frame_interpolate?: boolean;
  frame_order_type?: string;
  frame_order?: string;
}

export interface AjProjectAnimation {
  uuid?: string;
  name: string;
  length: number;
  loop: string;
  blend_weight?: AjProjectExpression;
  start_delay?: AjProjectExpression;
  loop_delay?: AjProjectExpression;
  override?: boolean;
  animators: Record<string, AjProjectAnimator>;
}

export interface AjProjectAnimator {
  name?: string;
  type?: string;
  keyframes?: AjProjectKeyframe[];
}

export interface AjProjectKeyframe {
  uuid?: string;
  channel: string;
  time: number;
  interpolation?: string;
  easing?: string;
  easingArgs?: number[];
  bezier_left_time?: number[];
  bezier_left_value?: number[];
  bezier_right_time?: number[];
  bezier_right_value?: number[];
  data_points: AjProjectDataPoint[];
}

export interface AjProjectDataPoint {
  x?: AjProjectExpression;
  y?: AjProjectExpression;
  z?: AjProjectExpression;
  commands?: string;
  function?: string;
  variant?: string;
  execute_condition?: string;
  repeat?: boolean | number;
  repeat_frequency?: number;
}

export function isAnimatedJavaProject(value: unknown): boolean {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const meta = (value as Record<string, unknown>).meta;
  return typeof meta === "object"
    && meta !== null
    && !Array.isArray(meta)
    && ["animated-java:format/blueprint", "animated_java_blueprint"].includes(String((meta as Record<string, unknown>).format));
}
