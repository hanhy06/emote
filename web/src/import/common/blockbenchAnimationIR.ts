import type { CurveIR, CurveKeyIR, NodeIR, SegmentIR, VectorValueIR } from "../../domain/animationIR";
import { affineScalarIR, scalarIR } from "../../domain/animationIRConversion";
import { blockbenchEasingIR } from "../../domain/animationIRCurves";
import type { ImportedNode } from "../../domain/conversionSeed";
import { minecraftVersionProfile } from "../../format/minecraftVersionProfiles";
import { writeBlockState, writeItemStack } from "../../format/minecraftData";
import type { BbKeyframe } from "./blockbenchCubeSchema";
import { sourceSecondsTime } from "../../format/time";
import { mapMolangResult } from "../../format/molang/sourceTransformer";

export function importedNodeIR(node: ImportedNode, parent?: string): NodeIR {
  const profile = minecraftVersionProfile("26.3");
  const common = { ...(parent ? { parent } : {}), ...(node.type === "anchor" ? {} : { visible: node.visible }) };
  if (node.type === "anchor") return common;
  const content = node.type === "item_display" ? { type: "item_display" as const, item_stack_snbt: writeItemStack(node.itemStack, profile), item_display: node.itemDisplay }
    : node.type === "block_display" ? { type: "block_display" as const, block_state_snbt: writeBlockState(node.blockState, profile) }
    : { type: "text_display" as const, text: node.text };
  return { ...common,
    ...(node.type === "item_display" && node.modelGeometryMatrix ? { transform: [{ id: "model_geometry", op: "matrix" as const, value: node.modelGeometryMatrix }] } : {}),
    attachments: { display: { ...content, ...(node.entityNbt ? { entity_nbt: node.entityNbt } : {}) } },
  };
}

export function blockbenchCurveIR(frames: readonly BbKeyframe[]): CurveIR | undefined {
  if (!frames.length) return undefined;
  const grouped: BbKeyframe[][] = [];
  for (const frame of [...frames].sort((a, b) => a.time - b.time)) {
    if (grouped.at(-1)?.[0].time === frame.time) grouped.at(-1)!.push(frame);
    else grouped.push([frame]);
  }
  const values = (frame: BbKeyframe, post: boolean): VectorValueIR => {
    const point = frame.data_points[post ? frame.data_points.length - 1 : 0];
    return [point?.x, point?.y, point?.z].map((value) => scalarIR(value ?? (frame.channel === "scale" ? 1 : 0)));
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
    if (interpolation === "catmull_rom") {
      const previous = grouped[index - 1];
      const following = grouped[index + 2];
      return { ...common, ...(previous && left.data_points.length === 1 ? { previous: values(previous.at(-1)!, true) } : {}),
        ...(following && right.data_points.length === 1 ? { following: values(following[0], false) } : {}) };
    }
    if (interpolation === "bezier") {
      const duration = right.time - left.time;
      const handleTime = (raw: number | string, incoming: boolean) => {
        const time = affineScalarIR(scalarIR(raw), 1 / duration, incoming ? 1 : 0);
        return typeof time === "number" ? Math.max(0, Math.min(1, time)) : { molang: mapMolangResult(time.molang, (result) => `math.clamp(${result}, 0, 1)`) };
      };
      return { ...common, handles: [0, 1, 2].map((axis) => ({
        out: { time: handleTime(left.bezier_right_time?.[axis] ?? 0.1, false), value: scalarIR(left.bezier_right_value?.[axis] ?? 0), relative: true },
        in: { time: handleTime(right.bezier_left_time?.[axis] ?? -0.1, true), value: scalarIR(right.bezier_left_value?.[axis] ?? 0), relative: true },
      })) };
    }
    return common;
  });
  return { type: "curve", before: "first_pre", at_key: "pre", evaluation: "component", keys, segments };
}
