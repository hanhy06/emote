import type { PlayerSkinPart } from "../domain/player";

export const SKIN_PARTS = [
  { id: "head", label: "Head", color: "#f0b65f" },
  { id: "body", label: "Body", color: "#7198f5" },
  { id: "left_arm", label: "Left Arm", color: "#b184f5" },
  { id: "right_arm", label: "Right Arm", color: "#55c7dd" },
  { id: "left_leg", label: "Left Leg", color: "#ef7f9b" },
  { id: "right_leg", label: "Right Leg", color: "#74ca86" },
] as const;

export type PartAssignments = Record<string, PlayerSkinPart | null>;
export type PartOrders = Record<string, number | null>;
