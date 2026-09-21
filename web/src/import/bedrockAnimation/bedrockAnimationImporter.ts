import { createDefaultPlayerBehavior } from "../../format/emoteAnimation";
import { sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { MAX_ANIMATION_DURATION_TICKS, requireAnimationDurationTicks, TICKS_PER_SECOND } from "../../format/time";
import type { ImportedAnimation, ImportedProject, ImportDiagnostic } from "../../domain/conversionSeed";
import { ConversionError } from "../../foundation/diagnostics";
import type { BedrockAnimation, BedrockAnimationDocument } from "./bedrockAnimationSchema";
import {
  bedrockAnimationDurationSeconds,
  bedrockAnimationPlaybackRate,
  bedrockAnimationUsesTime,
  evaluateBedrockExpression,
  planBedrockAnimationSamples,
} from "./bedrockAnimationBaker";
import {
  createBedrockPlayerNodes,
  isHiddenBedrockAccessoryBone,
  resolveBedrockPlayerBone,
} from "./bedrockPlayerRig";
import { createBedrockRuntime } from "./bedrockAnimationOutput";
import { createBedrockAnimationPreview, createBedrockCreatePosePreview } from "./bedrockAnimationPreview";

export function importBedrockAnimationDocument(document: BedrockAnimationDocument, sourceName: string): ImportedProject {
  const sourceStem = sourceName.replace(/\.json$/i, "").trim() || "Bedrock Animation";
  const diagnostics: ImportDiagnostic[] = [];
  const animations = Object.entries(document.animations).flatMap(([name, animation], index) => {
    try {
      collectAnimationDiagnostics(name, animation, diagnostics);
      return [importAnimation(name, animation, index, diagnostics)];
    } catch (reason) {
      if (reason instanceof ConversionError && reason.code === "unsupported_bedrock_molang") {
        const message = `${name}: preview uses the Create pose; runtime Molang is preserved.`;
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
    suggestedRotationDeadzone: 0,
    nodes: createBedrockPlayerNodes(),
    animations,
    diagnostics,
    resources: new Map(),
  };
}

function createPreviewOnlyAnimation(name: string, animation: BedrockAnimation, index: number, reason: string): ImportedAnimation {
  const sourceDuration = bedrockAnimationDurationSeconds(animation);
  const animationDurationTicks = sourceDuration === 0 && bedrockAnimationUsesTime(animation)
    ? MAX_ANIMATION_DURATION_TICKS
    : sourceDuration > 0 ? Math.max(1, Math.round(sourceDuration * TICKS_PER_SECOND)) : TICKS_PER_SECOND;
  const startDelayTicks = numericStartDelayTicks(animation) ?? 0;
  const durationTicks = requireAnimationDurationTicks(
    animationDurationTicks === MAX_ANIMATION_DURATION_TICKS ? animationDurationTicks : animationDurationTicks + startDelayTicks,
    `${name} duration`,
  );
  return {
    id: sanitizeResourcePath(name, `animation_${index + 1}`),
    name,
    durationTicks,
    playbackMode: animation.loop === true ? "loop" : animation.loop === "hold_on_last_frame" ? "hold" : "once",
    loopDelayTicks: 0,
    events: { start: [], timeline: [], loop: [], stop: [] },
    preview: createBedrockCreatePosePreview(reason),
    exportAvailability: { exportable: true },
    runtime: { kind: "native", ...createBedrockRuntime(animation, null, startDelayTicks, durationTicks) },
  };
}

function importAnimation(name: string, animation: BedrockAnimation, index: number, diagnostics: ImportDiagnostic[]): ImportedAnimation {
  const sourceDuration = bedrockAnimationDurationSeconds(animation);
  const assumedDuration = sourceDuration === 0 && bedrockAnimationUsesTime(animation);
  if (assumedDuration) {
    diagnostics.push({
      severity: "warning",
      code: "bedrock_animation_duration_assumed",
      message: `${name}: no animation_length. Preview: 20 ticks; export: 12000 ticks. To change it, edit animations.${name}.animation_length.`,
      sourcePath: `animations.${name}.animation_length`,
    });
  }
  const playbackRate = bedrockAnimationPlaybackRate(animation, name);
  const startDelayTicks = numericStartDelayTicks(animation) ?? 0;
  const maximumAnimationTicks = assumedDuration ? MAX_ANIMATION_DURATION_TICKS - startDelayTicks : undefined;
  if (maximumAnimationTicks !== undefined && maximumAnimationTicks < 1) throw new Error(`${name}.start_delay leaves no time for the animation.`);
  const animationDurationTicks = assumedDuration
    ? maximumAnimationTicks!
    : Math.max(1, Math.round(sourceDuration / playbackRate * TICKS_PER_SECOND));
  const durationTicks = requireAnimationDurationTicks(
    animationDurationTicks + startDelayTicks,
    `${name}.animation_length`,
  );
  const previewAnimationDurationTicks = assumedDuration ? TICKS_PER_SECOND : animationDurationTicks;
  const runtimeSamplePlan = planBedrockAnimationSamples(animation, previewAnimationDurationTicks, playbackRate);
  return {
    id: sanitizeResourcePath(name, `animation_${index + 1}`),
    name,
    durationTicks,
    playbackMode: animation.loop === true ? "loop" : animation.loop === "hold_on_last_frame" ? "hold" : "once",
    loopDelayTicks: Math.max(0, Math.round(evaluateBedrockExpression(animation.loop_delay ?? 0, 0, 1, `${name}.loop_delay`) * TICKS_PER_SECOND)),
    events: { start: [], timeline: [], loop: [], stop: [] },
    preview: createBedrockAnimationPreview(name, animation, previewAnimationDurationTicks, playbackRate, startDelayTicks),
    exportAvailability: { exportable: true },
    runtime: { kind: "native", ...createBedrockRuntime(animation, playbackRate, startDelayTicks, durationTicks, runtimeSamplePlan) },
  };
}

function collectAnimationDiagnostics(name: string, animation: BedrockAnimation, diagnostics: ImportDiagnostic[]): void {
  const ignored = [
    [animation.start_delay !== undefined && numericStartDelayTicks(animation) === null ? animation.start_delay : undefined, "start_delay"],
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

function numericStartDelayTicks(animation: BedrockAnimation): number | null {
  const value = animation.start_delay;
  if (value === undefined) return 0;
  const numeric = typeof value === "number" ? value : Number(value.trim());
  if (!Number.isFinite(numeric) || numeric < 0) return null;
  return Math.round(numeric * TICKS_PER_SECOND);
}
