import type { CurveIR, CurveKeyIR, NodeIR, ScalarIR, SegmentIR, VectorValueIR } from "../../domain/animationIR";
import { scalarIR } from "../../domain/animationIRConversion";
import { blockbenchEasingIR } from "../../domain/animationIRCurves";
import type { ImportedNode } from "../../domain/conversionSeed";
import { minecraftVersionProfile } from "../../format/minecraftVersionProfiles";
import { writeBlockState, writeItemStack } from "../../format/minecraftData";
import type { BbKeyframe } from "./blockbenchCubeSchema";
import { affineMolang, molangScalar, type MolangVector } from "./molangVector";
import { sourceSecondsTime } from "../../format/time";

export function importedNodeIR(node: ImportedNode, parent?: string): NodeIR {
  const profile = minecraftVersionProfile("26.3");
  const common = { ...(parent ? { parent } : {}), ...(node.type === "anchor" ? {} : { visible: node.visible }) };
  if (node.type === "anchor") return common;
  const content = node.type === "item_display" ? { type: "item_display" as const, item_stack_snbt: writeItemStack(node.itemStack, profile), item_display: node.itemDisplay }
    : node.type === "block_display" ? { type: "block_display" as const, block_state_snbt: writeBlockState(node.blockState, profile) }
    : { type: "text_display" as const, text: node.text };
  return { ...common, attachments: { display: { ...content, ...(node.entityNbt ? { entity_nbt: node.entityNbt } : {}) } } };
}

export function blockbenchCurveIR(frames: readonly BbKeyframe[], transform: (values: MolangVector) => MolangVector): CurveIR | undefined {
  if (!frames.length) return undefined;
  const grouped: BbKeyframe[][] = [];
  for (const frame of [...frames].sort((a, b) => a.time - b.time)) {
    if (grouped.at(-1)?.[0].time === frame.time) grouped.at(-1)!.push(frame);
    else grouped.push([frame]);
  }
  const values = (frame: BbKeyframe, post: boolean, delta?: readonly number[]): VectorValueIR => {
    const point = frame.data_points[post ? frame.data_points.length - 1 : 0];
    const vector = [point?.x, point?.y, point?.z].map((value, axis) => affineMolang(molangScalar(value!), 1, delta?.[axis] ?? 0)) as MolangVector;
    return transform(vector).map(scalarIR);
  };
  const keys: CurveKeyIR[] = grouped.map((group) => group.length === 1 && group[0].data_points.length === 1 ? { time: sourceSecondsTime(group[0].time), value: values(group[0], true) }
    : { time: sourceSecondsTime(group[0].time), pre: values(group[0], false), post: values(group.at(-1)!, true) });
  const segments: SegmentIR[] = grouped.slice(0, -1).map((group, index) => {
    const left = group.at(-1)!, right = grouped[index + 1][0];
    if (left.interpolation === "step") return { interpolation: "step" };
    const interpolation = left.interpolation === "catmullrom" || right.interpolation === "catmullrom" ? "catmull_rom"
      : left.interpolation === "bezier" || right.interpolation === "bezier" ? "bezier" : "linear";
    const easing = interpolation === "linear" ? blockbenchEasingIR(right.easing, right.easingArgs) : undefined;
    const common = { interpolation, ...(easing ? { easing } : {}) } as SegmentIR;
    if (interpolation === "catmull_rom") return { ...common, previous: values(grouped[Math.max(0, index - 1)].at(-1)!, true), following: values(grouped[Math.min(grouped.length - 1, index + 2)][0], false) };
    if (interpolation === "bezier") {
      const duration = right.time - left.time;
      const outgoing = values(left, true, left.bezier_right_value);
      const incoming = values(right, false, right.bezier_left_value);
      return { ...common, handles: [0, 1, 2].map((axis) => ({
        out: { time: Math.max(0, Math.min(1, (left.bezier_right_time?.[axis] ?? 0.1) / duration)), value: outgoing[axis] },
        in: { time: Math.max(0, Math.min(1, 1 + (right.bezier_left_time?.[axis] ?? -0.1) / duration)), value: incoming[axis] },
      })) };
    }
    return common;
  });
  return { type: "curve", before: "base", keys, segments };
}
