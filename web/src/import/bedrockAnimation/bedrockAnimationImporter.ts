import { Euler, MathUtils, Matrix4, Quaternion, Vector3 } from "three";
import { createDefaultPlayerBehavior } from "../../format/emoteAnimation";
import { matrix4ToRowMajor } from "../../format/matrix";
import { sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { requireAnimationDurationTicks, TICKS_PER_SECOND } from "../../format/time";
import type { ImportedAnimation, ImportedProject } from "../../domain/conversionSeed";
import type { BedrockAnimation, BedrockAnimationDocument, BedrockChannel, BedrockExpression } from "./bedrockAnimationSchema";
import {
  BEDROCK_PLAYER_BONES,
  BEDROCK_PLAYER_RENDER_SCALE,
  bedrockPlayerBoneById,
  createBedrockPlayerNodes,
  resolveBedrockPlayerBone,
} from "./bedrockPlayerRig";

type Transform = { position: number[]; rotation: number[]; scale: number[] };

export function importBedrockAnimationDocument(document: BedrockAnimationDocument, sourceName: string): ImportedProject {
  const sourceStem = sourceName.replace(/\.json$/i, "").trim() || "Bedrock Animation";
  const bindMatrices = buildWorldMatrices(new Map());
  const animations = Object.entries(document.animations).map(([name, animation], index) => importAnimation(name, animation, index));
  return {
    source: "bedrock_animation_json",
    sourceName,
    suggestedMetadata: { name: sourceStem, description: `${sourceStem} emote.` },
    suggestedPlayer: createDefaultPlayerBehavior(),
    suggestedNamespace: sanitizeNamespace(sourceStem),
    nodes: createBedrockPlayerNodes(bindMatrices),
    animations,
    diagnostics: [],
    resources: new Map(),
  };
}

function importAnimation(name: string, animation: BedrockAnimation, index: number): ImportedAnimation {
  const durationTicks = requireAnimationDurationTicks(
    Math.max(1, Math.round((animation.animation_length ?? 0) * TICKS_PER_SECOND)),
    `${name}.animation_length`,
  );
  const transforms = collectStaticTransforms(name, animation);
  const worldMatrices = buildWorldMatrices(transforms);
  const tracks: ImportedAnimation["tracks"] = {};
  for (const bone of BEDROCK_PLAYER_BONES) {
    if (!bone.cube) continue;
    const matrix = worldMatrices.get(bone.id);
    if (!matrix) throw new Error(`Missing animated matrix for Bedrock player bone ${bone.id}.`);
    tracks[bone.id] = {
      transforms: [{ tick: 0, matrix: matrix4ToRowMajor(matrix, `${name}/${bone.id}/0`), interpolation: { type: "step" } }],
      visibility: [],
    };
  }
  return {
    id: sanitizeResourcePath(name, `animation_${index + 1}`),
    name,
    durationTicks,
    loop: animation.loop === true ? "loop" : animation.loop === "hold_on_last_frame" ? "hold" : "once",
    loopDelayTicks: Math.max(0, Math.round(evaluateConstant(animation.loop_delay ?? 0, `${name}.loop_delay`) * TICKS_PER_SECOND)),
    tracks,
    events: { start: [], timeline: [], loop: [], stop: [] },
  };
}

function collectStaticTransforms(name: string, animation: BedrockAnimation): Map<string, Transform> {
  const transforms = new Map<string, Transform>();
  for (const [sourceBoneName, sourceBone] of Object.entries(animation.bones ?? {})) {
    const bone = resolveBedrockPlayerBone(sourceBoneName);
    if (!bone) continue;
    transforms.set(bone.id, {
      position: evaluateStaticChannel(sourceBone.position, [0, 0, 0], `${name}.${sourceBoneName}.position`),
      rotation: evaluateStaticChannel(sourceBone.rotation, [0, 0, 0], `${name}.${sourceBoneName}.rotation`),
      scale: evaluateStaticChannel(sourceBone.scale, [1, 1, 1], `${name}.${sourceBoneName}.scale`),
    });
  }
  return transforms;
}

function evaluateStaticChannel(channel: BedrockChannel | undefined, fallback: number[], path: string): number[] {
  if (channel === undefined) return fallback;
  if (typeof channel === "object" && channel !== null && !Array.isArray(channel)) {
    throw new Error(`${path} uses keyframes; animated Bedrock channels are not available in this experimental stage.`);
  }
  const values = Array.isArray(channel) ? channel : [channel];
  if (values.length === 1) {
    const value = evaluateConstant(values[0], `${path}[0]`);
    return [value, value, value];
  }
  return values.map((value, axis) => evaluateConstant(value, `${path}[${axis}]`));
}

function evaluateConstant(expression: BedrockExpression, path: string): number {
  if (typeof expression === "number") return expression;
  const value = Number(expression.trim());
  if (Number.isFinite(value)) return value;
  throw new Error(`${path} must be constant until Molang baking is enabled.`);
}

function buildWorldMatrices(transforms: ReadonlyMap<string, Transform>): Map<string, Matrix4> {
  const result = new Map<string, Matrix4>();
  const visit = (id: string): Matrix4 => {
    const cached = result.get(id);
    if (cached) return cached;
    const bone = bedrockPlayerBoneById(id);
    const parent = bone.parent ? bedrockPlayerBoneById(bone.parent) : undefined;
    const transform = transforms.get(id) ?? { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] };
    const local = composeTransform(
      bone.pivot.map((value, axis) => ((value - (parent?.pivot[axis] ?? 0)) + transform.position[axis]) / 16),
      transform.rotation,
      transform.scale,
    );
    const world = parent
      ? visit(parent.id).clone().multiply(local)
      : new Matrix4().makeScale(BEDROCK_PLAYER_RENDER_SCALE, BEDROCK_PLAYER_RENDER_SCALE, BEDROCK_PLAYER_RENDER_SCALE).multiply(local);
    result.set(id, world);
    return world;
  };
  BEDROCK_PLAYER_BONES.forEach((bone) => visit(bone.id));
  return result;
}

function composeTransform(position: number[], rotation: number[], scale: number[]): Matrix4 {
  return new Matrix4().compose(
    new Vector3(position[0], position[1], position[2]),
    new Quaternion().setFromEuler(new Euler(
      MathUtils.degToRad(rotation[0]),
      MathUtils.degToRad(rotation[1]),
      MathUtils.degToRad(rotation[2]),
      "ZYX",
    )),
    new Vector3(scale[0], scale[1], scale[2]),
  );
}
