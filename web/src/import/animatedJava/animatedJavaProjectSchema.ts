import {
  optionalArray,
  optionalBoolean,
  optionalNumber,
  optionalRecord,
  optionalString,
  requireArray,
  requireNumber,
  requireNumberArray,
  requireRecord,
  requireString,
} from "../../format/runtimeValue";

export type AjProjectExpression = string | number;

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

export function requireAnimatedJavaProject(value: unknown): AjProject {
  const root = requireRecord(value, "Animated Java project");
  const meta = requireRecord(root.meta, "meta");
  requireString(meta.format, "meta.format");
  requireString(meta.format_version, "meta.format_version");
  optionalString(root.name, "name");
  optionalRecord(root.blueprint_settings, "blueprint_settings");
  if (root.variants !== null) optionalRecord(root.variants, "variants");
  optionalArray(root.collections, "collections");
  optionalArray(root.animation_controllers, "animation_controllers");
  const resolution = requireRecord(root.resolution, "resolution");
  requireNumber(resolution.width, "resolution.width");
  requireNumber(resolution.height, "resolution.height");
  requireArray(root.elements, "elements").forEach((entry, index) => requireElement(entry, `elements[${index}]`));
  (optionalArray(root.groups, "groups") ?? []).forEach((entry, index) => requireGroup(entry, `groups[${index}]`));
  requireArray(root.outliner, "outliner").forEach((entry, index) => requireOutlinerEntry(entry, `outliner[${index}]`));
  requireArray(root.textures, "textures").forEach((entry, index) => requireTexture(entry, `textures[${index}]`));
  (optionalArray(root.animations, "animations") ?? []).forEach((entry, index) => requireAnimation(entry, `animations[${index}]`));
  const project = value as AjProject;
  return {
    ...project,
    elements: project.elements.map((element) => {
      const visibility = (element as { visibility?: unknown }).visibility;
      if (visibility !== "true" && visibility !== "false") return element;
      return { ...element, visibility: visibility === "true" };
    }),
    groups: project.groups ?? [],
    animations: project.animations ?? [],
  };
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
    for (const [direction, faceValue] of Object.entries(faces)) requireFace(faceValue, `${path}.faces.${direction}`);
    return;
  }
  if (type === "locator" || type === "camera") {
    requireVector(element.position, `${path}.position`);
    requireVector(element.rotation, `${path}.rotation`);
    optionalBoolean(element.visibility, `${path}.visibility`);
    optionalBoolean(element.ignore_inherited_scale, `${path}.ignore_inherited_scale`);
    return;
  }
  if (isDisplayElementType(type)) {
    requireVector(element.position, `${path}.position`);
    requireVector(element.rotation, `${path}.rotation`);
    requireVector(element.scale, `${path}.scale`);
    optionalLegacyBoolean(element.visibility, `${path}.visibility`);
    optionalString(element.block, `${path}.block`);
    optionalString(element.item, `${path}.item`);
    optionalString(element.item_display, `${path}.item_display`);
    optionalRecord(element.config, `${path}.config`);
    optionalRecord(element.configs, `${path}.configs`);
    optionalString(element.onSummonFunction, `${path}.onSummonFunction`);
  }
}

function requireFace(value: unknown, path: string): void {
  const face = requireRecord(value, path);
  if (face.uv !== undefined) {
    const uv = requireNumberArray(face.uv, `${path}.uv`);
    if (uv.length !== 4) throw new Error(`${path}.uv must contain four numbers.`);
  }
  if (face.texture !== undefined && face.texture !== null && typeof face.texture !== "number" && typeof face.texture !== "string") {
    throw new Error(`${path}.texture must be a texture index, id, or null.`);
  }
  optionalNumber(face.rotation, `${path}.rotation`);
  optionalBoolean(face.enabled, `${path}.enabled`);
}

function requireGroup(value: unknown, path: string): void {
  const group = requireRecord(value, path);
  requireString(group.uuid, `${path}.uuid`);
  requireString(group.name, `${path}.name`);
  requireVector(group.origin, `${path}.origin`);
  requireVector(group.rotation, `${path}.rotation`);
  optionalArray(group.children, `${path}.children`);
  optionalRecord(group.configs, `${path}.configs`);
  optionalString(group.onSummonFunction, `${path}.onSummonFunction`);
  optionalBoolean(group.visibility, `${path}.visibility`);
}

function requireOutlinerEntry(value: unknown, path: string): void {
  if (typeof value === "string") return;
  const group = requireRecord(value, path);
  requireString(group.uuid, `${path}.uuid`);
  optionalString(group.name, `${path}.name`);
  if (group.origin !== undefined) requireVector(group.origin, `${path}.origin`);
  if (group.rotation !== undefined) requireVector(group.rotation, `${path}.rotation`);
  requireArray(group.children, `${path}.children`).forEach((entry, index) => requireOutlinerEntry(entry, `${path}.children[${index}]`));
}

function requireTexture(value: unknown, path: string): void {
  const texture = requireRecord(value, path);
  optionalString(texture.id, `${path}.id`);
  optionalString(texture.uuid, `${path}.uuid`);
  optionalString(texture.name, `${path}.name`);
  optionalString(texture.source, `${path}.source`);
  optionalNumber(texture.frame_time, `${path}.frame_time`);
  optionalBoolean(texture.frame_interpolate, `${path}.frame_interpolate`);
  optionalString(texture.frame_order_type, `${path}.frame_order_type`);
  optionalString(texture.frame_order, `${path}.frame_order`);
}

function requireAnimation(value: unknown, path: string): void {
  const animation = requireRecord(value, path);
  optionalString(animation.uuid, `${path}.uuid`);
  requireString(animation.name, `${path}.name`);
  requireNumber(animation.length, `${path}.length`);
  requireString(animation.loop, `${path}.loop`);
  optionalExpression(animation.blend_weight, `${path}.blend_weight`);
  optionalExpression(animation.start_delay, `${path}.start_delay`);
  optionalExpression(animation.loop_delay, `${path}.loop_delay`);
  optionalBoolean(animation.override, `${path}.override`);
  const animators = requireRecord(animation.animators, `${path}.animators`);
  for (const [id, animatorValue] of Object.entries(animators)) {
    const animatorPath = `${path}.animators.${id}`;
    const animator = requireRecord(animatorValue, animatorPath);
    optionalString(animator.name, `${animatorPath}.name`);
    optionalString(animator.type, `${animatorPath}.type`);
    for (const [index, keyframeValue] of (optionalArray(animator.keyframes, `${animatorPath}.keyframes`) ?? []).entries()) {
      requireKeyframe(keyframeValue, `${animatorPath}.keyframes[${index}]`);
    }
  }
}

function requireKeyframe(value: unknown, path: string): void {
  const keyframe = requireRecord(value, path);
  optionalString(keyframe.uuid, `${path}.uuid`);
  const channel = requireString(keyframe.channel, `${path}.channel`);
  requireNumber(keyframe.time, `${path}.time`);
  optionalString(keyframe.interpolation, `${path}.interpolation`);
  optionalString(keyframe.easing, `${path}.easing`);
  if (keyframe.easingArgs !== undefined) requireNumberArray(keyframe.easingArgs, `${path}.easingArgs`);
  for (const property of ["bezier_left_time", "bezier_left_value", "bezier_right_time", "bezier_right_value"] as const) {
    if (keyframe[property] === undefined) continue;
    const values = requireNumberArray(keyframe[property], `${path}.${property}`);
    if (values.length !== 3) throw new Error(`${path}.${property} must contain three numbers.`);
  }
  requireArray(keyframe.data_points, `${path}.data_points`).forEach((pointValue, pointIndex) => {
    const pointPath = `${path}.data_points[${pointIndex}]`;
    const point = requireRecord(pointValue, pointPath);
    if (["position", "rotation", "scale"].includes(channel)) {
      requireExpression(point.x, `${pointPath}.x`);
      requireExpression(point.y, `${pointPath}.y`);
      requireExpression(point.z, `${pointPath}.z`);
      return;
    }
    optionalExpression(point.x, `${pointPath}.x`);
    optionalExpression(point.y, `${pointPath}.y`);
    optionalExpression(point.z, `${pointPath}.z`);
    optionalString(point.commands, `${pointPath}.commands`);
    optionalString(point.function, `${pointPath}.function`);
    optionalString(point.variant, `${pointPath}.variant`);
    optionalString(point.execute_condition, `${pointPath}.execute_condition`);
    if (point.repeat !== undefined && typeof point.repeat !== "boolean" && typeof point.repeat !== "number") {
      throw new Error(`${pointPath}.repeat must be a boolean or number.`);
    }
    optionalNumber(point.repeat_frequency, `${pointPath}.repeat_frequency`);
  });
}

function isDisplayElementType(type: string): type is AjProjectDisplayElement["type"] {
  return [
    "animated_java:vanilla_block_display",
    "animated_java:vanilla_item_display",
    "animated_java:vanilla_text_display",
    "animated_java:text_display",
  ].includes(type);
}

function requireVector(value: unknown, path: string): void {
  const vector = requireNumberArray(value, path);
  if (vector.length !== 3) throw new Error(`${path} must contain three numbers.`);
}

function optionalExpression(value: unknown, path: string): void {
  if (value !== undefined) requireExpression(value, path);
}

function optionalLegacyBoolean(value: unknown, path: string): void {
  if (value === "true" || value === "false") return;
  optionalBoolean(value, path);
}

function requireExpression(value: unknown, path: string): void {
  if (typeof value !== "number" && typeof value !== "string") throw new Error(`${path} must be a number or expression.`);
}
