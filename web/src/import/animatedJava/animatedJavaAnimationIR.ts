import { sourceSecondsTime } from "../../format/time";
import type { AnimationIR, NodeIR } from "../../domain/animationIR";
import { sourceDelayIR } from "../../domain/animationIRConversion";
import type { ImportedNode } from "../../domain/conversionSeed";
import type { DisplayNbtPatch } from "../../domain/minecraftData";
import { writeDisplayNbt } from "../../format/minecraftData";
import { minecraftVersionProfile } from "../../format/minecraftVersionProfiles";
import { importedNodeIR, blockbenchCurveIR } from "../common/blockbenchAnimationIR";
import { affineMolang, molangScalar, type MolangVector } from "../common/molangVector";
import { initialDisplayNbt } from "../common/runtimeOutput";
import type { AjProject, AjProjectAnimation, ProjectTransformGraph } from "./animatedJavaProjectSchema";
import type { AnimatedJavaCubes } from "./animatedJavaCubes";
import { matrix4ToRowMajor } from "../../format/matrix";
import { isRecord } from "../../format/runtimeValue";

export interface ProjectNodeStateFrame {
  nodeId: string;
  time: number;
  visible?: boolean | string;
  nbt?: DisplayNbtPatch;
}

export function createAnimatedJavaAnimationIR(source: AjProjectAnimation, project: AjProject, hierarchy: ProjectTransformGraph, imported: Record<string, ImportedNode>, scale: number, states: readonly ProjectNodeStateFrame[], cubes?: AnimatedJavaCubes): AnimationIR {
  const playbackMode = source.loop === "hold_on_last_frame" ? "hold" : source.loop;
  const ir: AnimationIR = {
    id: "emote:imported", metadata: { name: source.name, description: `${source.name} emote.` },
    nodes: { scene: { transform: [{ id: "scale", op: "scale", value: [scale, scale, scale] }] } },
    animation: { duration: sourceSecondsTime(typeof source.length === "number" ? Math.max(0.05, source.length, ...Object.values(source.animators).flatMap((a) => (a.keyframes ?? []).map((f) => f.time))) : source.length), tracks: [] },
  };
  ir.animation.playback = { mode: playbackMode as "once" | "hold" | "loop", start_delay: sourceDelayIR(source.start_delay || 0), loop_delay: sourceDelayIR(source.loop_delay || 0) };
  const weight = molangScalar(source.blend_weight || 1);
  const groupWeight = source.blend_weight === undefined || source.blend_weight === "" ? 1 : molangScalar(source.blend_weight);
  for (const [uuid, group] of hierarchy.groups) {
    const id = `group:${uuid}`;
    const parent = hierarchy.groupParents.get(uuid);
    const origin = parent ? hierarchy.groups.get(parent)?.origin ?? [0, 0, 0] : [0, 0, 0];
    const position = group.origin.map((v, axis) => (v - origin[axis]) / 16);
    ir.nodes[id] = { name: group.name, parent: parent ? `group:${parent}` : "scene",
      source: { node_id: uuid },
      transform: [ { id: "position", op: "translate", value: position },
        { id: "rotation", op: "rotate_euler", order: "ZYX", value: group.rotation }, { id: "scale", op: "scale", value: [1, 1, 1] } ],
    };
    for (const channel of ["position", "rotation", "scale"] as const) {
      const frames = source.animators[uuid]?.keyframes?.filter((frame) => frame.channel === channel) ?? [];
      const transform = (values: MolangVector): MolangVector => values.map((value, axis) => {
        if (channel === "position") return affineMolang(value, affineMolang(groupWeight, 1 / 16, 0), position[axis]);
        if (channel === "rotation") return affineMolang(value, groupWeight, group.rotation[axis]);
        return affineMolang(value, groupWeight, affineMolang(groupWeight, -1, 1));
      }) as MolangVector;
      const driver = blockbenchCurveIR(frames, transform);
      if (driver) ir.animation.tracks.push({ target: { node: id, operation: channel }, channel: "value", driver });
    }
  }
  for (const bone of cubes?.bones ?? []) for (const entry of bone.nodes) {
    const node = imported[entry.id];
    if (!node) continue;
    ir.nodes[entry.id] = { ...importedNodeIR(node, `group:${bone.uuid}`), name: entry.locatorName ?? entry.id,
      source: { editor_node_id: entry.id }, ...(entry.ignoreInheritedScale ? { inherit: { scale: false } } : {}),
      transform: [{ id: "content", op: "matrix", value: matrix4ToRowMajor(entry.localMatrix, `IR ${entry.id}`) }],
    };
  }
  for (const element of project.elements) {
    if (element.type === "cube" || element.type === "locator") continue;
    const parentUuid = hierarchy.elementParents.get(element.uuid);
    const parent = parentUuid ? `group:${parentUuid}` : "scene";
    const parentOrigin = parentUuid ? hierarchy.groups.get(parentUuid)?.origin ?? [0, 0, 0] : [0, 0, 0];
    const raw = element as unknown as Record<string, unknown>;
    const position = (Array.isArray(raw.position) ? raw.position : Array.isArray(raw.origin) ? raw.origin : [0, 0, 0]) as number[];
    const rotation = (Array.isArray(raw.rotation) ? raw.rotation : [0, 0, 0]) as number[];
    const size = (Array.isArray(raw.scale) ? raw.scale : [1, 1, 1]) as number[];
    const n = imported[element.uuid];
    const node: NodeIR = n ? importedNodeIR(n, parent) : { parent };
    if (!n) {
      const configs = isRecord(raw.configs) ? raw.configs : undefined;
      const config = { ...(isRecord(raw.config) ? raw.config : {}), ...(isRecord(configs?.default) ? configs.default : {}) };
      const groupConfig = project.groups.find((group) => group.uuid === parentUuid)?.configs?.default;
      node.visible = raw.visibility !== false && config.invisible !== true && groupConfig?.invisible !== true;
    }
    node.name = element.name; node.source = { node_id: element.uuid, editor_node_id: element.uuid, original_type: element.type };
    if (raw.ignore_inherited_scale) node.inherit = { scale: false };
    const basePosition = position.map((v, axis) => (v - parentOrigin[axis]) / 16);
    node.transform = [ { id: "position", op: "translate", value: basePosition },
      { id: "rotation", op: "rotate_euler", order: "XYZ", value: rotation }, { id: "scale", op: "scale", value: size } ];
    if (n?.type === "text_display") node.transform.push({ id: "text_orientation", op: "rotate_euler", order: "XYZ", value: [0, 180, 0] });
    ir.nodes[element.uuid] = node;
    for (const channel of ["position", "rotation", "scale"] as const) {
      const frames = source.animators[element.uuid]?.keyframes?.filter((f) => f.channel === channel) ?? [];
      const transform = (v: MolangVector): MolangVector => v.map((v, axis) => {
        if (channel === "position") return affineMolang(v, affineMolang(weight, (axis === 0 ? -1 : 1) / 16, 0), basePosition[axis]);
        if (channel === "rotation") return affineMolang(v, affineMolang(weight, axis === 2 ? 1 : -1, 0), rotation[axis]);
        return affineMolang(affineMolang(v, weight, affineMolang(weight, -1, 1)), !n || n.type === "item_display" ? 1 : size[axis], 0);
      }) as MolangVector;
      const driver = blockbenchCurveIR(frames, transform);
      if (driver) ir.animation.tracks.push({ target: { node: element.uuid, operation: channel }, channel: "value", driver });
    }
  }
  const statesByNode = new Map<string, ProjectNodeStateFrame[]>();
  for (const frame of states) {
    const frames = statesByNode.get(frame.nodeId) ?? [];
    frames.push(frame);
    statesByNode.set(frame.nodeId, frames);
  }
  const profile = minecraftVersionProfile("26.3");
  for (const [id, frames] of statesByNode) {
    const node = ir.nodes[id];
    if (!node) continue;
    const visible = [...new Map(frames.flatMap((frame) => frame.visible === undefined ? [] : [[frame.time, {
      time: frame.time, value: typeof frame.visible === "boolean" ? frame.visible : { molang: frame.visible },
    }] as const])).values()].sort((a, b) => a.time - b.time);
    if (visible.length) {
      if (visible[0].time !== 0) visible.unshift({ time: 0, value: node.visible ?? true });
      ir.animation.tracks.push({ target: { node: id }, channel: "visible", driver: { type: "state", keys: visible.map((frame) => ({ ...frame, time: sourceSecondsTime(frame.time) })) } });
    }
    const nbt = frames.flatMap((frame) => frame.nbt ? [{ time: frame.time, value: frame.nbt }] : []).sort((a, b) => a.time - b.time);
    if (nbt.length && node.attachments?.display) {
      const initial = { time: 0, value: initialDisplayNbt(imported[id], nbt.map((frame) => frame.value), nbt[0].time === 0 ? nbt[0].value : undefined) };
      if (nbt[0].time === 0) nbt[0] = initial;
      else nbt.unshift(initial);
      ir.animation.tracks.push({ target: { node: id, attachment: "display" }, channel: "nbt", driver: {
        type: "state", keys: nbt.map((frame) => ({ time: sourceSecondsTime(frame.time), value: { merge: writeDisplayNbt(frame.value, profile) } })),
      } });
    }
  }
  return ir;
}
