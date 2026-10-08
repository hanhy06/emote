import type { BakedRuntimeNodeTracks } from "../../domain/minecraftData";
import type { ImportedAnimation, ImportedProject, ImportDiagnostic } from "../../domain/conversionSeed";
import { createDefaultPlayerBehavior } from "../../format/emoteAnimation";
import { sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { requireAnimationDurationTicks } from "../../format/time";
import { createEmotecraftRuntime, sampleEmotecraftAnimation } from "./emotecraftAnimationSampling";
import type { EmotecraftFile, PalAnimation } from "./emotecraftBinary";
import { convertEmotecraftSong } from "./emotecraftNbs";
import { createEmotecraftNodes, EMOTECRAFT_PLAYER_PARTS } from "./emotecraftPlayerRig";
import { hasMolangExpression } from "../../format/molang/runtimeAnalysis";
import { ConversionError, PreviewUnavailableError } from "../../foundation/diagnostics";
import { createMolangPreviewFallback } from "../common/previewFallback";
import { matrix4ToRowMajor } from "../../format/matrix";

export function importEmotecraftFile(file: EmotecraftFile, sourceName: string): ImportedProject {
  const animation = file.animation;
  const sourceStem = sourceName.replace(/\.emotecraft$/i, "").trim() || "Emotecraft Emote";
  const displayName = file.metadata.name?.trim() || sourceStem;
  const durationTicks = requireAnimationDurationTicks(Math.max(1, Math.ceil(animation.lengthTicks)), `${displayName} duration`);
  const loopStartTicks = requireLoopStartTick(animation, durationTicks, displayName);
  const song = file.song ? convertEmotecraftSong(file.song, durationTicks) : { events: [], diagnostics: [] };
  const diagnostics = [...collectDiagnostics(file), ...song.diagnostics];
  const knownBones = new Set(["body", ...EMOTECRAFT_PLAYER_PARTS.map((part) => part.bone)]);
  const airBoneIds: Record<string, string> = {};
  for (const name of new Set([...Object.keys(animation.bones), ...Object.keys(animation.pivots)])) {
    if (knownBones.has(name)) continue;
    const base = `emotecraft_custom_${sanitizeResourcePath(name, "bone").replaceAll("/", "_")}`;
    let id = base;
    for (let suffix = 2; Object.values(airBoneIds).includes(id); suffix++) id = `${base}_${suffix}`;
    airBoneIds[name] = id;
    diagnostics.push({ severity: "warning", code: "emotecraft_bone_as_air", message: `Emotecraft bone ${name} was imported as an air item display; its transforms remain playable.` });
  }
  let samples;
  let previewFallback;
  try {
    samples = sampleEmotecraftAnimation(animation, displayName, durationTicks, { airBoneIds });
  } catch (reason) {
    if (!(reason instanceof ConversionError) || !["unsupported_emotecraft_molang", "unsupported_emotecraft_runtime_molang"].includes(reason.code)) throw reason;
    samples = sampleEmotecraftAnimation(animation, displayName, durationTicks, { createPose: true, airBoneIds });
    previewFallback = createMolangPreviewFallback(displayName, durationTicks, new PreviewUnavailableError(reason.code, reason.message, reason.sourcePath));
    diagnostics.push(previewFallback.diagnostic);
  }
  const nodes = createEmotecraftNodes(samples.slices, samples.bindMatrices);
  for (const [name, id] of Object.entries(airBoneIds)) nodes[id] = { binding: { sourceNodeId: name }, type: "item_display", defaultMatrix: matrix4ToRowMajor(samples.bindMatrices.get(id)!, `${name} bind matrix`), visible: true, itemStack: { id: "minecraft:air", count: 1 }, itemDisplay: "none" };
  const usesMolang = Object.values(animation.bones).some((bone) => [...bone.position, ...bone.rotation, ...bone.scale, bone.bend].some((frames) => frames.some((frame) => hasMolangExpression(frame.start) || hasMolangExpression(frame.end) || frame.easingArgs.some((args) => args.some(hasMolangExpression)))));
  const tracks: Record<string, BakedRuntimeNodeTracks> = Object.fromEntries(Object.entries(samples.transforms).map(([nodeId, frames]) => [nodeId, {
    transforms: frames.map((frame) => ({
      tick: frame.tick,
      matrix: frame.matrix,
      interpolation: frame.step ? { type: "step" } : { type: "linear", durationTicks: 1 },
    })),
    visibility: [],
    nbt: [],
  }]));
  const metadata = {
    name: displayName,
    description: file.metadata.description?.trim() || `${displayName} emote.`,
    ...(file.metadata.author ? { author: file.metadata.author } : {}),
    ...(file.metadata.badges.length ? { badges: file.metadata.badges } : {}),
  };
  const importedAnimation: ImportedAnimation = {
    id: sanitizeResourcePath(displayName, "emotecraft_emote"),
    name: displayName,
    suggestedMetadata: metadata,
    durationTicks,
    playbackMode: animation.loop === "once" ? "once" : animation.loop === "hold" ? "hold" : "loop",
    loopStartTicks,
    loopDelayTicks: 0,
    events: { start: [], timeline: song.events, loop: [], stop: [] },
    preview: previewFallback?.preview ?? {
      durationTicks: samples.durationTicks,
      availability: { status: "full" },
      tracks: Object.fromEntries(Object.entries(tracks).map(([nodeId, track]) => [nodeId, { transforms: track.transforms, visibility: track.visibility }])),
    },
    exportAvailability: { exportable: true },
    runtime: usesMolang ? createEmotecraftRuntime(animation, nodes, samples, airBoneIds) : { kind: "baked", tracks },
  };
  return {
    source: "emotecraft_binary",
    sourceName,
    suggestedMetadata: metadata,
    suggestedPlayer: createDefaultPlayerBehavior(),
    suggestedNamespace: sanitizeNamespace(displayName),
    suggestedRotationDeadzone: 0,
    nodes,
    animations: [importedAnimation],
    diagnostics,
    resources: new Map(),
  };
}

function requireLoopStartTick(animation: PalAnimation, durationTicks: number, displayName: string): number {
  if (animation.loop !== "loop_from_tick") return 0;
  const tick = Math.round(animation.loopStartTick);
  if (!Number.isFinite(animation.loopStartTick) || tick < 0 || tick >= durationTicks) {
    throw new Error(`${displayName} loop start must resolve within 0..${durationTicks - 1} ticks.`);
  }
  return tick;
}

function collectDiagnostics(file: EmotecraftFile): ImportDiagnostic[] {
  const diagnostics: ImportDiagnostic[] = [];
  if (file.icon) diagnostics.push({ severity: "warning", code: "emotecraft_icon_ignored", message: "The embedded Emotecraft icon is not part of the emote animation format and was ignored." });
  for (const [kind, count] of [["sound", file.animation.effects.sounds.length], ["particle", file.animation.effects.particles.length], ["instruction", file.animation.effects.instructions.length]] as const) {
    if (count) diagnostics.push({ severity: "warning", code: `emotecraft_${kind}_effects_ignored`, message: `${count} Emotecraft ${kind} effect(s) cannot be converted automatically and were ignored.` });
  }
  return diagnostics;
}
