import {
  optionalArray,
  optionalNumber,
  optionalRecord,
  optionalString,
  requireArray,
  requireNumber,
  requireNumberArray,
  requireRecord,
  requireString,
} from "../../format/runtimeValue";

export interface BbmodelProject {
  meta: { format_version: string; model_format: string };
  name?: string;
  geckolib_modid?: string;
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

export function requireBlockbenchCubeProject(value: unknown): BbmodelProject {
  const root = requireRecord(value, "GeckoLib bbmodel");
  const meta = requireRecord(root.meta, "meta");
  requireString(meta.format_version, "meta.format_version");
  requireString(meta.model_format, "meta.model_format");
  optionalString(root.name, "name");
  optionalString(root.geckolib_modid, "geckolib_modid");

  const resolution = requireRecord(root.resolution, "resolution");
  const width = requireNumber(resolution.width, "resolution.width");
  const height = requireNumber(resolution.height, "resolution.height");
  if (width <= 0 || height <= 0) throw new Error("GeckoLib texture resolution must be positive.");

  requireArray(root.elements, "elements").forEach((entry, index) => requireElement(entry, `elements[${index}]`));
  (optionalArray(root.groups, "groups") ?? []).forEach((entry, index) => requireGroup(entry, `groups[${index}]`));
  requireArray(root.outliner, "outliner").forEach((entry, index) => requireOutlinerEntry(entry, `outliner[${index}]`));
  requireArray(root.textures, "textures").forEach((entry, index) => {
    const texture = requireRecord(entry, `textures[${index}]`);
    optionalString(texture.id, `textures[${index}].id`);
    optionalString(texture.uuid, `textures[${index}].uuid`);
    optionalString(texture.name, `textures[${index}].name`);
    optionalString(texture.source, `textures[${index}].source`);
    optionalNumber(texture.frame_time, `textures[${index}].frame_time`);
    if (texture.frame_interpolate !== undefined && typeof texture.frame_interpolate !== "boolean") throw new Error(`textures[${index}].frame_interpolate must be a boolean.`);
    if (texture.frame_order_type !== undefined && !["loop", "backwards", "back_and_forth", "custom"].includes(requireString(texture.frame_order_type, `textures[${index}].frame_order_type`))) {
      throw new Error(`textures[${index}].frame_order_type is invalid.`);
    }
    optionalString(texture.frame_order, `textures[${index}].frame_order`);
  });
  (optionalArray(root.animations, "animations") ?? []).forEach((entry, index) => requireAnimation(entry, `animations[${index}]`));
  return value as BbmodelProject;
}

function requireGroup(value: unknown, path: string): void {
  const group = requireRecord(value, path);
  requireString(group.uuid, `${path}.uuid`);
  requireString(group.name, `${path}.name`);
  requireVector(group.origin, `${path}.origin`);
  requireVector(group.rotation, `${path}.rotation`);
}

function requireOutlinerEntry(value: unknown, path: string): void {
  if (typeof value === "string") return;
  const group = requireRecord(value, path);
  requireString(group.uuid, `${path}.uuid`);
  if (group.name !== undefined) requireString(group.name, `${path}.name`);
  if (group.origin !== undefined) requireVector(group.origin, `${path}.origin`);
  if (group.rotation !== undefined) requireVector(group.rotation, `${path}.rotation`);
  requireArray(group.children, `${path}.children`).forEach((entry, index) => requireOutlinerEntry(entry, `${path}.children[${index}]`));
}

function requireElement(value: unknown, path: string): void {
  const element = requireRecord(value, path);
  if (element.type === "locator") {
    requireString(element.uuid, `${path}.uuid`);
    requireString(element.name, `${path}.name`);
    requireVector(element.position, `${path}.position`);
    requireVector(element.rotation, `${path}.rotation`);
    if (element.ignore_inherited_scale !== undefined && typeof element.ignore_inherited_scale !== "boolean") {
      throw new Error(`${path}.ignore_inherited_scale must be a boolean.`);
    }
    return;
  }
  requireCube(element, path);
}

function requireCube(value: unknown, path: string): void {
  const cube = requireRecord(value, path);
  requireString(cube.uuid, `${path}.uuid`);
  optionalString(cube.name, `${path}.name`);
  optionalString(cube.type, `${path}.type`);
  requireVector(cube.from, `${path}.from`);
  requireVector(cube.to, `${path}.to`);
  if (cube.origin !== undefined) requireVector(cube.origin, `${path}.origin`);
  if (cube.rotation !== undefined) requireVector(cube.rotation, `${path}.rotation`);
  optionalNumber(cube.inflate, `${path}.inflate`);
  const faces = requireRecord(cube.faces, `${path}.faces`);
  for (const [direction, faceValue] of Object.entries(faces)) {
    const face = requireRecord(faceValue, `${path}.faces.${direction}`);
    if (face.uv !== undefined) {
      const uv = requireNumberArray(face.uv, `${path}.faces.${direction}.uv`);
      if (uv.length !== 4) throw new Error(`${path}.faces.${direction}.uv must contain four numbers.`);
    }
    if (face.texture !== undefined && face.texture !== null && typeof face.texture !== "number" && typeof face.texture !== "string") {
      throw new Error(`${path}.faces.${direction}.texture must be a texture index, id, or null.`);
    }
    optionalNumber(face.rotation, `${path}.faces.${direction}.rotation`);
  }
}

function requireAnimation(value: unknown, path: string): void {
  const animation = requireRecord(value, path);
  optionalString(animation.uuid, `${path}.uuid`);
  requireString(animation.name, `${path}.name`);
  requireNumber(animation.length, `${path}.length`);
  optionalString(animation.loop, `${path}.loop`);
  if (animation.loop_delay !== undefined && typeof animation.loop_delay !== "string" && typeof animation.loop_delay !== "number") {
    throw new Error(`${path}.loop_delay must be a string or number.`);
  }
  const animators = requireRecord(animation.animators, `${path}.animators`);
  for (const [id, animatorValue] of Object.entries(animators)) {
    const animatorPath = `${path}.animators.${id}`;
    const animator = requireRecord(animatorValue, animatorPath);
    optionalString(animator.name, `${animatorPath}.name`);
    optionalString(animator.type, `${animatorPath}.type`);
    for (const [index, keyframeValue] of (optionalArray(animator.keyframes, `${animatorPath}.keyframes`) ?? []).entries()) {
      const keyframePath = `${animatorPath}.keyframes[${index}]`;
      const keyframe = requireRecord(keyframeValue, keyframePath);
      optionalString(keyframe.uuid, `${keyframePath}.uuid`);
      const channel = requireString(keyframe.channel, `${keyframePath}.channel`);
      requireNumber(keyframe.time, `${keyframePath}.time`);
      optionalString(keyframe.interpolation, `${keyframePath}.interpolation`);
      optionalString(keyframe.easing, `${keyframePath}.easing`);
      for (const property of ["easingArgs", "bezier_left_time", "bezier_left_value", "bezier_right_time", "bezier_right_value"] as const) {
        if (keyframe[property] === undefined) continue;
        const values = requireNumberArray(keyframe[property], `${keyframePath}.${property}`);
        if (property !== "easingArgs" && values.length !== 3) throw new Error(`${keyframePath}.${property} must contain three numbers.`);
      }
      requireArray(keyframe.data_points, `${keyframePath}.data_points`).forEach((pointValue, pointIndex) => {
        const pointPath = `${keyframePath}.data_points[${pointIndex}]`;
        const point = requireRecord(pointValue, pointPath);
        if (["position", "rotation", "scale"].includes(channel)) {
          requireExpression(point.x, `${pointPath}.x`);
          requireExpression(point.y, `${pointPath}.y`);
          requireExpression(point.z, `${pointPath}.z`);
        } else {
          optionalString(point.effect, `${pointPath}.effect`);
          optionalString(point.locator, `${pointPath}.locator`);
          optionalString(point.script, `${pointPath}.script`);
          optionalString(point.file, `${pointPath}.file`);
          if (point.bind_to_actor !== undefined && typeof point.bind_to_actor !== "boolean") throw new Error(`${pointPath}.bind_to_actor must be a boolean.`);
        }
      });
    }
  }
}

function requireVector(value: unknown, path: string): void {
  const vector = requireNumberArray(value, path);
  if (vector.length !== 3) throw new Error(`${path} must contain three numbers.`);
}

function requireExpression(value: unknown, path: string): void {
  if (typeof value !== "number" && typeof value !== "string") throw new Error(`${path} must be a number or expression.`);
}
