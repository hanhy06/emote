import type { AnimationIR, CurveIR, CurveKeyIR, DriverIR, NodeIR, ScalarIR, SegmentIR, TrackIR } from "../../domain/animationIR";
import { blockbenchEasingIR } from "../../domain/animationIRCurves";
import { scalarIR } from "../../domain/animationIRConversion";
import type { ImportedNode, ImportDiagnostic } from "../../domain/conversionSeed";
import { sanitizeResourcePath } from "../../format/resourceLocation";
import { affineMolang } from "../common/molangVector";
import { importedNodeIR } from "../common/blockbenchAnimationIR";
import { rewriteMolangIdentifiers } from "../../format/molang/sourceTransformer";
import type { PalAnimation, PalExpression, PalKeyframe } from "./emotecraftBinary";
import { createEmotecraftNodes, createEmotecraftSlices, EMOTECRAFT_PIVOTS, EMOTECRAFT_PLAYER_PARTS, EMOTECRAFT_RENDER_SCALE } from "./emotecraftPlayerRig";

export function createEmotecraftAnimationIR(animation: PalAnimation, name: string, diagnostics: ImportDiagnostic[]): { ir: AnimationIR; imported: Record<string, ImportedNode> } {
  const duration = Math.max(0.05, animation.lengthTicks / 20);
  if (!Number.isFinite(duration) || duration > 600) throw new Error(`${name} duration must be within (0, 600] seconds.`);
  const nodes: Record<string, NodeIR> = { scene: { transform: [{ id: "scale", op: "scale", value: [EMOTECRAFT_RENDER_SCALE, EMOTECRAFT_RENDER_SCALE, EMOTECRAFT_RENDER_SCALE] }] } };
  const tracks: TrackIR[] = [];
  const slices = createEmotecraftSlices(new Set(EMOTECRAFT_PLAYER_PARTS.filter((part) => animation.bones[part.bone]?.bend.length).map((part) => part.bone)));
  const names = new Set(["body", ...EMOTECRAFT_PLAYER_PARTS.map((part) => part.bone), ...Object.keys(animation.bones), ...Object.keys(animation.pivots), ...Object.keys(animation.parents), ...Object.values(animation.parents)]);
  const usedIds = new Set(["scene", "inherited_torso_bend", ...slices.map((slice) => slice.id)]);
  const boneIds = new Map<string, string>();
  for (const name of names) {
    const base = `pal_${sanitizeResourcePath(name, "bone").replaceAll("/", "_")}`;
    let id = base;
    for (let suffix = 2; usedIds.has(id) || usedIds.has(`${id}_lower`); suffix++) id = `${base}_${suffix}`;
    usedIds.add(id); usedIds.add(`${id}_lower`); boneIds.set(name, id);
  }
  const addChannel = (id: string, operation: string, frames: PalKeyframe[], axis: number, type: "position" | "rotation" | "scale") => {
    if (!frames.length) return;
    const fallback = type === "scale" ? 1 : 0;
    const factor = type === "position" ? (axis === 0 ? -1 : 1) / 16 : type === "rotation" ? axis === 2 ? 1 : -1 : 1;
    const driver = channelDriver(frames, animation, fallback, type === "rotation");
    const vector = (value: ScalarIR) => [0, 1, 2].map((component) => component === axis ? scalarIR(affineMolang(typeof value === "number" ? value : value.molang, factor, 0)) : fallback);
    if (driver.type === "expression") driver.value = vector((driver.value as ScalarIR[])[0]);
    else if (driver.type === "curve") {
      for (const key of driver.keys) {
        if (key.value) key.value = vector(key.value[0]);
        else { key.pre = vector(key.pre[0]); key.post = vector(key.post[0]); }
      }
      for (const segment of driver.segments) {
        if (segment.previous) segment.previous = vector(segment.previous[0]);
        if (segment.following) segment.following = vector(segment.following[0]);
        if (segment.handles) segment.handles = [0, 1, 2].map((component) => component === axis
          ? { out: { ...segment.handles![0].out, value: scalarIR(affineMolang(typeof segment.handles![0].out.value === "number" ? segment.handles![0].out.value : segment.handles![0].out.value.molang, factor, 0)) },
              in: { ...segment.handles![0].in, value: scalarIR(affineMolang(typeof segment.handles![0].in.value === "number" ? segment.handles![0].in.value : segment.handles![0].in.value.molang, factor, 0)) } }
          : { out: { time: 0, value: fallback }, in: { time: 1, value: fallback } });
      }
    }
    tracks.push({ target: { node: id, operation }, channel: "value", driver });
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
    for (const type of ["position", "rotation", "scale"] as const) for (const axis of type === "rotation" ? [2, 1, 0] : [0, 1, 2]) {
      if (!bone?.[type][axis].length) continue;
      const operation = `${type}_${"xyz"[axis]}`;
      nodes[id].transform!.push({ id: operation, op: type === "position" ? "translate" : type === "rotation" ? "rotate_euler" : "scale", ...(type === "rotation" ? { order: "ZYX" as const } : {}), value: type === "scale" ? [1, 1, 1] : [0, 0, 0] });
      addChannel(id, operation, bone[type][axis], axis, type);
    }
    for (const type of ["position", "rotation", "scale"] as const) {
      const channels = bone?.[type];
      if (!channels?.every((frames) => frames.length)) continue;
      const timing = (frames: PalKeyframe[]) => JSON.stringify(frames.map((frame) => [frame.startTick, frame.endTick, frame.easing,
        frame.easing === "catmullrom" ? [] : frame.easing === "bezier" ? [frame.easingArgs[1], frame.easingArgs[3]] : frame.easingArgs]));
      if (channels.some((frames) => timing(frames) !== timing(channels[0]))) continue;
      const axisTracks = [0, 1, 2].map((axis) => tracks.find((track) => track.target.node === id && track.target.operation === `${type}_${"xyz"[axis]}`)!);
      const drivers = axisTracks.map((track) => track.driver);
      if (drivers.every((driver) => driver.type === "expression")) {
        tracks.push({ target: { node: id, operation: type }, channel: "value", driver: { type: "expression", value: drivers.map((driver, axis) => (driver.value as ScalarIR[])[axis]) } });
      } else if (drivers.every((driver) => driver.type === "curve")) {
        const curves = drivers as CurveIR[];
        const vector = (index: number, field: "value" | "pre" | "post") => curves.map((curve, axis) => curve.keys[index][field]![axis]);
        tracks.push({ target: { node: id, operation: type }, channel: "value", driver: { ...curves[0],
          keys: curves[0].keys.map((key, index) => key.value ? { time: key.time, value: vector(index, "value") } : { time: key.time, pre: vector(index, "pre"), post: vector(index, "post") }),
          segments: curves[0].segments.map((segment, index) => ({ ...segment,
            ...(segment.previous ? { previous: curves.map((curve, axis) => curve.segments[index].previous![axis]) } : {}),
            ...(segment.following ? { following: curves.map((curve, axis) => curve.segments[index].following![axis]) } : {}),
            ...(segment.handles ? { handles: curves.map((curve, axis) => curve.segments[index].handles![axis]) } : {}),
          })),
        } });
      } else continue;
      for (const track of axisTracks) tracks.splice(tracks.indexOf(track), 1);
      const operations = nodes[id].transform!;
      const first = operations.findIndex((operation) => operation.id.startsWith(`${type}_`));
      operations.splice(first, 3, { id: type, op: type === "position" ? "translate" : type === "rotation" ? "rotate_euler" : "scale", ...(type === "rotation" ? { order: "ZYX" as const } : {}), value: type === "scale" ? [1, 1, 1] : [0, 0, 0] });
    }
    if (bone?.bend.length) {
      const bend = channelExpression(bone.bend, animation, 0, true);
      nodes[`${id}_lower`] = { parent: id, source: { node_id: name, joint: "lower" }, transform: [
        { id: "joint", op: "translate", value: [0, -6 / 16, 0] }, { id: "bend", op: "rotate_euler", order: "XYZ", value: [0, 0, 0] },
      ] };
      tracks.push({ target: { node: `${id}_lower`, operation: "joint" }, channel: "value", driver: { type: "expression", value: [0, { molang: `return -${6 / 16} + ${2 / 16} * (1 - math.cos(${bend}));` }, { molang: `return ${2 / 16} * math.sin(${bend});` }] } });
      const driver = channelDriver(bone.bend, animation, 0, true);
      const vector = (v: ScalarIR) => [scalarIR(affineMolang(typeof v === "number" ? v : v.molang, -1, 0)), 0, 0];
      if (driver.type === "expression") driver.value = vector((driver.value as ScalarIR[])[0]);
      else if (driver.type === "curve") for (const key of driver.keys) {
        if (key.value) key.value = vector(key.value[0]); else { key.pre = vector(key.pre[0]); key.post = vector(key.post[0]); }
      }
      if (driver.type === "curve" && driver.segments.some((segment) => segment.previous || segment.handles)) {
        tracks.push({ target: { node: `${id}_lower`, operation: "bend" }, channel: "value", driver: { type: "expression", value: [{ molang: `return -(${bend});` }, 0, 0] } });
      } else tracks.push({ target: { node: `${id}_lower`, operation: "bend" }, channel: "value", driver });
    }
  }
  const torso = animation.bones.torso?.bend;
  if (animation.applyBendToOtherBones && torso?.length) {
    const bend = channelExpression(torso, animation, 0, true);
    nodes.inherited_torso_bend = { parent: boneIds.get("body")!, transform: [
      { id: "pivot", op: "translate", value: [0, 6 / 16, 0] }, { id: "bend", op: "rotate_euler", order: "XYZ", value: [0, 0, 0] }, { id: "unpivot", op: "translate", value: [0, -6 / 16, 0] },
    ] };
    tracks.push({ target: { node: "inherited_torso_bend", operation: "bend" }, channel: "value", driver: { type: "expression", value: [{ molang: `return -(${bend});` }, 0, 0] } });
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
    for (const name of ["head", "left_arm", "right_arm"]) nodes[boneIds.get(name)!].parent = inheritParent(animation.parents[name] ?? "body");
  }
  const imported = createEmotecraftNodes(slices);
  for (const slice of slices) nodes[slice.id] = { ...importedNodeIR(imported[slice.id], `${boneIds.get(slice.source.bone)!}${slice.lower ? "_lower" : ""}`), source: { editor_node_id: slice.id, node_id: slice.source.bone } };
  for (const name of names) if (!EMOTECRAFT_PLAYER_PARTS.some((part) => part.bone === name) && name !== "body") {
    const id = boneIds.get(name)!;
    diagnostics.push({ severity: "warning", code: "emotecraft_logical_bone", message: `${name} is preserved as a logical node; geometry is unavailable.`, sourcePath: `bones.${name}` });
    imported[id] = { type: "anchor", binding: { sourceNodeId: name } };
  }
  const ir: AnimationIR = { id: `emote:${sanitizeResourcePath(name)}`, metadata: { name, description: `${name} emote.` }, nodes,
    animation: { duration, playback: { mode: animation.loop === "loop_from_tick" ? "loop" : animation.loop, loop_start: animation.loop === "loop_from_tick" ? animation.loopStartTick / 20 : 0 }, tracks },
    source: { uuid: animation.uuid, format: animation.format, begin_tick: animation.beginTick, end_tick: animation.endTick, effects: structuredClone(animation.effects) } };
  return { ir, imported };
}

function scalar(value: PalExpression, angular: boolean): ScalarIR {
  return scalarIR(typeof value === "number" && angular ? value * 180 / Math.PI : value);
}

function channelDriver(frames: readonly PalKeyframe[], animation: PalAnimation, fallback: number, angular: boolean): DriverIR {
  if (animation.beginTick || animation.endTick !== undefined || frames.some((frame) => [frame.start, frame.end].some((value) => typeof value === "string" && /(?:q|query|global)\.key_frame_lerp_time/i.test(value)) || frame.easingArgs.some((args) => args.some((value) => typeof value === "string")) || /expo/.test(frame.easing) || frame.endTick > animation.lengthTicks)) {
    return { type: "expression", value: [{ molang: `return ${channelExpression(frames, animation, fallback, angular)};` }] };
  }
  const keys: CurveKeyIR[] = [];
  const segments: SegmentIR[] = [];
  const put = (tick: number, value: ScalarIR, segment?: SegmentIR) => {
    const time = tick / 20;
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
    put(frame.startTick, start);
    if (frame.endTick === frame.startTick) { put(frame.endTick, end); continue; }
    let segment: SegmentIR;
    if (frame.easing === "constant") segment = { interpolation: "step" };
    else if (frame.easing === "catmullrom") segment = { interpolation: "catmull_rom", previous: [scalar(frame.easingArgs[0]?.[0] ?? frame.start, angular)], following: [scalar(frame.easingArgs[1]?.[0] ?? frame.end, angular)] };
    else if (frame.easing === "bezier") {
      const duration = (frame.endTick - frame.startTick) / 20;
      segment = { interpolation: "bezier", handles: [{ out: { time: Math.max(0, Math.min(1, Number(frame.easingArgs[3]?.[0] ?? 0.1) / duration)), value: scalarIR(affineMolang(typeof start === "number" ? start : start.molang, 1, typeof scalar(frame.easingArgs[2]?.[0] ?? 0, angular) === "number" ? scalar(frame.easingArgs[2]?.[0] ?? 0, angular) as number : 0)) },
        in: { time: Math.max(0, Math.min(1, 1 + Number(frame.easingArgs[1]?.[0] ?? 0) / duration)), value: scalarIR(affineMolang(typeof end === "number" ? end : end.molang, 1, scalar(frame.easingArgs[0]?.[0] ?? 0, angular) as number)) } }] };
    } else segment = { interpolation: "linear", easing: blockbenchEasingIR(frame.easing, frame.easingArgs.map((args) => Number(args[0] ?? 0))) };
    put(frame.endTick, end, segment);
  }
  return { type: "curve", before: "first_pre", keys, segments } as CurveIR;
}

function channelExpression(frames: readonly PalKeyframe[], animation: PalAnimation, fallback: number, angular: boolean): string {
  const expression = (raw: PalExpression, p: string, angle = angular): string => {
    if (typeof raw === "number") return String(angle ? raw * 180 / Math.PI : raw);
    const rewritten = rewriteMolangIdentifiers(raw, (id) => /^(?:q|query|global)\.key_frame_lerp_time$/i.test(id) ? p : undefined);
    return /[;{}]|\breturn\b/i.test(raw) ? `({${rewritten};})` : `(${rewritten})`;
  };
  let result = String(fallback);
  for (let index = frames.length - 1; index >= 0; index--) {
    const frame = frames[index];
    const p = frame.endTick > frame.startTick ? `math.clamp((q.anim_time * 20 - ${frame.startTick}) / ${frame.endTick - frame.startTick}, 0, 1)` : "1";
    const a = expression(frame.start, p), b = expression(frame.end, p);
    const argument = (index: number, defaultValue: number, angle = false) => expression(frame.easingArgs[index]?.[0] ?? defaultValue, p, angle);
    let value: string;
    if (frame.easing === "constant") value = `${p} >= 1 ? ${b} : ${a}`;
    else if (frame.easing === "catmullrom") {
      const previous = frame.easingArgs[0]?.[0] === undefined ? a : argument(0, 0, angular);
      const following = frame.easingArgs[1]?.[0] === undefined ? b : argument(1, 0, angular);
      value = `0.5 * (2 * ${a} + (${b} - ${previous}) * ${p} + (2 * ${previous} - 5 * ${a} + 4 * ${b} - ${following}) * math.pow(${p}, 2) + (3 * ${a} - ${previous} - 3 * ${b} + ${following}) * math.pow(${p}, 3))`;
    } else if (frame.easing === "bezier") {
      const length = Math.max((frame.endTick - frame.startTick) / 20, Number.EPSILON);
      const rightTime = `math.clamp((${argument(3, 0.1)}) / ${length}, 0, 1)`;
      const leftTime = `math.clamp(1 + (${argument(1, 0)}) / ${length}, 0, 1)`;
      const t = "t.emote_bezier_t";
      value = `{t.emote_bezier_right = ${rightTime}; t.emote_bezier_left = ${leftTime}; t.emote_bezier_start = ${a}; t.emote_bezier_end = ${b}; t.emote_bezier_out = ${argument(2, 0, angular)}; t.emote_bezier_in = ${argument(0, 0, angular)}; t.emote_bezier_progress = ${p}; t.emote_bezier_low = 0; t.emote_bezier_high = 1; loop(48, {${t} = (t.emote_bezier_low + t.emote_bezier_high) / 2; t.emote_bezier_x = 3 * math.pow(1 - ${t}, 2) * ${t} * t.emote_bezier_right + 3 * (1 - ${t}) * math.pow(${t}, 2) * t.emote_bezier_left + math.pow(${t}, 3); t.emote_bezier_low = t.emote_bezier_x < t.emote_bezier_progress ? ${t} : t.emote_bezier_low; t.emote_bezier_high = t.emote_bezier_x < t.emote_bezier_progress ? t.emote_bezier_high : ${t}; }); ${t} = (t.emote_bezier_low + t.emote_bezier_high) / 2; math.pow(1 - ${t}, 3) * t.emote_bezier_start + 3 * math.pow(1 - ${t}, 2) * ${t} * (t.emote_bezier_start + t.emote_bezier_out) + 3 * (1 - ${t}) * math.pow(${t}, 2) * (t.emote_bezier_end + t.emote_bezier_in) + math.pow(${t}, 3) * t.emote_bezier_end;}`;
    } else {
      const eased = easingExpression(frame.easing, p, argument(0, frame.easing === "step" ? 5 : frame.easing.includes("bounce") ? 0.5 : 1));
      value = `${a} + (${b} - ${a}) * (${eased})`;
    }
    result = index === frames.length - 1 ? `(${value})` : `(q.anim_time * 20 < ${frame.endTick} ? (${value}) : ${result})`;
  }
  if (animation.beginTick && animation.beginTick > 0) {
    const blend = `(1 - math.cos(180 * math.clamp(q.anim_time * 20 / ${animation.beginTick}, 0, 1))) / 2`;
    result = `(${fallback} + (${result} - ${fallback}) * (${blend}))`;
  }
  if (animation.endTick !== undefined && animation.lengthTicks > animation.endTick) {
    const blend = `(1 - math.cos(180 * math.clamp((q.anim_time * 20 - ${animation.endTick}) / ${animation.lengthTicks - animation.endTick}, 0, 1))) / 2`;
    result = `(${result} * (1 - (${blend})) + ${fallback} * (${blend}))`;
  }
  return result;
}

function easingExpression(name: string, p: string, argument: string): string {
  if (name === "linear") return p;
  if (name === "step") { const count = `math.max(2, math.floor(${argument}))`; return `math.floor(${p} * (${count}) + 0.000000001) / (${count})`; }
  const match = /^ease(inout|in|out)(sine|quad|cubic|quart|quint|expo|circ|back|elastic|bounce)$/.exec(name);
  if (!match) throw new Error(`Unsupported Emotecraft easing ${name}.`);
  const kernel = (x: string) => {
    const exponent = ({ quad: 2, cubic: 3, quart: 4, quint: 5 } as Record<string, number>)[match[2]];
    if (exponent) return `math.pow(${x}, ${exponent})`;
    if (match[2] === "sine") return `1 - math.cos(90 * (${x}))`;
    if (match[2] === "expo") return `math.pow(2, 10 * ((${x}) - 1))`;
    if (match[2] === "circ") return `1 - math.sqrt(1 - math.pow(${x}, 2))`;
    if (match[2] === "back") { const k = `(${argument}) * 1.70158`; return `math.pow(${x}, 2) * (((${k}) + 1) * (${x}) - (${k}))`; }
    if (match[2] === "elastic") return `1 - math.pow(math.cos(90 * (${x})), 3) * math.cos(180 * (${x}) * (${argument}))`;
    const b = argument;
    return `math.min(math.min(7.5625 * math.pow(${x}, 2), 30.25 * (${b}) * math.pow((${x}) - 6/11, 2) + 1 - (${b})), math.min(121 * math.pow(${b}, 2) * math.pow((${x}) - 9/11, 2) + 1 - math.pow(${b}, 2), 484 * math.pow(${b}, 3) * math.pow((${x}) - 10.5/11, 2) + 1 - math.pow(${b}, 3)))`;
  };
  if (match[1] === "out") return `1 - (${kernel(`1 - (${p})`)})`;
  if (match[1] === "inout") return `${p} < 0.5 ? (${kernel(`2 * (${p})`)}) / 2 : 1 - (${kernel(`2 - 2 * (${p})`)}) / 2`;
  return kernel(p);
}
