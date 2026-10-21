import { orderedNodeIds, type AnimationIR, type NbtPatchIR, type TrackIR } from "../domain/animationIR";
import { parseMinecraftTime } from "../format/time";
import { evaluatePose } from "../domain/animationIRPose";

export function removeRedundantKeyframes(ir: AnimationIR): void {
  ir.animation.tracks = ir.animation.tracks.filter((track) => {
    if (track.channel !== "value" || track.driver.type !== "curve" || track.blend) return true;
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

export function removeTinyStaticNodes(ir: AnimationIR): void {
  const nodeIds = orderedNodeIds(ir.nodes);
  const constantValues = new Map<TrackIR, readonly number[]>();
  for (const track of ir.animation.tracks) {
    if (track.channel !== "value" || track.driver.type !== "curve" || track.blend
      || track.driver.segments.length !== track.driver.keys.length - 1
      || track.driver.segments.some((segment) => !["step", "linear"].includes(segment.interpolation)
        || segment.easing && segment.easing.kernel !== "linear"
        || segment.previous !== undefined || segment.following !== undefined || segment.out_tangent !== undefined
        || segment.in_tangent !== undefined || segment.handles !== undefined)) continue;
    const values = track.driver.keys.flatMap((key) => key.value ? [key.value] : [key.pre, key.post]);
    const first = values[0];
    if (!first || values.some((value) => value.length !== first.length || value.some((axis, index) => typeof axis !== "number" || !Number.isFinite(axis) || axis !== first[index]))) continue;
    const base = ir.nodes[track.target.node]?.transform?.find((operation) => operation.id === track.target.operation)?.value;
    if (track.driver.before === "first_pre" || parseMinecraftTime(track.driver.keys[0].time) === 0
      || base && first.every((axis, index) => axis === base[index])) constantValues.set(track, first as readonly number[]);
  }
  const staticNodes = new Set<string>();
  const zeroScaleNodes = new Set<string>();
  for (const id of nodeIds) {
    const node = ir.nodes[id];
    const tracks = ir.animation.tracks.filter((track) => track.target.node === id);
    if (node.parent && zeroScaleNodes.has(node.parent) && node.inherit?.scale !== false
      || node.transform?.some((operation) => {
        if (operation.op !== "scale") return false;
        const track = tracks.filter((track) => track.channel === "value" && track.target.operation === operation.id).at(-1);
        const value = track ? constantValues.get(track) : operation.value;
        return value?.length === 3 && value.every((axis) => axis === 0);
      })) zeroScaleNodes.add(id);
    if (node.parent && !staticNodes.has(node.parent)) continue;
    if (tracks.every((track) => track.channel === "value" ? constantValues.has(track)
      : track.driver.type === "state" && track.driver.keys.every((key) => track.channel === "visible"
        ? typeof key.value === "boolean" : typeof (key.value as NbtPatchIR).merge === "string"))) staticNodes.add(id);
  }
  const poses = evaluatePose({ ...ir, nodes: Object.fromEntries(Object.entries(ir.nodes).filter(([id]) => staticNodes.has(id))),
    animation: { ...ir.animation, tracks: ir.animation.tracks.filter((track) => track.channel === "value" && staticNodes.has(track.target.node)) } }, 0, (value) => {
    if (typeof value !== "number") throw new Error("Static node transform requires a constant value.");
    return value;
  });
  const candidates = new Set([...staticNodes, ...zeroScaleNodes].filter((id) => {
    if (!ir.nodes[id].attachments) return false;
    if (zeroScaleNodes.has(id)) return true;
    const matrix = poses[id].matrix.elements;
    return [0, 4, 8].every((offset) => Math.hypot(matrix[offset], matrix[offset + 1], matrix[offset + 2]) < 0.001);
  }));
  if (!candidates.size) return;
  const protectedNodes = new Set<string>();
  const protect = (id: string) => {
    for (let current: string | undefined = id; current && !protectedNodes.has(current); current = ir.nodes[current]?.parent) protectedNodes.add(current);
  };
  for (const [id, node] of Object.entries(ir.nodes)) if (!staticNodes.has(id) && !zeroScaleNodes.has(id) || node.attachments && !candidates.has(id)) protect(id);
  for (const phase of Object.values(ir.animation.events ?? {})) for (const event of phase ?? []) {
    if (event.source.type === "node") protect(event.source.node);
    if (event.origin.type === "node") protect(event.origin.node);
  }
  const removable = new Set<string>();
  for (const id of candidates) {
    for (let current: string | undefined = id; current && !protectedNodes.has(current); current = ir.nodes[current]?.parent) removable.add(current);
  }
  for (const id of nodeIds) if (ir.nodes[id].parent && removable.has(ir.nodes[id].parent!)) removable.add(id);
  if (!removable.size || removable.size === nodeIds.length) return;
  ir.nodes = Object.fromEntries(Object.entries(ir.nodes).filter(([id]) => !removable.has(id)));
  ir.animation.tracks = ir.animation.tracks.filter((track) => !removable.has(track.target.node));
}

