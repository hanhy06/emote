import { requireRecord, requireString } from "../../format/runtimeValue";

export type BedrockExpression = number | string;
export type BedrockVector = BedrockExpression | BedrockExpression[];
export type BedrockLoop = boolean | "hold_on_last_frame";

export interface BedrockAnimationDocument {
  format_version: "1.8.0";
  animations: Record<string, BedrockAnimation>;
}

export interface BedrockAnimation {
  loop?: BedrockLoop;
  animation_length?: number;
  start_delay?: BedrockExpression;
  loop_delay?: BedrockExpression;
  anim_time_update?: BedrockExpression;
  blend_weight?: BedrockExpression;
  override_previous_animation?: boolean;
  bones?: Record<string, BedrockBoneAnimation>;
  particle_effects?: unknown;
  sound_effects?: unknown;
  timeline?: unknown;
}

export interface BedrockBoneAnimation {
  position?: BedrockChannel;
  rotation?: BedrockChannel;
  scale?: BedrockChannel;
  relative_to?: { rotation?: "entity" };
}

export type BedrockChannel = BedrockVector | Record<string, BedrockKeyframe>;
export type BedrockKeyframe = BedrockVector | BedrockKeyframeValue;

export interface BedrockKeyframeValue {
  pre?: BedrockVector;
  post?: BedrockVector;
  lerp_mode?: "linear" | "catmullrom";
}

export function isBedrockAnimationDocument(value: unknown): boolean {
  if (!isRecord(value) || value.format_version !== "1.8.0" || !isRecord(value.animations)) return false;
  return Object.keys(value.animations).length > 0;
}

export function requireBedrockAnimationDocument(value: unknown): BedrockAnimationDocument {
  const root = requireRecord(value, "Bedrock animation document");
  const formatVersion = requireString(root.format_version, "format_version");
  if (formatVersion !== "1.8.0") throw new Error(`format_version must be \"1.8.0\", received \"${formatVersion}\".`);

  const animations = requireRecord(root.animations, "animations");
  if (Object.keys(animations).length === 0) throw new Error("animations must contain at least one animation.");
  for (const [name, animationValue] of Object.entries(animations)) requireAnimation(animationValue, `animations.${name}`);
  return value as BedrockAnimationDocument;
}

function requireAnimation(value: unknown, path: string): void {
  const animation = requireRecord(value, path);
  if (animation.loop !== undefined && typeof animation.loop !== "boolean" && animation.loop !== "hold_on_last_frame") {
    throw new Error(`${path}.loop must be a boolean or \"hold_on_last_frame\".`);
  }
  if (animation.animation_length !== undefined) {
    if (typeof animation.animation_length !== "number" || !Number.isFinite(animation.animation_length) || animation.animation_length < 0) {
      throw new Error(`${path}.animation_length must be a non-negative finite number.`);
    }
  }
  for (const property of ["start_delay", "loop_delay", "anim_time_update", "blend_weight"] as const) {
    if (animation[property] !== undefined) requireExpression(animation[property], `${path}.${property}`);
  }
  if (animation.override_previous_animation !== undefined && typeof animation.override_previous_animation !== "boolean") {
    throw new Error(`${path}.override_previous_animation must be a boolean.`);
  }
  if (animation.bones === undefined) return;

  const bones = requireRecord(animation.bones, `${path}.bones`);
  for (const [boneName, boneValue] of Object.entries(bones)) requireBoneAnimation(boneValue, `${path}.bones.${boneName}`);
}

function requireBoneAnimation(value: unknown, path: string): void {
  const bone = requireRecord(value, path);
  for (const channel of ["position", "rotation", "scale"] as const) {
    if (bone[channel] !== undefined) requireChannel(bone[channel], `${path}.${channel}`);
  }
  if (bone.relative_to === undefined) return;
  const relativeTo = requireRecord(bone.relative_to, `${path}.relative_to`);
  if (relativeTo.rotation !== undefined && relativeTo.rotation !== "entity") {
    throw new Error(`${path}.relative_to.rotation must be \"entity\".`);
  }
}

function requireChannel(value: unknown, path: string): void {
  if (!isRecord(value)) {
    requireVector(value, path);
    return;
  }
  if (Object.keys(value).length === 0) throw new Error(`${path} must contain at least one keyframe.`);
  for (const [timestamp, keyframe] of Object.entries(value)) {
    const time = Number(timestamp);
    if (!Number.isFinite(time) || time < 0) throw new Error(`${path}.${timestamp} is not a non-negative timestamp.`);
    requireKeyframe(keyframe, `${path}.${timestamp}`);
  }
}

function requireKeyframe(value: unknown, path: string): void {
  if (!isRecord(value)) {
    requireVector(value, path);
    return;
  }
  if (value.pre === undefined && value.post === undefined) throw new Error(`${path} must contain pre or post.`);
  if (value.pre !== undefined) requireVector(value.pre, `${path}.pre`);
  if (value.post !== undefined) requireVector(value.post, `${path}.post`);
  if (value.lerp_mode !== undefined && value.lerp_mode !== "linear" && value.lerp_mode !== "catmullrom") {
    throw new Error(`${path}.lerp_mode must be \"linear\" or \"catmullrom\".`);
  }
}

function requireVector(value: unknown, path: string): void {
  if (!Array.isArray(value)) {
    requireExpression(value, path);
    return;
  }
  if (value.length !== 1 && value.length !== 3) throw new Error(`${path} must contain one or three values.`);
  value.forEach((entry, index) => requireExpression(entry, `${path}[${index}]`));
}

function requireExpression(value: unknown, path: string): asserts value is BedrockExpression {
  if (typeof value === "number" && Number.isFinite(value)) return;
  if (typeof value === "string" && value.trim().length > 0) return;
  throw new Error(`${path} must be a finite number or a non-empty Molang expression.`);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
