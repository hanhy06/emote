import type { EmoteAnimation, EmoteNodeTracks, EmoteVectorKeyframe, Vec3 } from "./emoteAnimation";
import { localTransformToMatrix } from "./localTransform";
import { multiplyMatrix16 } from "./matrix";
import { parseMinecraftTime } from "./time";

const TINY_STATIC_NODE_SCALE = 0.001;
// Matrix decomposition leaves floating-point noise on otherwise constant channels.
// Ten decimal places retain motion while eliminating matrix decomposition noise.
const TRANSFORM_PRECISION = 1e10;

export function removeRedundantKeyframes(animation: EmoteAnimation): EmoteAnimation {
  // Molang can observe key_frame_lerp_time left by another channel, or have side effects.
  const sourceTracks = Object.values(animation.timeline.tracks);
  const preserveKeyframes = animation.molang || sourceTracks.some((track) =>
    [track.position, track.rotation, track.scale].some((frames) => frames?.some((frame) =>
      [frame.value, frame.pre, frame.post].some((value) => value?.some((axis) => typeof axis === "string"))))
    || track.visible?.some((frame) => typeof frame.value === "string")
    || track.nbt?.some((frame) => typeof frame.value !== "string"));

  const tracks: Record<string, EmoteNodeTracks> = {};
  for (const [id, source] of Object.entries(animation.timeline.tracks)) {
    if (preserveKeyframes) {
      tracks[id] = source;
      continue;
    }
    const node = animation.nodes[id];
    const track = { ...source };
    for (const channel of ["position", "rotation", "scale"] as const) {
      const frames = source[channel];
      if (!frames) continue;
      const cleaned = cleanVectorFrames(frames, node.transform[channel], channel !== "rotation");
      if (cleaned.length) track[channel] = cleaned;
      else delete track[channel];
    }
    if (source.visible) {
      const visible = source.visible.filter((frame, index, frames) => index === 0 || frame.value !== frames[index - 1].value);
      if (visible.length === 1 && visible[0].value === (node.type === "anchor" ? true : node.visible ?? true)) delete track.visible;
      else track.visible = visible;
    }
    // NBT patches and timeline commands may intentionally reapply state changed externally.
    if (Object.keys(track).length) tracks[id] = track;
  }
  return removeTinyStaticNodes({ ...animation, timeline: { ...animation.timeline, tracks } });
}

function removeTinyStaticNodes(animation: EmoteAnimation): EmoteAnimation {
  const worldMatrices = new Map<string, ReturnType<typeof localTransformToMatrix> | null>();
  const visiting = new Set<string>();
  const staticWorldMatrix = (id: string): ReturnType<typeof localTransformToMatrix> | undefined => {
    const cached = worldMatrices.get(id);
    if (cached !== undefined) return cached ?? undefined;
    if (visiting.has(id)) return undefined;
    visiting.add(id);
    const node = animation.nodes[id];
    const track = animation.timeline.tracks[id];
    const position = staticChannelValue(track?.position, node.transform.position);
    const rotation = staticChannelValue(track?.rotation, node.transform.rotation);
    const scale = staticChannelValue(track?.scale, node.transform.scale);
    let result: ReturnType<typeof localTransformToMatrix> | undefined;
    if (position && rotation && scale) {
      const local = localTransformToMatrix({ position, rotation, scale }, `${id} static transform`);
      const parent = node.parent ? staticWorldMatrix(node.parent) : undefined;
      if (!node.parent || parent) result = parent ? multiplyMatrix16(parent, local, `${id} static world transform`) : local;
    }
    visiting.delete(id);
    worldMatrices.set(id, result ?? null);
    return result;
  };
  const candidates = new Set(Object.entries(animation.nodes).flatMap(([id, node]) => {
    if (node.type === "anchor") return [];
    const matrix = staticWorldMatrix(id);
    return matrix && matrixScaleIsTiny(matrix) ? [id] : [];
  }));
  if (candidates.size === 0 || candidates.size === Object.keys(animation.nodes).length) return animation;

  const protectedNodes = new Set<string>();
  const protectWithAncestors = (id: string): void => {
    for (let current: string | undefined = id; current && !protectedNodes.has(current); current = animation.nodes[current]?.parent) {
      protectedNodes.add(current);
    }
  };
  for (const [id, node] of Object.entries(animation.nodes)) if (node.type !== "anchor" && !candidates.has(id)) protectWithAncestors(id);
  for (const events of Object.values(animation.timeline.events ?? {})) {
    for (const event of events ?? []) {
      if (event.source.type === "node") protectWithAncestors(event.source.node);
      if (event.origin.type === "node") protectWithAncestors(event.origin.node);
    }
  }
  for (const id of protectedNodes) candidates.delete(id);
  if (candidates.size === 0) return animation;

  const removable = new Set(candidates);
  for (const id of candidates) {
    for (let parent = animation.nodes[id]?.parent; parent && !protectedNodes.has(parent); parent = animation.nodes[parent]?.parent) {
      removable.add(parent);
    }
  }
  let removableCount: number;
  do {
    removableCount = removable.size;
    for (const [id, node] of Object.entries(animation.nodes)) {
      if (node.parent && removable.has(node.parent)) removable.add(id);
    }
  } while (removable.size !== removableCount);
  if (removable.size === Object.keys(animation.nodes).length) return animation;

  const nodes = Object.fromEntries(Object.entries(animation.nodes).filter(([id]) => !removable.has(id)));
  const tracks = Object.fromEntries(Object.entries(animation.timeline.tracks).filter(([id]) => !removable.has(id)));
  return { ...animation, nodes, timeline: { ...animation.timeline, tracks } };
}

function staticChannelValue(frames: EmoteVectorKeyframe[] | undefined, fallback: Vec3): Vec3 | undefined {
  if (!frames?.length) return fallback;
  const values = frames.flatMap((frame) => [frame.value, frame.pre, frame.post].filter((value): value is NonNullable<typeof value> => value !== undefined));
  if (values.length === 0 || values.some((value) => value.some((axis) => typeof axis !== "number"))) return undefined;
  const constant = values[0] as Vec3;
  if (values.some((value) => value.some((axis, index) => axis !== constant[index]))) return undefined;
  if (parseMinecraftTime(frames[0].time) > 0 && fallback.some((axis, index) => axis !== constant[index])) return undefined;
  return constant;
}

function matrixScaleIsTiny(matrix: readonly number[]): boolean {
  return [
    Math.hypot(matrix[0], matrix[4], matrix[8]),
    Math.hypot(matrix[1], matrix[5], matrix[9]),
    Math.hypot(matrix[2], matrix[6], matrix[10]),
  ].every((value) => value < TINY_STATIC_NODE_SCALE);
}

function cleanVectorFrames(frames: EmoteVectorKeyframe[], defaults: Vec3, allowLinearReduction: boolean): EmoteVectorKeyframe[] {
  // Preserve pre/post discontinuities and their incoming/outgoing interpolation boundaries.
  if (frames.some((frame) => !frame.value || frame.pre || frame.post)) return frames;
  frames = frames.map((frame) => ({ ...frame, value: (frame.value as Vec3).map((axis) =>
    Number(axis.toFixed(10))) as unknown as Vec3 }));
  const result: EmoteVectorKeyframe[] = [];
  for (const frame of frames) {
    result.push(frame);
    while (result.length >= 3) {
      const [first, middle, last] = result.slice(-3);
      const a = first.value as Vec3;
      const b = middle.value as Vec3;
      const c = last.value as Vec3;
      const same = a.every((value, axis) => value === b[axis]);
      const constant = same && b.every((value, axis) => value === c[axis]);
      const repeatedStep = same && first.interpolation === "step" && middle.interpolation === "step";
      const linear = allowLinearReduction && (first.interpolation ?? "linear") === "linear"
        && (middle.interpolation ?? "linear") === "linear"
        && (first.easing ?? "linear") === "linear" && (middle.easing ?? "linear") === "linear";
      const progress = (parseMinecraftTime(middle.time) - parseMinecraftTime(first.time))
        / (parseMinecraftTime(last.time) - parseMinecraftTime(first.time));
      if (!constant && !repeatedStep && !(linear && a.every((value, axis) => b[axis] === value + (c[axis] - value) * progress))) break;
      result.splice(result.length - 2, 1);
    }
  }
  while (result.length > 1 && result.at(-1)!.value!.every((value, axis) => value === result.at(-2)!.value![axis])) result.pop();
  const last = result.at(-1);
  if (last) {
    const { interpolation: _interpolation, easing: _easing, ...terminal } = last;
    result[result.length - 1] = terminal;
  }
  if (result.length === 1 && result[0].value!.every((value, axis) => typeof value === "number" && Math.abs(value - defaults[axis]) <= 1 / TRANSFORM_PRECISION)) return [];
  return result.map((frame) => {
    const { interpolation, easing, ...value } = frame;
    return { ...value, ...(interpolation && interpolation !== "linear" ? { interpolation } : {}), ...(easing && easing !== "linear" ? { easing } : {}) };
  });
}
