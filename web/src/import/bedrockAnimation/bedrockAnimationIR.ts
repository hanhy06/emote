import { sourceSecondsTime, parseAnimationSeconds } from "../../format/time";
import type { AnimationIR, CurveIR, DriverIR, NodeIR, TimelineEventIR, VectorValueIR } from "../../domain/animationIR";
import { scalarIR, sourceDelayIR } from "../../domain/animationIRConversion";
import type { ImportedNode } from "../../domain/conversionSeed";
import type { ConversionIssue } from "../../foundation/diagnostics";
import { importedNodeIR } from "../common/blockbenchAnimationIR";
import { affineMolang, molangScalar, negateMolang, type MolangVector } from "../common/molangVector";
import type { BedrockAnimation, BedrockChannel, BedrockExpression, BedrockKeyframe, BedrockKeyframeValue, BedrockVector } from "./bedrockAnimationSchema";
import { BEDROCK_PLAYER_BONES, BEDROCK_PLAYER_RENDER_SCALE, BEDROCK_PLAYER_SLICES, resolveBedrockPlayerBone } from "./bedrockPlayerRig";
import { bedrockPositionToCanonical, bedrockRotationToCanonical } from "./coordinateSpace";
import { isRecord } from "../../format/runtimeValue";

export function createBedrockAnimationIR(animation: BedrockAnimation, name: string, imported: Record<string, ImportedNode>, unknownBoneIds: Record<string, string>, diagnostics: ConversionIssue[]): AnimationIR {
  const sourceDuration = bedrockAnimationDurationSeconds(animation);
  const assumedDuration = sourceDuration === 0 && bedrockAnimationUsesTime(animation);
  if (assumedDuration) diagnostics.push({
    severity: "warning", code: "bedrock_animation_duration_assumed",
    message: `${name}: no animation_length. Duration: 600 seconds. To change it, edit animations.${name}.animation_length.`,
    sourcePath: `animations.${name}.animation_length`,
  });
  const nodes: Record<string, NodeIR> = { scene: { transform: [{ id: "scale", op: "scale", value: [BEDROCK_PLAYER_RENDER_SCALE, BEDROCK_PLAYER_RENDER_SCALE, BEDROCK_PLAYER_RENDER_SCALE] }] } };
  const timeline: TimelineEventIR[] = [];
  const ir: AnimationIR = {
    id: "emote:imported", metadata: { name, description: `${name} emote.` }, nodes,
    animation: {
      duration: sourceSecondsTime(animation.animation_length !== undefined && typeof animation.animation_length !== "number" ? animation.animation_length : Math.max(0.05, sourceDuration || (assumedDuration ? 600 : 0.05),
        ...[animation.particle_effects, animation.sound_effects, animation.timeline].flatMap((events) => events && typeof events === "object" && !Array.isArray(events) ? Object.keys(events).map(Number) : []))),
      ...(animation.anim_time_update !== undefined ? { clock: { type: "molang", expression: String(animation.anim_time_update) } as const } : {}),
      playback: { mode: animation.loop === true ? "loop" : animation.loop === "hold_on_last_frame" ? "hold" : "once",
        start_delay: sourceDelayIR(animation.start_delay ?? 0), loop_delay: sourceDelayIR(animation.loop_delay ?? 0) },
      tracks: [], events: { timeline },
    },
  };
  const unknown = Object.entries(unknownBoneIds).map(([sourceName, id]) => ({ id, sourceName, parent: undefined, pivot: [0, 0, 0] as const }));
  const blend = molangScalar(animation.blend_weight ?? 1);
  for (const bone of [...BEDROCK_PLAYER_BONES, ...unknown]) {
    const id = `bone:${bone.id}`;
    const parentPivot = BEDROCK_PLAYER_BONES.find((p) => p.id === bone.parent)?.pivot ?? [0, 0, 0];
    const position = bedrockPositionToCanonical(bone.pivot.map((v, axis) => v - parentPivot[axis]), (v) => -v).map((v) => v / 16);
    const source = Object.entries(animation.bones ?? {}).find(([name]) => (resolveBedrockPlayerBone(name)?.id ?? unknownBoneIds[name]) === bone.id)?.[1];
    nodes[id] = { name: bone.sourceName, parent: bone.parent ? `bone:${bone.parent}` : "scene",
      source: { node_id: bone.sourceName, ...(unknownBoneIds[bone.sourceName] ? { missing_geometry: true, editor_node_id: bone.id } : {}) },
      ...(source?.relative_to?.rotation === "entity" ? { inherit: { rotation: "entity" } as const } : {}),
      transform: [
        { id: "position", op: "translate", value: position },
        { id: "rotation", op: "rotate_euler", order: "ZYX", value: [0, 0, 0] },
        { id: "scale", op: "scale", value: [1, 1, 1] },
      ],
    };
    for (const slice of BEDROCK_PLAYER_SLICES.filter((s) => s.bone.id === bone.id)) {
      const n = imported[slice.id]; if (!n) continue;
      nodes[slice.id] = { ...importedNodeIR(n, id), source: { editor_node_id: slice.id } };
    }
    if (!source) continue;
    for (const channel of ["position", "rotation", "scale"] as const) {
      const c = source[channel]; if (c === undefined) continue;
      const transform = (v: MolangVector): VectorValueIR => {
        if (channel === "position") return bedrockPositionToCanonical(v, negateMolang).map((v, axis) => scalarIR(affineMolang(v, affineMolang(blend, 1 / 16, 0), position[axis])));
        if (channel === "rotation") return bedrockRotationToCanonical(v, negateMolang).map((v) => scalarIR(affineMolang(v, blend, 0)));
        return v.map((v) => scalarIR(affineMolang(v, blend, affineMolang(blend, -1, 1))));
      };
      const vector = (v: BedrockVector) => transform((Array.isArray(v) ? v : [v, v, v]).map(molangScalar) as MolangVector);
      let driver: DriverIR;
      if (c === null || typeof c !== "object" || Array.isArray(c)) driver = { type: "expression", value: vector(c as BedrockVector) };
      else {
        const entries = Object.entries(c).sort(([a], [b]) => Number(a) - Number(b));
        const key = (v: unknown): BedrockKeyframeValue | undefined => typeof v === "object" && v !== null && !Array.isArray(v) ? v as BedrockKeyframeValue : undefined;
        const pre = (v: unknown) => key(v) ? vector(key(v)!.pre ?? key(v)!.post!) : vector(v as BedrockVector);
        const post = (v: unknown) => key(v) ? vector(key(v)!.post ?? key(v)!.pre!) : vector(v as BedrockVector);
        const curve: CurveIR = { type: "curve", keys: entries.map(([time, v]) => ({ time: `${time}s`, pre: pre(v), post: post(v) })), segments: entries.slice(0, -1).map(([, v], index) => {
          const next = entries[index + 1][1];
          return key(v)?.lerp_mode === "catmullrom" || key(next)?.lerp_mode === "catmullrom"
            ? { interpolation: "catmull_rom", previous: post(entries[Math.max(0, index - 1)][1]), following: pre(entries[Math.min(entries.length - 1, index + 2)][1]) }
            : { interpolation: "linear" };
        }) };
        driver = curve;
      }
      ir.animation.tracks.push({ target: { node: id, operation: channel }, channel: "value", driver });
    }
  }
  for (const property of ["particle_effects", "sound_effects", "timeline"] as const) {
    const data = animation[property];
    if (!data || typeof data !== "object" || Array.isArray(data)) continue;
    for (const [timestamp, value] of Object.entries(data)) {
      const time = Number(timestamp);
      const sourcePath = `animations.${name}.${property}.${timestamp}`;
      for (const entry of Array.isArray(value) ? value : [value]) {
        if (property === "timeline") {
          const lines = typeof entry === "string" ? entry.split(/\r?\n/).map((line) => line.trim()).filter(Boolean) : [];
          const commands = lines.map((line) => line.replace(/^\//, "").trim()).filter(Boolean);
          if (commands.length) timeline.push({ time: sourceSecondsTime(time), source: { type: "player" }, origin: { type: "root" }, action: { type: "commands", commands } });
          if (!commands.length || lines.some((line) => !line.startsWith("/"))) diagnostics.push({ severity: "warning", code: commands.length ? "bedrock_instruction_approximated" : "bedrock_instruction_ignored", message: `${name} at ${time * 20}t: ${commands.length ? "uninterpreted instructions were kept as commands; behavior may differ. Review and edit them." : "an unsupported timeline entry was omitted."}`, sourcePath });
          continue;
        }
        const effect = typeof entry === "string" ? entry.trim() : isRecord(entry) && typeof entry.effect === "string" ? entry.effect.trim() : "";
        if (!effect) {
          diagnostics.push({ severity: "warning", code: "bedrock_effect_ignored", message: `${name} at ${time * 20}t: an effect without a resource identifier was omitted.`, sourcePath });
          continue;
        }
        const locator = isRecord(entry) && typeof entry.locator === "string" ? entry.locator : "";
        const node = locator ? Object.entries(nodes).find(([, node]) => node.source?.node_id === locator)?.[0] : undefined;
        timeline.push({ time: sourceSecondsTime(time), source: { type: property === "sound_effects" ? "player" : "server" }, origin: node ? { type: "node", node } : { type: "root" }, action: { type: "commands", commands: [property === "sound_effects" ? `playsound ${effect} master @s ~ ~ ~` : `particle ${effect} ~ ~ ~`] } });
        diagnostics.push({ severity: "warning", code: "bedrock_effect_approximated", message: `${name} at ${time * 20}t: a Bedrock effect was converted to a Java command; resource aliases and effect settings may differ. Review and edit the event.`, sourcePath });
        if (locator && !node) diagnostics.push({ severity: "warning", code: "bedrock_effect_origin_approximated", message: `${name} at ${time * 20}t: effect locator could not be resolved; the root position is used. Review and edit the event.`, sourcePath });
        if (isRecord(entry) && typeof entry.pre_effect_script === "string" && entry.pre_effect_script.trim()) diagnostics.push({ severity: "warning", code: "bedrock_particle_script_ignored", message: `${name} at ${time * 20}t: particle pre-effect script was omitted. Review and edit the event.`, sourcePath });
      }
    }
  }
  timeline.sort((first, second) => parseAnimationSeconds(first.time) - parseAnimationSeconds(second.time));
  return ir;
}

function bedrockAnimationDurationSeconds(animation: BedrockAnimation): number {
  let duration = animation.animation_length ?? 0;
  for (const bone of Object.values(animation.bones ?? {})) {
    for (const channel of [bone.position, bone.rotation, bone.scale]) {
      if (!isKeyframedChannel(channel)) continue;
      for (const timestamp of Object.keys(channel)) duration = Math.max(duration, Number(timestamp));
    }
  }
  return duration;
}

function bedrockAnimationUsesTime(animation: BedrockAnimation): boolean {
  return Object.values(animation.bones ?? {}).some((bone) => [bone.position, bone.rotation, bone.scale]
    .some((channel) => channelExpressions(channel).some((expression) => typeof expression === "string" && /(?:q|query)\.anim_time\b/i.test(expression))));
}

function channelExpressions(channel: BedrockChannel | undefined): BedrockExpression[] {
  if (channel === undefined) return [];
  if (!isKeyframedChannel(channel)) return Array.isArray(channel) ? channel : [channel];
  return Object.values(channel).flatMap((keyframe) => {
    if (!isKeyframeValue(keyframe)) return Array.isArray(keyframe) ? keyframe : [keyframe];
    return [keyframe.pre, keyframe.post].flatMap((vector) => vector === undefined ? [] : Array.isArray(vector) ? vector : [vector]);
  });
}

function isKeyframedChannel(channel: BedrockChannel | undefined): channel is Record<string, BedrockKeyframe> {
  return typeof channel === "object" && channel !== null && !Array.isArray(channel);
}

function isKeyframeValue(value: BedrockKeyframe): value is BedrockKeyframeValue {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
