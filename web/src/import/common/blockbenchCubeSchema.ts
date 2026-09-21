export interface BlockbenchCubeProject {
  name?: string;
  resolution: { width: number; height: number };
  elements: BbElement[];
  groups: BbGroup[];
  outliner: BbOutlinerEntry[];
  textures: BbTexture[];
  animations: BbAnimation[];
}


export interface BbGroup {
  uuid: string;
  name: string;
  origin: number[];
  rotation: number[];
}

export interface BbOutlinerGroup extends Partial<BbGroup> {
  uuid: string;
  children: BbOutlinerEntry[];
}

export type BbOutlinerEntry = string | BbOutlinerGroup;

export interface BbCube {
  uuid: string;
  name?: string;
  type?: string;
  from: number[];
  to: number[];
  origin?: number[];
  rotation?: number[];
  inflate?: number;
  faces: Record<string, BbFace>;
}

export interface BbLocator {
  uuid: string;
  name: string;
  type: "locator";
  position: number[];
  rotation: number[];
  ignore_inherited_scale?: boolean;
}

export type BbElement = BbCube | BbLocator;

export interface BbFace {
  uv?: number[];
  texture?: number | string | null;
  rotation?: number;
  enabled?: boolean;
}

export interface BbTexture {
  id?: string;
  uuid?: string;
  name?: string;
  source?: string;
  frame_time?: number;
  frame_interpolate?: boolean;
  frame_order_type?: "loop" | "backwards" | "back_and_forth" | "custom";
  frame_order?: string;
}

export interface BbAnimation {
  uuid?: string;
  name: string;
  length: number;
  loop?: string;
  loop_delay?: string | number;
  start_delay?: string | number;
  blend_weight?: string | number;
  animators: Record<string, BbAnimator>;
}

export interface BbAnimator {
  name?: string;
  type?: string;
  keyframes?: BbKeyframe[];
}

export interface BbKeyframe {
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
  data_points: BbDataPoint[];
}

export interface BbDataPoint {
  x?: number | string;
  y?: number | string;
  z?: number | string;
  effect?: string;
  locator?: string;
  script?: string;
  file?: string;
  bind_to_actor?: boolean;
}
