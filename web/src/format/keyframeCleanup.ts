import type { EmoteAnimation, EmoteNodeTracks, EmoteVectorKeyframe, Vec3 } from "./emoteAnimation";
import { parseMinecraftTime } from "./time";

const TINY_STATIC_NODE_SCALE = 0.001;

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
  const candidates = new Set(Object.entries(animation.nodes).flatMap(([id, node]) => {
    const track = animation.timeline.tracks[id];
    const moves = Boolean(track?.position || track?.rotation || track?.scale);
    const tiny = node.transform.scale.every((value) => Math.abs(value) < TINY_STATIC_NODE_SCALE);
    return node.type !== "anchor" && tiny && !moves ? [id] : [];
  }));
  if (candidates.size === 0 || candidates.size === Object.keys(animation.nodes).length) return animation;

  for (const [id, node] of Object.entries(animation.nodes)) {
    if (candidates.has(id)) continue;
    let parentId = node.parent;
    while (parentId) {
      candidates.delete(parentId);
      parentId = animation.nodes[parentId]?.parent;
    }
  }
  for (const events of Object.values(animation.timeline.events ?? {})) {
    for (const event of events ?? []) {
      if (event.source.type === "node") candidates.delete(event.source.node);
      if (event.origin.type === "node") candidates.delete(event.origin.node);
    }
  }
  if (candidates.size === 0) return animation;

  const nodes = Object.fromEntries(Object.entries(animation.nodes).filter(([id]) => !candidates.has(id)));
  const tracks = Object.fromEntries(Object.entries(animation.timeline.tracks).filter(([id]) => !candidates.has(id)));
  return { ...animation, nodes, timeline: { ...animation.timeline, tracks } };
}

function cleanVectorFrames(frames: EmoteVectorKeyframe[], defaults: Vec3, allowLinearReduction: boolean): EmoteVectorKeyframe[] {
  // Preserve pre/post discontinuities and their incoming/outgoing interpolation boundaries.
  if (frames.some((frame) => !frame.value || frame.pre || frame.post)) return frames;
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
  if (result.length === 1 && result[0].value!.every((value, axis) => value === defaults[axis])) return [];
  return result;
}
