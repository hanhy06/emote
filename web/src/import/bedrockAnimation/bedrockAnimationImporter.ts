import { createDefaultPlayerBehavior } from "../../format/emoteAnimation";
import { sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { MAX_ANIMATION_DURATION_TICKS, requireAnimationDurationTicks, TICKS_PER_SECOND } from "../../format/time";
import type { ImportedAnimation, ImportedProject, ImportDiagnostic } from "../../domain/conversionSeed";
import { PreviewUnavailableError, skippedAnimationIssue } from "../../foundation/diagnostics";
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
import { createBedrockAnimationPreview } from "./bedrockAnimationPreview";
import { createMolangPreviewFallback } from "../common/previewFallback";
import { IDENTITY_MATRIX } from "../../format/matrix";

export function importBedrockAnimationDocument(document: BedrockAnimationDocument, sourceName: string): ImportedProject {
  const sourceStem = sourceName.replace(/\.json$/i, "").trim() || "Bedrock Animation";
  const diagnostics: ImportDiagnostic[] = [...(document.animationDiagnostics ?? [])];
  const nodes = createBedrockPlayerNodes();
  const runtimeNodeIds = new Set(Object.keys(nodes));
  const unknownBoneIds: Record<string, string> = {};
  for (const animation of Object.values(document.animations)) {
    for (const boneName of Object.keys(animation.bones ?? {})) {
      if (resolveBedrockPlayerBone(boneName) || unknownBoneIds[boneName]) continue;
      const base = `bedrock_custom_${sanitizeResourcePath(boneName, "bone").replaceAll("/", "_")}`;
      let id = base;
      for (let suffix = 2; [id, `${id}_x`, `${id}_y`, `${id}_z`].some((candidate) => runtimeNodeIds.has(candidate)); suffix++) id = `${base}_${suffix}`;
      for (const candidate of [id, `${id}_x`, `${id}_y`, `${id}_z`]) runtimeNodeIds.add(candidate);
      unknownBoneIds[boneName] = id;
      nodes[id] = { binding: { sourceNodeId: boneName }, type: "item_display", defaultMatrix: IDENTITY_MATRIX, visible: true, itemStack: { id: "minecraft:air", count: 1 }, itemDisplay: "none" };
    }
  }
  const animations = Object.entries(document.animations).flatMap(([name, animation], index) => {
    const animationDiagnostics: ImportDiagnostic[] = [];
    try {
      collectAnimationDiagnostics(name, animation, animationDiagnostics);
      const imported = importAnimation(name, animation, index, animationDiagnostics, unknownBoneIds);
      diagnostics.push(...animationDiagnostics);
      return [imported];
    } catch (reason) {
      diagnostics.push(skippedAnimationIssue(name, `animations.${name}`, reason));
      return [];
    }
  });
  if (animations.length === 0) {
    const reasons = diagnostics.filter((issue) => issue.code === "animation_skipped").map((issue) => issue.message).join(" ");
    throw new Error(`No Bedrock animations in this file can be imported.${reasons ? ` ${reasons}` : ""}`);
  }
  return {
    source: "bedrock_animation_json",
    sourceName,
    suggestedMetadata: { name: sourceStem, description: `${sourceStem} emote.` },
    suggestedPlayer: createDefaultPlayerBehavior(),
    suggestedNamespace: sanitizeNamespace(sourceStem),
    suggestedRotationDeadzone: 0,
    nodes,
    animations,
    diagnostics,
    resources: new Map(),
  };
}

function importAnimation(name: string, animation: BedrockAnimation, index: number, diagnostics: ImportDiagnostic[], unknownBoneIds: Record<string, string>): ImportedAnimation {
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
  let playbackRate: number | null = null;
  let previewReason: PreviewUnavailableError | undefined;
  try {
    playbackRate = bedrockAnimationPlaybackRate(animation, name);
  } catch (reason) {
    if (!(reason instanceof PreviewUnavailableError)) throw reason;
    previewReason = reason;
  }
  const startDelayTicks = numericStartDelayTicks(animation) ?? 0;
  const maximumAnimationTicks = assumedDuration ? MAX_ANIMATION_DURATION_TICKS - startDelayTicks : undefined;
  if (maximumAnimationTicks !== undefined && maximumAnimationTicks < 1) throw new Error(`${name}.start_delay leaves no time for the animation.`);
  const animationDurationTicks = assumedDuration
    ? maximumAnimationTicks!
    : Math.max(1, Math.round(sourceDuration / (playbackRate ?? 1) * TICKS_PER_SECOND));
  const durationTicks = requireAnimationDurationTicks(
    animationDurationTicks + startDelayTicks,
    `${name}.animation_length`,
  );
  const previewAnimationDurationTicks = assumedDuration ? TICKS_PER_SECOND : animationDurationTicks;
  const runtimeSamplePlan = planBedrockAnimationSamples(animation, previewAnimationDurationTicks, playbackRate ?? 1);
  let loopDelayTicks = 0;
  try {
    loopDelayTicks = Math.max(0, Math.round(evaluateBedrockExpression(animation.loop_delay ?? 0, 0, 1, `${name}.loop_delay`) * TICKS_PER_SECOND));
  } catch (reason) {
    if (!(reason instanceof PreviewUnavailableError)) throw reason;
    previewReason ??= reason;
    diagnostics.push({ severity: "warning", code: "bedrock_animation_property_ignored", message: `${name}.loop_delay has no matching dynamic playback setting and was omitted.`, sourcePath: `animations.${name}.loop_delay` });
  }
  const runtime = createBedrockRuntime(animation, playbackRate, startDelayTicks, durationTicks, runtimeSamplePlan, unknownBoneIds);
  let preview;
  try {
    if (previewReason) throw previewReason;
    preview = createBedrockAnimationPreview(name, animation, previewAnimationDurationTicks, playbackRate ?? 1, startDelayTicks);
  } catch (reason) {
    if (!(reason instanceof PreviewUnavailableError)) throw reason;
    const fallback = createMolangPreviewFallback(name, startDelayTicks + previewAnimationDurationTicks, reason);
    preview = fallback.preview;
    diagnostics.push(fallback.diagnostic);
  }
  return {
    id: sanitizeResourcePath(name, `animation_${index + 1}`),
    name,
    durationTicks,
    playbackMode: animation.loop === true ? "loop" : animation.loop === "hold_on_last_frame" ? "hold" : "once",
    loopDelayTicks,
    events: { start: [], timeline: [], loop: [], stop: [] },
    preview,
    exportAvailability: { exportable: true },
    runtime: { kind: "native", ...runtime },
  };
}

function collectAnimationDiagnostics(name: string, animation: BedrockAnimation, diagnostics: ImportDiagnostic[]): void {
  const ignored = [
    [animation.start_delay !== undefined && numericStartDelayTicks(animation) === null ? animation.start_delay : undefined, "start_delay"],
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
        code: "bedrock_animation_bone_as_air",
        message: `${name} bone ${boneName} was imported as an air item display; its transforms remain playable.`,
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
