import type { AnimationIR, CurveIR, CurveKeyIR, NodeIR, ScalarIR, SegmentIR, SharedValueIR, TrackIR } from "../../domain/animationIR";
import { blockbenchEasingIR, evaluateEasing } from "../../domain/animationIRCurves";
import { affineCurveIR, affineScalarIR, scalarIR } from "../../domain/animationIRConversion";
import type { ImportedNode } from "../../domain/conversionSeed";
import type { ConversionIssue } from "../../foundation/diagnostics";
import { sanitizeResourcePath } from "../../format/resourceLocation";
import { MAX_ANIMATION_DURATION_TICKS, parseMinecraftTime, sourceSecondsTime } from "../../format/time";
import { importedNodeIR } from "../common/blockbenchAnimationIR";
import { rewriteMolangIdentifiers } from "../../format/molang/sourceTransformer";
import type { PalAnimation, PalExpression, PalKeyframe } from "./emotecraftBinary";
import { createEmotecraftNodes, createEmotecraftSlices, EMOTECRAFT_PIVOTS, EMOTECRAFT_PLAYER_PARTS, EMOTECRAFT_RENDER_SCALE } from "./emotecraftPlayerRig";

export function createEmotecraftAnimationIR(animation: PalAnimation, name: string, diagnostics: ConversionIssue[]): { ir: AnimationIR; imported: Record<string, ImportedNode> } {
  const duration = Math.max(0.05, animation.lengthTicks / 20);
  if (parseMinecraftTime(sourceSecondsTime(duration), 1) > MAX_ANIMATION_DURATION_TICKS) throw new Error(`${name} duration must not exceed ${MAX_ANIMATION_DURATION_TICKS} ticks.`);
  const nodes: Record<string, NodeIR> = { scene: { transform: [{ id: "scale", op: "scale", value: [EMOTECRAFT_RENDER_SCALE, EMOTECRAFT_RENDER_SCALE, EMOTECRAFT_RENDER_SCALE] }] } };
  const tracks: TrackIR[] = [];
  const values: Record<string, SharedValueIR> = {};
  const slices = createEmotecraftSlices(new Set(EMOTECRAFT_PLAYER_PARTS.filter((part) => animation.bones[part.bone]?.bend.length).map((part) => part.bone)));
  const names = new Set([...Object.keys(animation.bones), "body", ...EMOTECRAFT_PLAYER_PARTS.map((part) => part.bone), ...Object.keys(animation.pivots), ...Object.keys(animation.parents), ...Object.values(animation.parents)]);
  const usedIds = new Set(["scene", "inherited_torso_bend", ...slices.map((slice) => slice.id)]);
  const boneIds = new Map<string, string>();
  for (const name of names) {
    const base = `pal_${sanitizeResourcePath(name, "bone").replaceAll("/", "_")}`;
    let id = base;
    for (let suffix = 2; usedIds.has(id) || usedIds.has(`${id}_lower`); suffix++) id = `${base}_${suffix}`;
    usedIds.add(id); usedIds.add(`${id}_lower`); boneIds.set(name, id);
  }
  const channelValue = (id: string, frames: readonly PalKeyframe[], fallback: number, angular: boolean, factor: number): ScalarIR => {
    if (!frames.length) return fallback;
    const curve = affineCurveIR(channelDriver(frames, angular), [factor], [0]);
    const first = frames[0], last = frames.at(-1)!;
    const loop = animation.format === "player_animator" && animation.loop !== "once";
    const returnTick = animation.loop === "loop_from_tick" ? animation.loopStartTick : 0;
    const returned = frames.find((frame) => frame.endTick > returnTick) ?? last;
    const begin = animation.beginTick !== undefined && animation.beginTick > 0;
    const end = animation.loop === "once" && animation.endTick !== undefined && animation.lengthTicks > animation.endTick;
    if (!loop && !begin && !end) {
      values[id] = { ...curve, base: [fallback] };
      return { molang: `q.value('${id}', 0)` };
    }
    values[`${id}_sample`] = { ...curve, base: [fallback] };
    let sample = `q.value('${id}_sample', 0)`;
    if (loop) {
      const loopLength = animation.lengthTicks - returnTick;
      const startTick = Math.min(last.endTick, animation.lengthTicks);
      const easing = animation.easeBeforeKeyframe ? returned : last;
      const seam = affineCurveIR(channelDriver([{ startTick, endTick: returned.endTick + loopLength,
        start: last.end, end: returned.end, easing: easing.easing, easingArgs: easing.easingArgs }], angular), [factor], [0]);
      values[`${id}_loop_tail`] = { ...seam, base: [fallback] };
      values[`${id}_loop_start`] = { ...structuredClone(seam), base: [fallback], time_offset: `${loopLength}t` };
      sample = `(q.loop_count > 0 && q.anim_time_ticks >= ${returned.startTick} && q.anim_time_ticks < ${returned.endTick} ? q.value('${id}_loop_start', 0) : q.anim_time_ticks >= ${last.endTick} ? q.value('${id}_loop_tail', 0) : ${sample})`;
    }
    let result = sample;
    if (end) {
      const easing = animation.easeBeforeKeyframe || last.easing === "bezier" || last.easing === "catmullrom" ? "easeinoutsine" : last.easing;
      values[`${id}_fade_out`] = { ...channelDriver([{ startTick: animation.endTick!, endTick: animation.lengthTicks,
        start: 1, end: 0, easing, easingArgs: [] }], false), base: [1] };
      const finalWeight = easing === "constant" ? 1 : 1 - evaluateEasing(blockbenchEasingIR(easing, [easing === "step" ? 2 : /bounce$/.test(easing) ? 0.5 : 1]), 1);
      const weight = `(q.anim_time_ticks >= ${animation.lengthTicks} ? ${finalWeight} : q.value('${id}_fade_out', 0))`;
      result = `(q.anim_time_ticks >= ${animation.endTick} ? ${fallback} + (${sample} - ${fallback}) * ${weight} : ${sample})`;
    }
    if (begin) {
      let incoming = sample;
      const length = first.endTick - first.startTick;
      if (length > 0) {
        const progress = `(math.clamp((q.anim_time_ticks - ${first.startTick}) / ${length}, 0, 1))`;
        values[`${id}_begin_start`] = { type: "expression", base: [fallback], value: [affineScalarIR(scalar(first.start, angular, false, progress), factor)] };
        values[`${id}_begin_target`] = { type: "expression", base: [fallback], value: [affineScalarIR(scalar(first.end, angular, false, progress), factor)] };
        values[`${id}_fade_in`] = { ...channelDriver([{ startTick: 0, endTick: length, start: 0, end: 1, easing: "easeinoutsine", easingArgs: [] }], false), base: [1] };
        values[`${id}_begin`] = { type: "expression", base: [fallback], value: [{ molang:
          `q.value('${id}_begin_start', 0); return ${fallback} + (q.value('${id}_begin_target', 0) - ${fallback}) * q.value('${id}_fade_in', 0);` }] };
        const active = `q.anim_time_ticks >= ${first.startTick} && q.anim_time_ticks < ${first.endTick}${loop && returned === first ? " && q.loop_count == 0" : ""}`;
        incoming = `(${active} ? q.value('${id}_begin', 0) : ${sample})`;
      }
      result = `(q.anim_time_ticks < ${animation.beginTick} ? ${incoming} : ${result})`;
    }
    values[id] = { type: "expression", base: [fallback], value: [{ molang: `return ${result};` }] };
    return { molang: `q.value('${id}', 0)` };
  };
  for (const name of names) {
    const id = boneIds.get(name)!;
    const parent = name === "body" ? undefined : animation.parents[name] ?? "body";
    const pivot = animation.pivots[name] ?? EMOTECRAFT_PIVOTS[name] ?? [0, 0, 0];
    const parentPivot = parent ? animation.pivots[parent] ?? EMOTECRAFT_PIVOTS[parent] ?? [0, 0, 0] : [0, 0, 0];
    const bone = animation.bones[name];
    nodes[id] = { name, source: { node_id: name, ...(bone ? { channels: structuredClone(bone) } : {}) }, parent: parent ? boneIds.get(parent)! : "scene", transform: [
      { id: "pivot", op: "translate", value: pivot.map((value, axis) => (value - parentPivot[axis]) / 16) },
    ] };
    for (const type of ["position", "rotation", "scale"] as const) {
      if (!bone?.[type].some((frames) => frames.length)) continue;
      nodes[id].transform!.push({ id: type, op: type === "position" ? "translate" : type === "rotation" ? "rotate_euler" : "scale", ...(type === "rotation" ? { order: "ZYX" as const } : {}), value: type === "scale" ? [1, 1, 1] : [0, 0, 0] });
    }
    for (const type of ["rotation", "position", "scale"] as const) {
      if (!bone?.[type].some((frames) => frames.length)) continue;
      const fallback = type === "scale" ? 1 : 0;
      const vector = bone[type].map((frames, axis) => {
        const factor = type === "position" ? (axis === 0 ? -1 : 1) / 16 : type === "rotation" && axis !== 2 ? -1 : 1;
        return channelValue(`${id}_${type}_${"xyz"[axis]}`, frames, fallback, type === "rotation", factor);
      });
      tracks.push({ target: { node: id, operation: type }, channel: "value", driver: { type: "expression", value: vector } });
    }
    if (bone?.bend.length) {
      const bend = channelValue(`${id}_bend`, bone.bend, 0, true, -1) as { molang: string };
      nodes[`${id}_lower`] = { parent: id, source: { node_id: name, joint: "lower" }, transform: [
        { id: "joint", op: "translate", value: [0, -6 / 16, 0] }, { id: "bend", op: "rotate_euler", order: "XYZ", value: [0, 0, 0] },
      ] };
      tracks.push({ target: { node: `${id}_lower`, operation: "joint" }, channel: "value", driver: { type: "expression", value: [0, { molang: `return -${6 / 16} + ${2 / 16} * (1 - math.cos(${bend.molang}));` }, { molang: `return -${2 / 16} * math.sin(${bend.molang});` }] } });
      tracks.push({ target: { node: `${id}_lower`, operation: "bend" }, channel: "value", driver: { type: "expression", value: [bend, 0, 0] } });
    }
  }
  const torso = animation.bones.torso?.bend;
  if (animation.applyBendToOtherBones && torso?.length) {
    const bend: ScalarIR = { molang: `q.value('${boneIds.get("torso")!}_bend', 0)` };
    nodes.inherited_torso_bend = { parent: boneIds.get("body")!, transform: [
      { id: "pivot", op: "translate", value: [0, 6 / 16, 0] }, { id: "bend", op: "rotate_euler", order: "XYZ", value: [0, 0, 0] }, { id: "unpivot", op: "translate", value: [0, -6 / 16, 0] },
    ] };
    tracks.push({ target: { node: "inherited_torso_bend", operation: "bend" }, channel: "value", driver: { type: "expression", value: [bend, 0, 0] } });
    const inherited = new Map<string, string>();
    const inheritParent = (name: string): string => {
      if (name === "body") return "inherited_torso_bend";
      if (inherited.has(name)) return inherited.get(name)!;
      const original = boneIds.get(name)!;
      let id = `${original}_inherited`;
      for (let suffix = 2; usedIds.has(id); suffix++) id = `${original}_inherited_${suffix}`;
      usedIds.add(id); inherited.set(name, id);
      nodes[id] = { ...structuredClone(nodes[original]), parent: inheritParent(animation.parents[name] ?? "body") };
      tracks.push(...tracks.filter((track) => track.target.node === original).map((track) => ({ ...structuredClone(track), target: { ...track.target, node: id } })));
      return id;
    };
    for (const name of ["head", "left_arm", "right_arm", "cape"]) {
      const id = boneIds.get(name);
      if (id) nodes[id].parent = inheritParent(animation.parents[name] ?? "body");
    }
  }
  const imported = createEmotecraftNodes(slices);
  for (const slice of slices) nodes[slice.id] = { ...importedNodeIR(imported[slice.id], `${boneIds.get(slice.source.bone)!}${slice.lower ? "_lower" : ""}`), source: { editor_node_id: slice.id, node_id: slice.source.bone } };
  for (const name of names) if (!EMOTECRAFT_PLAYER_PARTS.some((part) => part.bone === name) && name !== "body") {
    const id = boneIds.get(name)!;
    diagnostics.push({ severity: "warning", code: "emotecraft_logical_bone", message: `${name} is preserved as a logical node; geometry is unavailable.`, sourcePath: `bones.${name}` });
    imported[id] = { type: "anchor", binding: { sourceNodeId: name } };
  }
  const ir: AnimationIR = { id: `emote:${sanitizeResourcePath(name)}`, metadata: { name, description: `${name} emote.` }, nodes,
    animation: { duration: `${duration}s`, playback: { mode: animation.loop === "loop_from_tick" ? "loop" : animation.loop, loop_start: `${animation.loop === "loop_from_tick" ? animation.loopStartTick : 0}t` }, tracks, ...(Object.keys(values).length ? { values } : {}) },
    source: { uuid: animation.uuid, format: animation.format, begin_tick: animation.beginTick, end_tick: animation.endTick, effects: structuredClone(animation.effects) } };
  return { ir, imported };
}

function scalar(value: PalExpression, angular: boolean, control = false, progress = "q.key_frame_lerp_time"): ScalarIR {
  const raw = scalarIR(typeof value === "string" ? rewriteMolangIdentifiers(value, (id) => /^global\.key_frame_lerp_time$/i.test(id) || progress !== "q.key_frame_lerp_time" && /^(?:q|query)\.key_frame_lerp_time$/i.test(id) ? progress : undefined) : value);
  return angular && (typeof raw === "number" || control) ? affineScalarIR(raw, 180 / Math.PI) : raw;
}

function channelDriver(frames: readonly PalKeyframe[], angular: boolean): CurveIR {
  const keys: CurveKeyIR[] = [];
  const segments: SegmentIR[] = [];
  const put = (tick: number, value: ScalarIR, segment?: SegmentIR) => {
    const time = `${tick}t`;
    if (keys.at(-1)?.time === time) {
      const previous = keys.at(-1)!;
      keys[keys.length - 1] = { time, pre: previous.pre ?? previous.value!, post: [value] };
    } else {
      if (keys.length) segments.push(segment ?? { interpolation: "step" });
      keys.push({ time, value: [value] });
    }
  };
  for (const frame of frames) {
    const start = scalar(frame.start, angular), end = scalar(frame.end, angular);
    const argument = (index: number): PalExpression | undefined => frame.easingArgs[index]?.length ? frame.easingArgs[index].join(";") : undefined;
    put(frame.startTick, start);
    if (frame.endTick === frame.startTick) { put(frame.endTick, end); continue; }
    let segment: SegmentIR;
    if (frame.easing === "constant") segment = { interpolation: "step" };
    else if (frame.easing === "catmullrom" && frame.easingArgs.length >= 2) segment = { interpolation: "catmull_rom", previous: [scalar(argument(0) ?? 0, angular, true)], following: [scalar(argument(1) ?? 0, angular, true)] };
    else if (frame.easing === "bezier" && frame.easingArgs.length >= 2) {
      const duration = (frame.endTick - frame.startTick) / 20;
      const outTime = frame.easingArgs.length > 3 ? argument(3) ?? 0.1 : 0.1;
      const outValue = frame.easingArgs.length > 3 ? argument(2) ?? 0 : 0;
      segment = { interpolation: "bezier", control_order: "in_out", handles: [{
        out: { time: affineScalarIR(scalar(outTime, false), 1 / duration), value: scalar(outValue, angular, true), relative: true, clamp: "rescale_value" },
        in: { time: affineScalarIR(scalar(argument(1) ?? 0, false), 1 / duration, 1), value: scalar(argument(0) ?? 0, angular, true), relative: true, clamp: "rescale_value" },
      }] };
    } else if (frame.easing === "catmullrom" || frame.easing === "bezier") segment = { interpolation: "linear" };
    else segment = { interpolation: "linear", easing: blockbenchEasingIR(frame.easing, [argument(0) ?? (frame.easing === "step" ? 2 : /bounce$/.test(frame.easing) ? 0.5 : 1)]) };
    put(frame.endTick, end, segment);
  }
  return { type: "curve", before: "first_pre", at_key: "segment", evaluation: "vector", keys, segments };
}
