import type { AnimationEntryIR, AnimationIR, ClipIR, CurveIR, ScalarIR, TimeValueIR, VectorValueIR } from "./animationIR";
import { formatMinecraftTime, parseAnimationSeconds, parseMinecraftTime, sourceSecondsTime } from "../format/time";
import { mapMolangResult } from "../format/molang/sourceTransformer";
export function scalarIR(value: number | string): ScalarIR {
  if (typeof value !== "string") return value;
  const numeric = Number(value.trim());
  return Number.isFinite(numeric) ? numeric : { molang: value };
}

export function affineScalarIR(value: ScalarIR, factor: number, offset = 0): ScalarIR {
  if (typeof value === "number") return value * factor + offset;
  if (factor === 1 && offset === 0) return value;
  return { molang: mapMolangResult(value.molang, (result) => {
    const scaled = factor === 1 ? `(${result})` : `((${result}) * ${factor})`;
    return offset === 0 ? scaled : `(${scaled} + ${offset})`;
  }) };
}

export function affineCurveIR(curve: CurveIR, factors: readonly number[], offsets: readonly number[]): CurveIR {
  const vector = (values: VectorValueIR, relative = false) => values.map((value, axis) => affineScalarIR(value, factors[axis], relative ? 0 : offsets[axis]));
  for (const key of curve.keys) {
    if (key.value) key.value = vector(key.value);
    else { key.pre = vector(key.pre); key.post = vector(key.post); }
  }
  for (const segment of curve.segments) {
    if (segment.previous) segment.previous = vector(segment.previous);
    if (segment.following) segment.following = vector(segment.following);
    if (segment.out_tangent) segment.out_tangent = vector(segment.out_tangent, true);
    if (segment.in_tangent) segment.in_tangent = vector(segment.in_tangent, true);
    for (const [axis, handle] of (segment.handles ?? []).entries()) for (const control of [handle.out, handle.in]) {
      control.value = affineScalarIR(control.value, factors[axis], control.relative ? 0 : offsets[axis]);
    }
  }
  return curve;
}

export function sourceDelayIR(value: number | string): TimeValueIR {
  const scalar = scalarIR(value);
  return typeof scalar === "number" ? formatMinecraftTime(parseMinecraftTime(sourceSecondsTime(scalar))) : scalar;
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
  for (const track of [...clip.tracks, ...Object.values(clip.values ?? {}).map((driver) => ({ channel: "value" as const, driver }))]) {
    if (track.driver.type === "state") {
      track.driver.keys.sort((first, second) => parseAnimationSeconds(first.time) - parseAnimationSeconds(second.time));
      if (generatedTimes) for (const key of track.driver.keys) key.time = formatMinecraftTime(parseMinecraftTime(key.time));
      if (track.channel === "visible") track.driver.keys = [...new Map(track.driver.keys.map((key) => [parseMinecraftTime(key.time), key])).values()];
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

export function remapClip(source: ClipIR, nodeId: (id: string) => string): ClipIR {
  const clip = structuredClone(source);
  for (const t of clip.tracks) t.target.node = nodeId(t.target.node);
  for (const phase of Object.values(clip.events ?? {})) for (const e of phase ?? []) {
    if (e.source.type === "node") e.source.node = nodeId(e.source.node);
    if (e.origin.type === "node") e.origin.node = nodeId(e.origin.node);
  }
  return clip;
}
