import { Euler, MathUtils, Matrix4, Quaternion, Vector3 } from "three";
import { createDefaultPlayerBehavior } from "../../format/emoteAnimation";
import { matrix4ToRowMajor } from "../../format/matrix";
import { sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { MAX_ANIMATION_DURATION_TICKS, requireAnimationDurationTicks, TICKS_PER_SECOND } from "../../format/time";
import type { ImportedAnimation, ImportedProject, ImportDiagnostic } from "../../domain/conversionSeed";
import { ConversionError } from "../../foundation/diagnostics";
import type { BedrockAnimation, BedrockAnimationDocument, BedrockExpression } from "./bedrockAnimationSchema";
import {
  bedrockAnimationDurationSeconds,
  bedrockAnimationPlaybackRate,
  bedrockAnimationUsesTime,
  evaluateBedrockChannel,
  evaluateBedrockExpression,
  planBedrockAnimationSamples,
} from "./bedrockAnimationBaker";
import {
  BEDROCK_PLAYER_BONES,
  BEDROCK_PLAYER_RENDER_SCALE,
  bedrockPlayerBoneById,
  createBedrockPlayerNodes,
  isHiddenBedrockAccessoryBone,
  resolveBedrockPlayerBone,
} from "./bedrockPlayerRig";
import { createBedrockRuntime } from "./bedrockAnimationOutput";

type Transform = { position: number[]; rotation: number[]; scale: number[] };

export function importBedrockAnimationDocument(document: BedrockAnimationDocument, sourceName: string): ImportedProject {
  const sourceStem = sourceName.replace(/\.json$/i, "").trim() || "Bedrock Animation";
  const bindMatrices = buildWorldMatrices(new Map());
  const diagnostics: ImportDiagnostic[] = [];
  const animations = Object.entries(document.animations).flatMap(([name, animation], index) => {
    try {
      collectAnimationDiagnostics(name, animation, diagnostics);
      return [importAnimation(name, animation, index, diagnostics)];
    } catch (reason) {
      if (reason instanceof ConversionError && reason.code === "unsupported_bedrock_molang") {
        const message = `${name} contains Molang that the converter cannot evaluate for preview. The Create pose will be shown, while the original Molang is preserved for runtime export.`;
        diagnostics.push({
          severity: "warning",
          code: "bedrock_animation_molang_unavailable",
          message,
          sourcePath: reason.sourcePath ?? `animations.${name}`,
        });
        return [createPreviewOnlyAnimation(name, animation, index, message)];
      }
      diagnostics.push({
        severity: "warning",
        code: "bedrock_animation_skipped",
        message: `${name} was skipped: ${reason instanceof Error ? reason.message : "unsupported animation"}`,
        sourcePath: `animations.${name}`,
      });
      return [];
    }
  });
  if (animations.length === 0) {
    const reasons = diagnostics.filter((issue) => issue.code === "bedrock_animation_skipped").map((issue) => issue.message).join(" ");
    throw new Error(`No Bedrock animations in this file can be baked.${reasons ? ` ${reasons}` : ""}`);
  }
  return {
    source: "bedrock_animation_json",
    sourceName,
    suggestedMetadata: { name: sourceStem, description: `${sourceStem} emote.` },
    suggestedPlayer: createDefaultPlayerBehavior(),
    suggestedNamespace: sanitizeNamespace(sourceStem),
    nodes: createBedrockPlayerNodes(bindMatrices),
    animations,
    diagnostics,
    resources: new Map(),
  };
}

function createPreviewOnlyAnimation(name: string, animation: BedrockAnimation, index: number, reason: string): ImportedAnimation {
  const sourceDuration = bedrockAnimationDurationSeconds(animation);
  const durationTicks = sourceDuration === 0 && bedrockAnimationUsesTime(animation)
    ? MAX_ANIMATION_DURATION_TICKS
    : sourceDuration > 0 ? Math.max(1, Math.round(sourceDuration * TICKS_PER_SECOND)) : TICKS_PER_SECOND;
  return {
    id: sanitizeResourcePath(name, `animation_${index + 1}`),
    name,
    durationTicks,
    loop: animation.loop === true ? "loop" : animation.loop === "hold_on_last_frame" ? "hold" : "once",
    loopDelayTicks: 0,
    tracks: {},
    events: { start: [], timeline: [], loop: [], stop: [] },
    availability: { preview: "create_pose", exportable: true, reason },
    preview: { durationTicks: TICKS_PER_SECOND, tracks: {} },
    runtime: createBedrockRuntime(animation, durationTicks, null),
  };
}

function importAnimation(name: string, animation: BedrockAnimation, index: number, diagnostics: ImportDiagnostic[]): ImportedAnimation {
  const sourceDuration = bedrockAnimationDurationSeconds(animation);
  const assumedDuration = sourceDuration === 0 && bedrockAnimationUsesTime(animation);
  if (assumedDuration) {
    diagnostics.push({
      severity: "warning",
      code: "bedrock_animation_duration_assumed",
      message: `${name} uses time-dependent Molang without a duration. The skin preview uses 1 second (20 ticks), and runtime export uses the 10-minute limit (12000 ticks). To set an exact duration, edit animations.${name}.animation_length in the source Bedrock JSON.`,
      sourcePath: `animations.${name}.animation_length`,
    });
  }
  const playbackRate = bedrockAnimationPlaybackRate(animation, name);
  const durationTicks = requireAnimationDurationTicks(
    assumedDuration ? MAX_ANIMATION_DURATION_TICKS : Math.max(1, Math.round(sourceDuration / playbackRate * TICKS_PER_SECOND)),
    `${name}.animation_length`,
  );
  const previewDurationTicks = assumedDuration ? TICKS_PER_SECOND : durationTicks;
  const samplePlan = planBedrockAnimationSamples(animation, previewDurationTicks, playbackRate);
  const tracks: ImportedAnimation["tracks"] = Object.fromEntries(BEDROCK_PLAYER_BONES.filter((bone) => bone.cube).map((bone) => [bone.id, {
    transforms: [],
    visibility: [],
  }]));
  for (let tick = 0; tick <= previewDurationTicks; tick++) {
    const sourceTime = samplePlan.sourceTimes.get(tick) ?? tick / TICKS_PER_SECOND * playbackRate;
    const worldMatrices = buildWorldMatrices(collectTransforms(name, animation, sourceTime));
    for (const bone of BEDROCK_PLAYER_BONES) {
      if (!bone.cube) continue;
      const matrix = worldMatrices.get(bone.id);
      if (!matrix) throw new Error(`Missing animated matrix for Bedrock player bone ${bone.id}.`);
      tracks[bone.id].transforms.push({
        tick,
        matrix: matrix4ToRowMajor(matrix, `${name}/${bone.id}/${tick}`),
        interpolation: tick === 0 || samplePlan.stepTicks.has(tick) ? { type: "step" } : { type: "linear", durationTicks: 1 },
      });
    }
  }
  return {
    id: sanitizeResourcePath(name, `animation_${index + 1}`),
    name,
    durationTicks,
    loop: animation.loop === true ? "loop" : animation.loop === "hold_on_last_frame" ? "hold" : "once",
    loopDelayTicks: Math.max(0, Math.round(evaluateBedrockExpression(animation.loop_delay ?? 0, 0, 1, `${name}.loop_delay`) * TICKS_PER_SECOND)),
    tracks,
    events: { start: [], timeline: [], loop: [], stop: [] },
    preview: { durationTicks: previewDurationTicks, tracks },
    runtime: createBedrockRuntime(animation, durationTicks, playbackRate),
  };
}

function collectTransforms(name: string, animation: BedrockAnimation, time: number): Map<string, Transform> {
  const transforms = new Map<string, Transform>();
  for (const [sourceBoneName, sourceBone] of Object.entries(animation.bones ?? {})) {
    const bone = resolveBedrockPlayerBone(sourceBoneName);
    if (!bone) continue;
    transforms.set(bone.id, {
      position: evaluateBedrockChannel(sourceBone.position, time, [0, 0, 0], `${name}.${sourceBoneName}.position`),
      rotation: evaluateBedrockChannel(sourceBone.rotation, time, [0, 0, 0], `${name}.${sourceBoneName}.rotation`),
      scale: evaluateBedrockChannel(sourceBone.scale, time, [1, 1, 1], `${name}.${sourceBoneName}.scale`),
    });
  }
  return transforms;
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

function collectAnimationDiagnostics(name: string, animation: BedrockAnimation, diagnostics: ImportDiagnostic[]): void {
  const ignored = [
    [animation.start_delay, "start_delay"],
    [animation.blend_weight, "blend_weight"],
    [animation.override_previous_animation, "override_previous_animation"],
    [animation.particle_effects, "particle_effects"],
    [animation.sound_effects, "sound_effects"],
    [animation.timeline, "timeline"],
  ] as const;
  for (const [value, property] of ignored) {
    if (value === undefined) continue;
    diagnostics.push({
      severity: "warning",
      code: "bedrock_animation_property_ignored",
      message: `${name}.${property} is not represented by the experimental importer.`,
      sourcePath: `animations.${name}.${property}`,
    });
  }
  for (const [boneName, bone] of Object.entries(animation.bones ?? {})) {
    if (!resolveBedrockPlayerBone(boneName) && !isHiddenBedrockAccessoryBone(boneName)) {
      diagnostics.push({
        severity: "warning",
        code: "bedrock_animation_bone_ignored",
        message: `${name} bone ${boneName} is not part of the supported player rig and was ignored.`,
        sourcePath: `animations.${name}.bones.${boneName}`,
      });
    }
    if (bone.relative_to !== undefined) {
      diagnostics.push({
        severity: "warning",
        code: "bedrock_relative_rotation_ignored",
        message: `${name} bone ${boneName} uses relative_to.rotation, which was treated as normal local rotation.`,
        sourcePath: `animations.${name}.bones.${boneName}.relative_to`,
      });
    }
  }
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
