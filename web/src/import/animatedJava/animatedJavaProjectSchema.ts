import {
  optionalArray,
  optionalBoolean,
  optionalNumber,
  optionalString,
  requireArray,
  requireNumber,
  requireNumberArray,
  requireRecord,
  requireString,
} from "../../format/runtimeValue";

export interface AjProject {
  meta: { format: string; format_version: string };
  name?: string;
  resolution: { width: number; height: number };
  elements: AjProjectElement[];
  groups: unknown[];
  outliner: unknown[];
  textures: unknown[];
  animations: AjProjectAnimation[];
}

export type AjProjectElement = AjProjectDisplayElement | AjProjectCube;

export interface AjProjectDisplayElement {
  uuid: string;
  name: string;
  type: string;
  position: number[];
  rotation: number[];
  scale: number[];
  visibility: boolean;
  block?: string;
  item?: string;
  text?: unknown;
  configs?: { default?: Record<string, unknown>; variants?: Record<string, unknown> };
}

export interface AjProjectCube {
  uuid: string;
  name: string;
  type: "cube";
  from: number[];
  to: number[];
  origin?: number[];
  rotation?: number[];
  inflate?: number;
  faces: Record<string, unknown>;
}

export interface AjProjectAnimation {
  name: string;
  length: number;
  loop: string;
  blend_weight?: string;
  start_delay?: string;
  loop_delay?: string;
  animators: Record<string, AjProjectAnimator>;
}

export interface AjProjectAnimator {
  name?: string;
  type?: string;
  keyframes: AjProjectKeyframe[];
}

export interface AjProjectKeyframe {
  channel: string;
  time: number;
  interpolation: string;
  easing?: string;
  data_points: { x: string | number; y: string | number; z: string | number }[];
}

export function isAnimatedJavaProject(value: unknown): boolean {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const meta = (value as Record<string, unknown>).meta;
  return typeof meta === "object"
    && meta !== null
    && !Array.isArray(meta)
    && (meta as Record<string, unknown>).format === "animated-java:format/blueprint";
}

export function requireAnimatedJavaProject(value: unknown): AjProject {
  const root = requireRecord(value, "Animated Java project");
  const meta = requireRecord(root.meta, "meta");
  requireString(meta.format, "meta.format");
  requireString(meta.format_version, "meta.format_version");
  optionalString(root.name, "name");
  const resolution = requireRecord(root.resolution, "resolution");
  requireNumber(resolution.width, "resolution.width");
  requireNumber(resolution.height, "resolution.height");
  requireArray(root.elements, "elements").forEach((entry, index) => requireElement(entry, `elements[${index}]`));
  requireArray(root.groups, "groups");
  requireArray(root.outliner, "outliner");
  requireArray(root.textures, "textures");
  requireArray(root.animations, "animations").forEach((entry, index) => requireAnimation(entry, `animations[${index}]`));
  return value as AjProject;
}

function requireElement(value: unknown, path: string): void {
  const element = requireRecord(value, path);
  requireString(element.uuid, `${path}.uuid`);
  requireString(element.name, `${path}.name`);
  const type = requireString(element.type, `${path}.type`);
  if (type === "cube") {
    requireVector(element.from, `${path}.from`);
    requireVector(element.to, `${path}.to`);
    if (element.origin !== undefined) requireVector(element.origin, `${path}.origin`);
    if (element.rotation !== undefined) requireVector(element.rotation, `${path}.rotation`);
    optionalNumber(element.inflate, `${path}.inflate`);
    const faces = requireRecord(element.faces, `${path}.faces`);
    for (const [direction, faceValue] of Object.entries(faces)) {
      const face = requireRecord(faceValue, `${path}.faces.${direction}`);
      const uv = requireNumberArray(face.uv, `${path}.faces.${direction}.uv`);
      if (uv.length !== 4) throw new Error(`${path}.faces.${direction}.uv must contain four numbers.`);
    }
    return;
  }
  requireVector(element.position, `${path}.position`);
  requireVector(element.rotation, `${path}.rotation`);
  requireVector(element.scale, `${path}.scale`);
  optionalBoolean(element.visibility, `${path}.visibility`);
  optionalString(element.block, `${path}.block`);
  optionalString(element.item, `${path}.item`);
}

function requireAnimation(value: unknown, path: string): void {
  const animation = requireRecord(value, path);
  requireString(animation.name, `${path}.name`);
  requireNumber(animation.length, `${path}.length`);
  requireString(animation.loop, `${path}.loop`);
  optionalString(animation.blend_weight, `${path}.blend_weight`);
  optionalString(animation.start_delay, `${path}.start_delay`);
  optionalString(animation.loop_delay, `${path}.loop_delay`);
  const animators = requireRecord(animation.animators, `${path}.animators`);
  for (const [id, animatorValue] of Object.entries(animators)) {
    const animatorPath = `${path}.animators.${id}`;
    const animator = requireRecord(animatorValue, animatorPath);
    optionalString(animator.name, `${animatorPath}.name`);
    optionalString(animator.type, `${animatorPath}.type`);
    for (const [index, keyframeValue] of (optionalArray(animator.keyframes, `${animatorPath}.keyframes`) ?? []).entries()) {
      const keyframePath = `${animatorPath}.keyframes[${index}]`;
      const keyframe = requireRecord(keyframeValue, keyframePath);
      requireString(keyframe.channel, `${keyframePath}.channel`);
      requireNumber(keyframe.time, `${keyframePath}.time`);
      requireString(keyframe.interpolation, `${keyframePath}.interpolation`);
      optionalString(keyframe.easing, `${keyframePath}.easing`);
      requireArray(keyframe.data_points, `${keyframePath}.data_points`).forEach((pointValue, pointIndex) => {
        const pointPath = `${keyframePath}.data_points[${pointIndex}]`;
        const point = requireRecord(pointValue, pointPath);
        requireExpression(point.x, `${pointPath}.x`);
        requireExpression(point.y, `${pointPath}.y`);
        requireExpression(point.z, `${pointPath}.z`);
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
