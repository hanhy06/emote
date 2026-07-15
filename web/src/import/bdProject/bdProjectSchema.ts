import {
  optionalArray,
  optionalBoolean,
  optionalNumber,
  optionalRecord,
  optionalString,
  requireArray,
  requireNumber,
  requireNumberArray,
  requireRecord,
} from "../../format/runtimeValue";

export interface BdSceneNode {
  isCollection?: boolean;
  isItemDisplay?: boolean;
  isBlockDisplay?: boolean;
  isTextDisplay?: boolean;
  name?: string;
  nbt?: string;
  transforms?: number[];
  defaultTransform?: BdTransform;
  pivotCustom?: number[];
  animation?: BdAnimationSample[] | null;
  children?: BdSceneNode[];
  brightness?: { sky?: number; block?: number };
  emissiveIntensity?: number;
  tagHead?: { Value?: string };
  listAnim?: { id?: number; name?: string }[];
  listSound?: { tracks?: unknown[] }[];
}

export interface BdTransform {
  position?: VectorLike;
  rotation?: VectorLike;
  scale?: VectorLike;
}

interface BdAnimationSample extends BdTransform {
  time: number;
}

export type VectorLike = { x?: number; y?: number; z?: number } | number[];

export function requireBdSceneNode(value: unknown, path: string): BdSceneNode {
  const node = requireRecord(value, path);
  optionalBoolean(node.isCollection, `${path}.isCollection`);
  optionalBoolean(node.isItemDisplay, `${path}.isItemDisplay`);
  optionalBoolean(node.isBlockDisplay, `${path}.isBlockDisplay`);
  optionalBoolean(node.isTextDisplay, `${path}.isTextDisplay`);
  optionalString(node.name, `${path}.name`);
  optionalString(node.nbt, `${path}.nbt`);
  if (node.transforms !== undefined) requireNumberArray(node.transforms, `${path}.transforms`);
  if (node.pivotCustom !== undefined) requireNumberArray(node.pivotCustom, `${path}.pivotCustom`);
  if (node.defaultTransform !== undefined) requireBdTransform(node.defaultTransform, `${path}.defaultTransform`);
  if (node.animation !== undefined && node.animation !== null) {
    requireArray(node.animation, `${path}.animation`).forEach((sampleValue, index) => {
      const samplePath = `${path}.animation[${index}]`;
      const sample = requireRecord(sampleValue, samplePath);
      requireNumber(sample.time, `${samplePath}.time`);
      requireBdTransform(sample, samplePath);
    });
  }
  optionalNumber(node.emissiveIntensity, `${path}.emissiveIntensity`);
  const brightness = optionalRecord(node.brightness, `${path}.brightness`);
  if (brightness) {
    optionalNumber(brightness.sky, `${path}.brightness.sky`);
    optionalNumber(brightness.block, `${path}.brightness.block`);
  }
  const tagHead = optionalRecord(node.tagHead, `${path}.tagHead`);
  if (tagHead) optionalString(tagHead.Value, `${path}.tagHead.Value`);
  for (const [index, animationValue] of (optionalArray(node.listAnim, `${path}.listAnim`) ?? []).entries()) {
    const animation = requireRecord(animationValue, `${path}.listAnim[${index}]`);
    optionalNumber(animation.id, `${path}.listAnim[${index}].id`);
    optionalString(animation.name, `${path}.listAnim[${index}].name`);
  }
  for (const [index, soundValue] of (optionalArray(node.listSound, `${path}.listSound`) ?? []).entries()) {
    const sound = requireRecord(soundValue, `${path}.listSound[${index}]`);
    optionalArray(sound.tracks, `${path}.listSound[${index}].tracks`);
  }
  const children = optionalArray(node.children, `${path}.children`) ?? [];
  node.children = children.map((child, index) => requireBdSceneNode(child, `${path}.children[${index}]`));
  return node as BdSceneNode;
}

function requireBdTransform(value: unknown, path: string): void {
  const transform = requireRecord(value, path);
  if (transform.position !== undefined) requireVectorLike(transform.position, `${path}.position`);
  if (transform.rotation !== undefined) requireVectorLike(transform.rotation, `${path}.rotation`);
  if (transform.scale !== undefined) requireVectorLike(transform.scale, `${path}.scale`);
}

function requireVectorLike(value: unknown, path: string): void {
  if (Array.isArray(value)) {
    requireNumberArray(value, path);
    return;
  }
  const vector = requireRecord(value, path);
  optionalNumber(vector.x, `${path}.x`);
  optionalNumber(vector.y, `${path}.y`);
  optionalNumber(vector.z, `${path}.z`);
}
