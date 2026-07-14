export const SKIN_PARTS = [
  { id: "head", label: "머리", color: "#f0b65f" },
  { id: "body", label: "몸통", color: "#7198f5" },
  { id: "left_arm", label: "왼팔", color: "#b184f5" },
  { id: "right_arm", label: "오른팔", color: "#55c7dd" },
  { id: "left_leg", label: "왼다리", color: "#ef7f9b" },
  { id: "right_leg", label: "오른다리", color: "#74ca86" },
] as const;

export type SkinPartId = typeof SKIN_PARTS[number]["id"];
export type PartAssignments = Record<number, SkinPartId | null>;
export type PartOrders = Record<number, number | null>;

const SKIN_PART_IDS = new Set<string>(SKIN_PARTS.map((part) => part.id));
const LIMB_PART_IDS = new Set<SkinPartId>(["left_arm", "right_arm", "left_leg", "right_leg"]);

export function isSkinPartId(value: string): value is SkinPartId {
  return SKIN_PART_IDS.has(value);
}

export function isLimbPart(value: SkinPartId): boolean {
  return LIMB_PART_IDS.has(value);
}

export function markerFor(skinPart: SkinPartId, order?: number | null): string {
  return `emote:${skinPart}${order == null ? "" : order}`;
}
