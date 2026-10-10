import { formatMinecraftTime, parseAnimationSeconds, TICKS_PER_SECOND } from "../../format/time";
import { importedNodeHints } from "../../domain/conversionSeed";
import type { ImportedProject } from "../../domain/conversionSeed";
import type { ConversionIssue } from "../../foundation/diagnostics";
import type { TimelineEventIR } from "../../domain/animationIR";
import { createDefaultPlayerBehavior } from "../../domain/emoteDefinition";
import { sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import type { EmotecraftFile } from "./emotecraftBinary";
import { convertEmotecraftSong } from "./emotecraftNbs";
import { createEmotecraftAnimationIR } from "./emotecraftAnimationIR";

export function importEmotecraftFile(file: EmotecraftFile, sourceName: string): ImportedProject {
  const name = file.metadata.name?.trim() || sourceName.replace(/\.emotecraft$/i, "").trim() || "Emotecraft Emote";
  const diagnostics: ConversionIssue[] = [];
  const { ir, imported: nodes } = createEmotecraftAnimationIR(file.animation, name, diagnostics);
  const effects = file.animation.effects;
  const timeline: TimelineEventIR[] = [];
  for (const [index, sound] of effects.sounds.entries()) {
    if (!sound.sound.trim()) {
      diagnostics.push({ severity: "warning", code: "emotecraft_effect_ignored", message: `${name} at ${sound.tick}t: an effect without a resource identifier was omitted.`, sourcePath: `effects.sounds[${index}]` });
      continue;
    }
    timeline.push({ time: `${sound.tick}t`, source: { type: "player" }, origin: { type: "root" }, action: { type: "commands", commands: [`playsound ${sound.sound.trim()} master @s ~ ~ ~`] } });
  }
  for (const [index, particle] of effects.particles.entries()) {
    const sourcePath = `effects.particles[${index}]`;
    if (!particle.effect.trim()) {
      diagnostics.push({ severity: "warning", code: "emotecraft_effect_ignored", message: `${name} at ${particle.tick}t: an effect without a resource identifier was omitted.`, sourcePath });
      continue;
    }
    const node = particle.locator.trim() ? Object.entries(ir.nodes).find(([, node]) => node.source?.node_id === particle.locator.trim())?.[0] : undefined;
    timeline.push({ time: `${particle.tick}t`, source: { type: "server" }, origin: node ? { type: "node", node } : { type: "root" }, action: { type: "commands", commands: [`particle ${particle.effect.trim()} ~ ~ ~`] } });
    if (particle.locator.trim() && !node) diagnostics.push({ severity: "warning", code: "emotecraft_effect_origin_approximated", message: `${name} at ${particle.tick}t: effect locator could not be resolved; the root position is used. Review and edit the event.`, sourcePath });
    if (particle.script.trim()) diagnostics.push({ severity: "warning", code: "emotecraft_particle_script_ignored", message: `${name} at ${particle.tick}t: particle pre-effect script was omitted. Review and edit the event.`, sourcePath });
  }
  for (const [index, instruction] of effects.instructions.entries()) {
    const lines = instruction.instruction.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
    const commands = lines.map((line) => line.replace(/^\//, "").trim()).filter(Boolean);
    if (commands.length) timeline.push({ time: `${instruction.tick}t`, source: { type: "player" }, origin: { type: "root" }, action: { type: "commands", commands } });
    if (lines.some((line) => !line.startsWith("/"))) diagnostics.push({ severity: "warning", code: "emotecraft_instruction_approximated", message: `${name} at ${instruction.tick}t: uninterpreted instructions were kept as commands; behavior may differ. Review and edit them.`, sourcePath: `effects.instructions[${index}]` });
  }
  const durationTicks = Math.ceil(Math.max(parseAnimationSeconds(ir.animation.duration), ...timeline.map((event) => parseAnimationSeconds(event.time))) * TICKS_PER_SECOND);
  ir.animation.duration = formatMinecraftTime(durationTicks);
  const song = file.song ? convertEmotecraftSong(file.song, durationTicks) : { events: [], diagnostics: [] };
  diagnostics.push(...song.diagnostics);
  ir.animation.events = { start: [], timeline: [...timeline, ...song.events].sort((first, second) => parseAnimationSeconds(first.time) - parseAnimationSeconds(second.time)), loop: [], stop: [] };
  ir.metadata = { name, description: file.metadata.description?.trim() || `${name} emote.`,
    ...(file.metadata.author ? { author: file.metadata.author } : {}), ...(file.metadata.badges.length ? { badges: file.metadata.badges } : {}) };
  if (file.icon) diagnostics.push({ severity: "warning", code: "emotecraft_icon_ignored", message: "Embedded icon is outside the animation output contract." });
  return {
    source: "emotecraft_binary", sourceName, suggestedMetadata: ir.metadata, suggestedPlayer: createDefaultPlayerBehavior(),
    suggestedNamespace: sanitizeNamespace(name), suggestedRotationDeadzone: 0, nodeHints: importedNodeHints(nodes),
    animations: [{ ir, id: sanitizeResourcePath(name, "emotecraft_emote"), name, suggestedMetadata: ir.metadata,
    }], diagnostics, resources: new Map(),
  };
}
