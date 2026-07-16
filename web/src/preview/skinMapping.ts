export const SKIN_PARTS = [
  { id: "head", label: "Head", color: "#f0b65f" },
  { id: "body", label: "Body", color: "#7198f5" },
  { id: "left_arm", label: "Left Arm", color: "#b184f5" },
  { id: "right_arm", label: "Right Arm", color: "#55c7dd" },
  { id: "left_leg", label: "Left Leg", color: "#ef7f9b" },
  { id: "right_leg", label: "Right Leg", color: "#74ca86" },
] as const;

export type SkinPartId = typeof SKIN_PARTS[number]["id"];
export type PartAssignments = Record<string, SkinPartId | null>;
export type PartOrders = Record<string, number | null>;

export function selectPart(current: ReadonlySet<string>, nodeId: string, additive: boolean): Set<string> {
  if (!additive) return current.has(nodeId) ? new Set() : new Set([nodeId]);
  const next = new Set(current);
  if (next.has(nodeId)) next.delete(nodeId);
  else next.add(nodeId);
  return next;
}

export function selectParts(current: ReadonlySet<string>, nodeIds: readonly string[], additive: boolean): Set<string> {
  const next = additive ? new Set(current) : new Set<string>();
  nodeIds.forEach((nodeId) => next.add(nodeId));
  return next;
}
