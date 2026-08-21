import { normalizeResourceLocation } from "../format/resourceLocation";
import { readSnbtRawField, readSnbtStringField } from "../format/snbt";
import type { PlayerSkinPart } from "../format/emoteAnimation";

export const SKIN_PARTS = [
  { id: "head", label: "Head", color: "#f0b65f" },
  { id: "body", label: "Body", color: "#7198f5" },
  { id: "left_arm", label: "Left Arm", color: "#b184f5" },
  { id: "right_arm", label: "Right Arm", color: "#55c7dd" },
  { id: "left_leg", label: "Left Leg", color: "#ef7f9b" },
  { id: "right_leg", label: "Right Leg", color: "#74ca86" },
] as const;

export type SkinPartId = PlayerSkinPart;
export type PartAssignments = Record<string, SkinPartId | null>;
export type PartOrders = Record<string, number | null>;

export function assignSkinPart(
  assignments: PartAssignments,
  orders: PartOrders,
  selectedNodeIds: readonly string[],
  part: SkinPartId | null,
  assignmentGroups: Readonly<Record<string, string>> = {},
): { assignments: PartAssignments; orders: PartOrders } {
  const nextAssignments = { ...assignments };
  const nextOrders = { ...orders };
  const selectedGroups = new Set(selectedNodeIds.map((nodeId) => assignmentGroups[nodeId] ?? nodeId));
  const assignedNodeIds = Object.keys(assignments)
    .filter((nodeId) => selectedGroups.has(assignmentGroups[nodeId] ?? nodeId));
  selectedNodeIds.forEach((nodeId) => {
    if (!assignedNodeIds.includes(nodeId)) assignedNodeIds.push(nodeId);
  });
  if (part === null) {
    assignedNodeIds.forEach((nodeId) => {
      nextAssignments[nodeId] = null;
      nextOrders[nodeId] = null;
    });
    return { assignments: nextAssignments, orders: nextOrders };
  }

  if (selectedGroups.size !== 1) {
    assignedNodeIds.forEach((nodeId) => {
      nextAssignments[nodeId] = part;
      nextOrders[nodeId] = orders[nodeId] ?? 0;
    });
    return { assignments: nextAssignments, orders: nextOrders };
  }

  const order = new Set(Object.entries(assignments)
    .filter(([nodeId, assignment]) => !selectedGroups.has(assignmentGroups[nodeId] ?? nodeId) && assignment === part)
    .map(([nodeId]) => assignmentGroups[nodeId] ?? nodeId)).size;
  assignedNodeIds.forEach((nodeId) => {
    nextAssignments[nodeId] = part;
    nextOrders[nodeId] = order;
  });
  return { assignments: nextAssignments, orders: nextOrders };
}

export function isPlayerHeadItemStack(itemStackSnbt: string): boolean {
  const quotedId = readSnbtStringField(itemStackSnbt, "id");
  const rawId = quotedId === null ? readSnbtRawField(itemStackSnbt, "id") : null;
  const id = quotedId ?? (rawId && /^[A-Za-z0-9._+-]+$/.test(rawId) ? rawId : null);
  return id !== null && normalizeResourceLocation(id) === "minecraft:player_head";
}

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
