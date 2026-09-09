import type { Matrix4 } from "three";
import type { BbCube, BbGroup, BbLocator } from "./blockbenchCubeSchema";

export interface BoneNodeEntry {
  id: string;
  localMatrix: Matrix4;
  ignoreInheritedScale?: boolean;
  locatorName?: string;
}

export interface BoneEntry {
  id: string;
  uuid: string;
  group: BbGroup;
  parent?: BoneEntry;
  cubes: BbCube[];
  locators: BbLocator[];
  nodes: BoneNodeEntry[];
}
