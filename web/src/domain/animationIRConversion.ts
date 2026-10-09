import { orderedNodeIdsIR, type AnimationIR, type ClipIR, type ScalarIR } from "./animationIR";
import { evaluatePoseIR } from "./animationIRPose";
export function scalarIR(value: number | string): ScalarIR {
  if (typeof value === "number") return value;
  const numeric = Number(value.trim());
  return Number.isFinite(numeric) ? numeric : { molang: value };
}

export function removeTinyStaticNodes(ir: AnimationIR, events: NonNullable<AnimationIR["animation"]["events"]>): AnimationIR {
  const staticNodes = new Set<string>();
  for (const id of orderedNodeIdsIR(ir.nodes)) {
    const node = ir.nodes[id];
    if (node.parent && !staticNodes.has(node.parent)) continue;
    const constant = ir.animation.tracks.filter((t) => t.target.node === id && t.channel === "value").every((t) => {
      if (t.driver.type !== "curve") return false;
      const values = t.driver.keys.flatMap((key) => key.value ? [key.value] : [key.pre, key.post]);
      const first = values[0];
      if (!first || values.some((v) => v.some((axis, index) => typeof axis !== "number" || typeof first[index] !== "number" || Math.abs(axis - first[index]) > 1e-10))) return false;
      const base = node.transform?.find((op) => op.id === t.target.operation)?.value;
      return t.driver.before === "first_pre" || t.driver.keys[0].time === 0 || Boolean(base && first.every((axis, index) => typeof axis === "number" && Math.abs(axis - base[index]) <= 1e-10));
    });
    if (constant) staticNodes.add(id);
  }
  const poses = evaluatePoseIR(ir, 0, (value) => typeof value === "number" ? value : 0);
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
  for (const id of orderedNodeIdsIR(ir.nodes)) if (ir.nodes[id].parent && removable.has(ir.nodes[id].parent!)) removable.add(id);
  if (!removable.size || removable.size === Object.keys(ir.nodes).length) return ir;
  ir.nodes = Object.fromEntries(Object.entries(ir.nodes).filter(([id]) => !removable.has(id)));
  ir.animation.tracks = ir.animation.tracks.filter((track) => !removable.has(track.target.node));
  return ir;
}

export function remapClipIR(source: ClipIR, nodeId: (id: string) => string): ClipIR {
  const clip = structuredClone(source);
  for (const t of clip.tracks) t.target.node = nodeId(t.target.node);
  for (const phase of Object.values(clip.events ?? {})) for (const e of phase ?? []) {
    if (e.source.type === "node") e.source.node = nodeId(e.source.node);
    if (e.origin.type === "node") e.origin.node = nodeId(e.origin.node);
  }
  return clip;
}
