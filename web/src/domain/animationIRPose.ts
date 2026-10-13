import { parseMinecraftTime } from "../format/time";
import { Euler, Matrix4, Quaternion, Vector3 } from "three";
import { orderedNodeIds, type AnimationIR, type DriverIR, type ScalarIR, type TrackIR, type VisibilityIR } from "./animationIR";
import { sampleCurve, type ScalarEvaluatorIR } from "./animationIRCurves";

export interface NodePoseIR {
  matrix: Matrix4;
  orientation: Quaternion;
  visible: boolean;
  attachments: Record<string, boolean>;
}

export function evaluatePose(animation: AnimationIR, time: number, evaluate: ScalarEvaluatorIR, onApproximation?: (reason: string) => void): Record<string, NodePoseIR> {
  const result: Record<string, NodePoseIR> = {};
  const tracks = new Map(animation.animation.tracks.map((track) => [JSON.stringify([track.target.node, track.target.operation, track.target.attachment, track.channel]), track]));
  const find = (node: string, channel: TrackIR["channel"], operation?: string, attachment?: string) => tracks.get(JSON.stringify([node, operation, attachment, channel]));
  const sharedValues = new Map<string, number[]>();
  const evaluatingValues = new Set<string>();
  const scalar: ScalarEvaluatorIR = (raw, progress, base) => evaluate(raw, progress, base, resolveValue);
  const resolveValue = (id: string, component: number): number => {
    const definition = animation.animation.values?.[id];
    if (!definition) throw new Error(`Unknown shared value ${id}.`);
    if (!Number.isInteger(component) || component < 0 || component >= definition.base.length) throw new Error(`Invalid component for shared value ${id}.`);
    let cached = sharedValues.get(id);
    if (!cached) {
      if (evaluatingValues.has(id)) throw new Error(`Cyclic shared value ${id}.`);
      evaluatingValues.add(id);
      try {
        cached = value(definition, definition.base, time + (definition.time_offset ? parseMinecraftTime(definition.time_offset) / 20 : 0));
        sharedValues.set(id, cached);
      } finally { evaluatingValues.delete(id); }
    }
    return cached[component];
  };
  const value = (driver: DriverIR | undefined, base: readonly number[], sampleTime = time): number[] => {
    if (!driver) return [...base];
    if (driver.type === "curve") return sampleCurve(driver, sampleTime, base, scalar);
    if (driver.type !== "expression" || !Array.isArray(driver.value)) throw new Error("Transform requires an expression vector or curve.");
    return (driver.value as readonly ScalarIR[]).map((raw, axis) => scalar(raw, 0, base[axis]));
  };
  const visible = (driver: DriverIR | undefined, base: boolean): boolean => {
    if (!driver) return base;
    let raw: VisibilityIR;
    if (driver.type === "expression") raw = driver.value as VisibilityIR;
    else if (driver.type === "state") {
      const key = driver.keys.filter((key) => parseMinecraftTime(key.time) / 20 <= time).at(-1);
      if (!key) return base;
      raw = key.value as VisibilityIR;
    } else throw new Error("Visibility requires an expression or state driver.");
    return typeof raw === "boolean" ? raw : scalar(raw, 0, base ? 1 : 0) !== 0;
  };
  const sampled = new Map<TrackIR, number[] | boolean | Error>();
  for (const track of animation.animation.tracks) {
    const node = animation.nodes[track.target.node];
    if (track.channel === "value") {
      try {
        const base = node.transform!.find((operation) => operation.id === track.target.operation)!.value;
        const weight = track.blend ? scalar(track.blend.weight, 0, 1) : 1;
        const sample = value(track.driver, base);
        sampled.set(track, track.blend ? sample.map((axis, index) => track.blend!.base[index] + (axis - track.blend!.base[index]) * weight) : sample);
      } catch (reason) {
        if (!onApproximation) throw reason;
        sampled.set(track, reason instanceof Error ? reason : new Error(String(reason)));
      }
    } else if (track.channel === "visible") {
      const base = track.target.attachment ? node.attachments?.[track.target.attachment]?.visible ?? true : node.visible ?? true;
      sampled.set(track, visible(track.driver, base));
    }
  }
  for (const id of orderedNodeIds(animation.nodes)) {
    const node = animation.nodes[id];
    const local = new Matrix4();
    const rotation = new Quaternion();
    let hasMatrix = false;
    for (const operation of node.transform ?? []) {
      let v: number[];
      try {
        const track = find(id, "value", operation.id);
        const sample = track ? sampled.get(track) : undefined;
        if (sample instanceof Error) throw sample;
        v = sample ? sample as number[] : [...operation.value];
        v = v.map((component, axis) => {
          if (Number.isFinite(component)) return component;
          if (!onApproximation) throw new Error(`${id}.${operation.id}[${axis}] produced a non-finite transform.`);
          onApproximation(`${id}.${operation.id}[${axis}] uses its base component.`);
          return operation.value[axis];
        });
      } catch (reason) {
        if (!onApproximation) throw reason;
        onApproximation(`${id}.${operation.id} uses its base operation.`);
        v = [...operation.value];
      }
      if (operation.op === "translate") local.multiply(new Matrix4().makeTranslation(v[0], v[1], v[2]));
      else if (operation.op === "scale") local.multiply(new Matrix4().makeScale(v[0], v[1], v[2]));
      else if (operation.op === "matrix") { local.multiply(new Matrix4().set(...v as Parameters<Matrix4["set"]>)); hasMatrix = true; }
      else {
        const q = operation.op === "rotate_euler"
          ? new Quaternion().setFromEuler(new Euler(v[0] * Math.PI / 180, v[1] * Math.PI / 180, v[2] * Math.PI / 180, operation.order))
          : new Quaternion(...v as [number, number, number, number]).normalize();
        local.multiply(new Matrix4().makeRotationFromQuaternion(q));
        rotation.multiply(q);
      }
    }
    let world = local;
    const visibilityTrack = find(id, "visible");
    let effectiveVisible = visibilityTrack ? sampled.get(visibilityTrack) as boolean : node.visible ?? true;
    let orientation = rotation;
    if (node.parent) {
      const parent = result[node.parent];
      if ((node.inherit?.rotation ?? "parent") === "entity" || node.inherit?.scale === false) {
        let ancestor: string | undefined = node.parent;
        while (ancestor) {
          if (animation.nodes[ancestor].transform?.some((operation) => operation.op === "matrix")) {
            if (!onApproximation) throw new Error(`Selective inheritance at ${id} requires explicit ancestor orientation.`);
            onApproximation(`Selective inheritance at ${id} uses a decomposed matrix orientation.`);
          }
          ancestor = animation.nodes[ancestor].parent;
        }
      }
      const parentRotation = new Matrix4().makeRotationFromQuaternion(parent.orientation);
      const inheritRotation = node.inherit?.rotation === "entity" ? new Matrix4() : parentRotation.clone();
      const residual = parentRotation.clone().invert().multiply(parent.matrix.clone().setPosition(0, 0, 0));
      if (node.inherit?.scale !== false) inheritRotation.multiply(residual);
      inheritRotation.setPosition(new Vector3().setFromMatrixPosition(parent.matrix));
      world = inheritRotation.multiply(local);
      orientation = node.inherit?.rotation === "entity" ? rotation : parent.orientation.clone().multiply(rotation);
      if (node.inherit?.visibility !== false) effectiveVisible &&= parent.visible;
    }
    if (hasMatrix && Object.values(animation.nodes).some((child) => child.parent === id && (child.inherit?.scale === false || child.inherit?.rotation === "entity"))) {
      if (!onApproximation) throw new Error(`Matrix node ${id} cannot provide selective inheritance orientation.`);
      world.decompose(new Vector3(), orientation, new Vector3());
      onApproximation(`Matrix node ${id} uses a decomposed orientation.`);
    }
    const attachments = Object.fromEntries(Object.entries(node.attachments ?? {}).sort(([a], [b]) => a.localeCompare(b))
      .map(([attachmentId, attachment]) => {
        const track = find(id, "visible", undefined, attachmentId);
        return [attachmentId, effectiveVisible && (track ? sampled.get(track) as boolean : attachment.visible ?? true)];
      }));
    result[id] = { matrix: world, orientation, visible: effectiveVisible, attachments };
  }
  return result;
}
