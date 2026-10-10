import type { ImportDiagnostic } from "../../domain/conversionSeed";
import { isRecord } from "../../format/runtimeValue";

export type BedrockExpression = number | string;
export type BedrockVector = BedrockExpression | BedrockExpression[];
export type BedrockLoop = boolean | "hold_on_last_frame";

export interface BedrockAnimationDocument {
  format_version: "1.8.0";
  animations: Record<string, BedrockAnimation>;
  animationDiagnostics?: ImportDiagnostic[];
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
