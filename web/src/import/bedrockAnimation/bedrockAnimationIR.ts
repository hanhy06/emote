import { sourceSecondsTime, parseAnimationSeconds, parseMinecraftTime, MAX_ANIMATION_DURATION_TICKS } from "../../format/time";
import type { AnimationIR, CurveIR, ValueDriverIR, NodeIR, ScalarIR, TimelineEventIR, VectorValueIR, Vector3IR } from "../../domain/animationIR";
import { affineCurveIR, affineScalarIR, scalarIR, sourceDelayIR } from "../../domain/animationIRConversion";
import type { ImportedNode } from "../../domain/conversionSeed";
import { ConversionError, type ConversionIssue } from "../../foundation/diagnostics";
import { scanMolangSource } from "../../format/molang/sourceScanner";
import { importedNodeIR } from "../common/blockbenchAnimationIR";
import type { BedrockAnimation, BedrockBoneAnimation, BedrockChannel, BedrockExpression, BedrockKeyframe, BedrockKeyframeValue, BedrockVector } from "./bedrockAnimationSchema";
import { BEDROCK_PLAYER_BONES, BEDROCK_PLAYER_RENDER_SCALE, BEDROCK_PLAYER_SLICES, resolveBedrockPlayerBone } from "./bedrockPlayerRig";
import { bedrockPositionToCanonical } from "./coordinateSpace";
import { isRecord } from "../../format/runtimeValue";

export function createBedrockAnimationIR(animation: BedrockAnimation, name: string, imported: Record<string, ImportedNode>, unknownBoneIds: Record<string, string>, diagnostics: ConversionIssue[]): AnimationIR {
  const sourceDuration = bedrockAnimationDurationSeconds(animation);
  if (animation.animation_length === undefined && sourceDuration === 0 && bedrockAnimationUsesTime(animation)) {
    throw new ConversionError("bedrock_animation_duration_missing", `${name}: a time-dependent animation without key or event timing requires animation_length.`, `animations.${name}.animation_length`);
  }
  const duration = sourceSecondsTime(animation.animation_length !== undefined && typeof animation.animation_length !== "number" ? animation.animation_length : Math.max(0.05, sourceDuration));
  const durationTicks = parseMinecraftTime(duration, 1);
  if (durationTicks > MAX_ANIMATION_DURATION_TICKS) throw new ConversionError("animation_duration_unsupported", `${name} duration must not exceed ${MAX_ANIMATION_DURATION_TICKS} ticks.`, `animations.${name}.animation_length`);
  const nodes: Record<string, NodeIR> = { scene: { transform: [{ id: "scale", op: "scale", value: [BEDROCK_PLAYER_RENDER_SCALE, BEDROCK_PLAYER_RENDER_SCALE, BEDROCK_PLAYER_RENDER_SCALE] }] } };
  const timeline: TimelineEventIR[] = [];
  const ir: AnimationIR = {
    id: "emote:imported", metadata: { name, description: `${name} emote.` }, nodes,
    animation: {
      duration,
      ...(animation.anim_time_update !== undefined ? { clock: { type: "molang", expression: String(animation.anim_time_update) } as const } : {}),
      playback: { mode: animation.loop === true ? "loop" : animation.loop === "hold_on_last_frame" ? "hold" : "once",
        start_delay: sourceDelayIR(animation.start_delay ?? 0), loop_delay: sourceDelayIR(animation.loop_delay ?? 0) },
      tracks: [], events: { timeline },
    },
  };
  const unknown = Object.entries(unknownBoneIds).map(([sourceName, id]) => ({ id, sourceName, parent: undefined, pivot: [0, 0, 0] as const }));
  const sources = new Map<string, BedrockBoneAnimation>();
  for (const [sourceName, source] of Object.entries(animation.bones ?? {})) {
    const id = resolveBedrockPlayerBone(sourceName)?.id ?? unknownBoneIds[sourceName];
    if (!id) continue;
    const merged = sources.get(id);
    if (!merged) { sources.set(id, { ...source }); continue; }
    for (const channel of ["position", "rotation", "scale"] as const) {
      if (source[channel] === undefined) continue;
      if (merged[channel] !== undefined) throw new ConversionError("bedrock_bone_alias_conflict", `${name}: bone aliases for ${id} define ${channel} more than once.`, `animations.${name}.bones.${sourceName}.${channel}`);
      merged[channel] = source[channel];
    }
    if (source.relative_to !== undefined) {
      if (merged.relative_to !== undefined && JSON.stringify(merged.relative_to) !== JSON.stringify(source.relative_to)) {
        throw new ConversionError("bedrock_bone_alias_conflict", `${name}: bone aliases for ${id} have conflicting relative_to settings.`, `animations.${name}.bones.${sourceName}.relative_to`);
      }
      merged.relative_to = source.relative_to;
    }
  }
  let weight: ScalarIR = scalarIR(animation.blend_weight ?? 1);
  if (typeof weight !== "number") {
    ir.animation.values = { blend: { type: "expression", base: [1], value: [weight] } };
    weight = { molang: "q.value('blend', 0)" };
  }
  for (const bone of [...BEDROCK_PLAYER_BONES, ...unknown]) {
    const id = `bone:${bone.id}`;
    const parentPivot = BEDROCK_PLAYER_BONES.find((p) => p.id === bone.parent)?.pivot ?? [0, 0, 0];
    const position = bedrockPositionToCanonical(bone.pivot.map((v, axis) => v - parentPivot[axis]), (v) => -v).map((v) => v / 16) as Vector3IR;
    const source = sources.get(bone.id);
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
      const base: Vector3IR = channel === "position" ? position : channel === "rotation" ? [0, 0, 0] : [1, 1, 1];
      const factors = channel === "position" ? [-1 / 16, 1 / 16, 1 / 16] : channel === "rotation" ? [-1, -1, 1] : [1, 1, 1];
      const offsets = channel === "position" ? position : [0, 0, 0];
      const vector = (v: BedrockVector): VectorValueIR => (Array.isArray(v) && v.length !== 1 ? v : Array(3).fill(Array.isArray(v) ? v[0] : v)).map(scalarIR);
      let driver: ValueDriverIR;
      if (c === null || typeof c !== "object" || Array.isArray(c)) driver = { type: "expression", value: vector(c as BedrockVector).map((value, axis) => affineScalarIR(value, factors[axis], offsets[axis])) };
      else {
        const entries = Object.entries(c).sort(([a], [b]) => Number(a) - Number(b));
        const key = (v: unknown): BedrockKeyframeValue | undefined => typeof v === "object" && v !== null && !Array.isArray(v) ? v as BedrockKeyframeValue : undefined;
        const pre = (v: unknown) => key(v) ? vector(key(v)!.pre ?? key(v)!.post!) : vector(v as BedrockVector);
        const post = (v: unknown) => key(v) ? vector(key(v)!.post ?? key(v)!.pre!) : vector(v as BedrockVector);
        const curve: CurveIR = { type: "curve", before: "base", at_key: "post", evaluation: "vector", keys: entries.map(([time, v]) => ({ time: `${time}s`, pre: pre(v), post: post(v) })), segments: entries.slice(0, -1).map(([, v], index) => {
          const next = entries[index + 1][1];
          return key(v)?.lerp_mode === "catmullrom" || key(next)?.lerp_mode === "catmullrom"
            ? { interpolation: "catmull_rom", ...(index > 0 ? { previous: post(entries[index - 1][1]) } : {}),
              ...(index + 2 < entries.length ? { following: pre(entries[index + 2][1]) } : {}) }
            : { interpolation: "linear" };
        }) };
        driver = affineCurveIR(curve, factors, offsets);
      }
      ir.animation.tracks.push({ target: { node: id, operation: channel }, channel: "value", driver, ...(weight === 1 ? {} : { blend: { base, weight } }) });
    }
  }
  for (const property of ["particle_effects", "sound_effects", "timeline"] as const) {
    const data = animation[property];
    if (!data || typeof data !== "object" || Array.isArray(data)) continue;
    for (const [timestamp, value] of Object.entries(data)) {
      const time = Number(timestamp);
      const sourcePath = `animations.${name}.${property}.${timestamp}`;
      if (time < 0 || parseMinecraftTime(sourceSecondsTime(time)) > durationTicks) {
        diagnostics.push({ severity: "warning", code: "bedrock_event_outside_duration", message: `${name} at ${time * 20}t: an event outside the animation duration was omitted.`, sourcePath });
        continue;
      }
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
  if (animation.animation_length !== undefined) return parseAnimationSeconds(sourceSecondsTime(animation.animation_length));
  let duration = 0;
  for (const bone of Object.values(animation.bones ?? {})) {
    for (const channel of [bone.position, bone.rotation, bone.scale]) {
      if (!isKeyframedChannel(channel)) continue;
      for (const timestamp of Object.keys(channel)) duration = Math.max(duration, Number(timestamp));
    }
  }
  for (const events of [animation.particle_effects, animation.sound_effects, animation.timeline]) {
    if (!isRecord(events)) continue;
    for (const timestamp of Object.keys(events)) duration = Math.max(duration, Number(timestamp));
  }
  return duration;
}

function bedrockAnimationUsesTime(animation: BedrockAnimation): boolean {
  const expressions = [animation.blend_weight, animation.anim_time_update, ...Object.values(animation.bones ?? {})
    .flatMap((bone) => [bone.position, bone.rotation, bone.scale].flatMap(channelExpressions))];
  return expressions.some((expression) => typeof expression === "string" && scanMolangSource(expression)
    .some((token) => token.kind === "identifier" && /^(?:q|query)\.(?:anim_time|anim_time_ticks|life_time|loop_count)$/i.test(token.value)));
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
