import type { ImportedAnimation, ImportedProject, ImportDiagnostic } from "../../domain/conversionSeed";
import { createDefaultPlayerBehavior } from "../../format/emoteAnimation";
import { sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { requireAnimationDurationTicks } from "../../format/time";
import { projectEmotecraftPreview } from "./emotecraftAnimationPreview";
import { projectEmotecraftRuntime } from "./emotecraftAnimationRuntime";
import { sampleEmotecraftAnimation } from "./emotecraftAnimationSampling";
import type { EmotecraftFile, PalAnimation } from "./emotecraftBinary";
import { convertEmotecraftSong } from "./emotecraftNbs";
import { createEmotecraftNodes } from "./emotecraftPlayerRig";

export function importEmotecraftFile(file: EmotecraftFile, sourceName: string): ImportedProject {
  const animation = file.animation;
  const sourceStem = sourceName.replace(/\.emotecraft$/i, "").trim() || "Emotecraft Emote";
  const displayName = file.metadata.name?.trim() || sourceStem;
  const durationTicks = requireAnimationDurationTicks(Math.max(1, Math.ceil(animation.lengthTicks)), `${displayName} duration`);
  const loopStartTicks = requireLoopStartTick(animation, durationTicks, displayName);
  const song = file.song ? convertEmotecraftSong(file.song, durationTicks) : { events: [], diagnostics: [] };
  const diagnostics = [...collectDiagnostics(file), ...song.diagnostics];
  const samples = sampleEmotecraftAnimation(animation, displayName, durationTicks);
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
    preview: projectEmotecraftPreview(samples),
    exportAvailability: { exportable: true },
    runtime: projectEmotecraftRuntime(samples),
  };
  return {
    source: "emotecraft_binary",
    sourceName,
    suggestedMetadata: metadata,
    suggestedPlayer: createDefaultPlayerBehavior(),
    suggestedNamespace: sanitizeNamespace(displayName),
    suggestedRotationDeadzone: 0,
    nodes: createEmotecraftNodes(samples.slices, samples.bindMatrices),
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
  for (const name of ["cape", "elytra", "left_item", "right_item"]) {
    if (file.animation.bones[name]) diagnostics.push({ severity: "warning", code: "emotecraft_accessory_bone_ignored", message: `Emotecraft bone ${name} is not representable by player skin slices and was ignored.` });
  }
  return diagnostics;
}
