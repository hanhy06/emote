import { orderedNodeIds, type AnimationEntryIR, type AnimationIR, type ClipIR, type ScalarIR, type TimeValueIR } from "./animationIR";
import { formatMinecraftTime, parseAnimationSeconds, parseMinecraftTime, sourceSecondsTime } from "../format/time";
import { evaluatePose } from "./animationIRPose";
export function scalarIR(value: number | string): ScalarIR {
  if (typeof value !== "string") return value as ScalarIR;
  const numeric = Number(value.trim());
  return Number.isFinite(numeric) ? numeric : { molang: value };
}

export function sourceDelayIR(value: number | string): TimeValueIR {
  const scalar = scalarIR(value);
  return typeof scalar === "number" ? formatMinecraftTime(parseMinecraftTime(sourceSecondsTime(scalar))) : scalar as TimeValueIR;
}

export function normalizeAnimationTimes(animation: AnimationIR | AnimationEntryIR, generatedTimes = false): void {
  const clip = "clip" in animation ? animation.clip : animation.animation;
  if (generatedTimes) clip.duration = formatMinecraftTime(parseMinecraftTime(clip.duration, 1));
  if (generatedTimes && clip.playback) {
    if (clip.playback.loop_start !== undefined) clip.playback.loop_start = formatMinecraftTime(parseMinecraftTime(clip.playback.loop_start));
    for (const field of ["start_delay", "loop_delay"] as const) {
      const value = clip.playback[field];
      if (typeof value === "string") clip.playback[field] = formatMinecraftTime(parseMinecraftTime(value));
    }
  }
  for (const track of clip.tracks) {
    if (track.driver.type === "state") {
      const keys = [...track.driver.keys].sort((first, second) => parseAnimationSeconds(first.time) - parseAnimationSeconds(second.time))
        .map((key) => ({ ...key, time: generatedTimes ? formatMinecraftTime(parseMinecraftTime(key.time)) : key.time }));
      track.driver.keys = track.channel === "visible" ? [...new Map(keys.map((key) => [parseMinecraftTime(key.time), key])).values()] : keys;
      continue;
    }
    if (track.driver.type !== "curve") continue;
    const curve = track.driver;
    const source = curve.keys.map((key, index) => ({ key, index, seconds: parseAnimationSeconds(key.time), tick: parseMinecraftTime(key.time) }))
      .sort((first, second) => first.seconds - second.seconds);
    const retained = [...new Map(source.map((entry) => [entry.tick, entry])).values()];
    const keys = retained.map(({ key, tick }) => ({ ...key, time: generatedTimes ? formatMinecraftTime(tick) : key.time }));
    curve.segments = retained.slice(0, -1).map((left, index) => {
      const segment = { ...curve.segments[left.index] };
      if (segment.interpolation === "catmull_rom") {
        const previous = retained[Math.max(0, index - 1)];
        const following = retained[Math.min(retained.length - 1, index + 2)];
        if (previous.index !== Math.max(0, left.index - 1)) segment.previous = previous.key.post ?? previous.key.value;
        if (following.index !== Math.min(source.length - 1, left.index + 2)) segment.following = following.key.pre ?? following.key.value;
      }
      return segment;
    });
    curve.keys = keys;
  }
  for (const events of Object.values(clip.events ?? {})) {
    events.sort((first, second) => parseAnimationSeconds(first.time ?? "0t") - parseAnimationSeconds(second.time ?? "0t"));
    if (generatedTimes) for (const event of events) if (event.time !== undefined) event.time = formatMinecraftTime(parseMinecraftTime(event.time));
  }
}

export function removeRedundantKeyframes(ir: AnimationIR): void {
  ir.animation.tracks = ir.animation.tracks.filter((track) => {
    if (track.channel !== "value" || track.driver.type !== "curve") return true;
    const curve = track.driver;
    if (!curve.keys.length || curve.segments.length !== curve.keys.length - 1
      || curve.keys.some((key) => !key.value || key.pre !== undefined || key.post !== undefined || key.value.some((axis) => typeof axis !== "number" || !Number.isFinite(axis)))
      || curve.segments.some((segment) => !["step", "linear"].includes(segment.interpolation)
        || segment.easing && segment.easing.kernel !== "linear"
        || segment.previous !== undefined || segment.following !== undefined || segment.out_tangent !== undefined
        || segment.in_tangent !== undefined || segment.handles !== undefined)) return true;
    const keys = curve.keys;
    const values = keys.map((key) => key.value as readonly number[]);
    const sameValue = (first: number, second: number) => values[first].length === values[second].length && values[first].every((axis, index) => axis === values[second][index]);
    const constant = values.every((_, index) => sameValue(0, index));
    if (constant) {
      const base = ir.nodes[track.target.node]?.transform?.find((operation) => operation.id === track.target.operation)?.value;
      if (base?.length === values[0].length && values[0].every((axis, index) => axis === base[index])) return false;
      curve.keys = [keys[0]];
      curve.segments = [];
      return true;
    }
    const ticks = keys.map((key) => parseMinecraftTime(key.time));
    if (ticks.some((tick, index) => index > 0 && tick <= ticks[index - 1])) return true;
    const retained = [0];
    for (let index = 1; index < keys.length - 1; index++) {
      const previous = retained[retained.length - 1];
      const left = curve.segments[previous], right = curve.segments[index];
      const progress = (ticks[index] - ticks[previous]) / (ticks[index + 1] - ticks[previous]);
      const redundant = left.interpolation === right.interpolation && (left.interpolation === "step"
        ? sameValue(previous, index)
        : values[index].length === values[previous].length && values[index].length === values[index + 1].length
          && values[index].every((axis, component) => axis === values[previous][component] + (values[index + 1][component] - values[previous][component]) * progress));
      if (!redundant) retained.push(index);
    }
    retained.push(keys.length - 1);
    while (retained.length > 1 && sameValue(retained[retained.length - 1], retained[retained.length - 2])) retained.pop();
    curve.keys = retained.map((index) => keys[index]);
    curve.segments = retained.slice(0, -1).map((index) => curve.segments[index]);
    return true;
  });
}

export function removeTinyStaticNodes(ir: AnimationIR, events: NonNullable<AnimationIR["animation"]["events"]>): AnimationIR {
  const staticNodes = new Set<string>();
  for (const id of orderedNodeIds(ir.nodes)) {
    const node = ir.nodes[id];
    if (node.parent && !staticNodes.has(node.parent)) continue;
    const constant = ir.animation.tracks.filter((t) => t.target.node === id && t.channel === "value").every((t) => {
      if (t.driver.type !== "curve") return false;
      const values = t.driver.keys.flatMap((key) => key.value ? [key.value] : [key.pre, key.post]);
      const first = values[0];
      if (!first || values.some((v) => v.some((axis, index) => typeof axis !== "number" || typeof first[index] !== "number" || Math.abs(axis - first[index]) > 1e-10))) return false;
      const base = node.transform?.find((op) => op.id === t.target.operation)?.value;
      return t.driver.before === "first_pre" || parseAnimationSeconds(t.driver.keys[0].time) === 0 || Boolean(base && first.every((axis, index) => typeof axis === "number" && Math.abs(axis - base[index]) <= 1e-10));
    });
    if (constant) staticNodes.add(id);
  }
  const poses = evaluatePose(ir, 0, (value) => typeof value === "number" ? value : 0);
  const candidates = new Set([...staticNodes].filter((id) => {
    if (!ir.nodes[id].attachments) return false;
    const matrix = poses[id].matrix.elements;
    return [0, 4, 8].every((offset) => Math.hypot(matrix[offset], matrix[offset + 1], matrix[offset + 2]) < 0.001);
  }));
  if (!candidates.size) return ir;
  const protectedNodes = new Set<string>();
  const protect = (id: string) => {
    for (let current: string | undefined = id; current && !protectedNodes.has(current); current = ir.nodes[current]?.parent) protectedNodes.add(current);
  };
  for (const [id, node] of Object.entries(ir.nodes)) if (node.attachments && !candidates.has(id)) protect(id);
  for (const phase of Object.values(events)) for (const event of phase ?? []) {
    if (event.source.type === "node") protect(event.source.node);
    if (event.origin.type === "node") protect(event.origin.node);
  }
  const removable = new Set<string>();
  for (const id of candidates) {
    for (let current: string | undefined = id; current && !protectedNodes.has(current); current = ir.nodes[current]?.parent) removable.add(current);
  }
  for (const id of orderedNodeIds(ir.nodes)) if (ir.nodes[id].parent && removable.has(ir.nodes[id].parent!)) removable.add(id);
  if (!removable.size || removable.size === Object.keys(ir.nodes).length) return ir;
  ir.nodes = Object.fromEntries(Object.entries(ir.nodes).filter(([id]) => !removable.has(id)));
  ir.animation.tracks = ir.animation.tracks.filter((track) => !removable.has(track.target.node));
  return ir;
}

export function remapClip(source: ClipIR, nodeId: (id: string) => string): ClipIR {
  const clip = structuredClone(source);
  for (const t of clip.tracks) t.target.node = nodeId(t.target.node);
  for (const phase of Object.values(clip.events ?? {})) for (const e of phase ?? []) {
    if (e.source.type === "node") e.source.node = nodeId(e.source.node);
    if (e.origin.type === "node") e.origin.node = nodeId(e.origin.node);
  }
  return clip;
}
