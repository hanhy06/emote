import { Euler, Matrix4, Quaternion, Vector3 } from "three";
import type { LocalTransform, Matrix16, Vec3 } from "./emoteAnimation";
import { matrix4ToRowMajor, stabilizeDisplayMatrix } from "./matrix";

const ZERO_SCALE_EPSILON = 1e-12;

export function matrixToLocalTransform(matrix: Matrix16, label: string): LocalTransform {
  const stable = stabilizeDisplayMatrix(matrix, label);
  const position = new Vector3();
  const rotation = new Quaternion();
  const scale = new Vector3();
  new Matrix4().set(...stable).decompose(position, rotation, scale);
  if (Math.hypot(stable[0], stable[4], stable[8]) <= ZERO_SCALE_EPSILON) scale.x = 0;
  if (Math.hypot(stable[1], stable[5], stable[9]) <= ZERO_SCALE_EPSILON) scale.y = 0;
  if (Math.hypot(stable[2], stable[6], stable[10]) <= ZERO_SCALE_EPSILON) scale.z = 0;
  const euler = new Euler().setFromQuaternion(rotation, "XYZ");
  return {
    position: cleanVec3([position.x, position.y, position.z]),
    rotation: cleanVec3([euler.x * 180 / Math.PI, euler.y * 180 / Math.PI, euler.z * 180 / Math.PI]),
    scale: cleanVec3([scale.x, scale.y, scale.z]),
  };
}

export function localTransformToMatrix(transform: LocalTransform, label: string): Matrix16 {
  const rotation = transform.rotation.map((value) => value * Math.PI / 180) as [number, number, number];
  return matrix4ToRowMajor(new Matrix4().compose(
    new Vector3(...transform.position),
    new Quaternion().setFromEuler(new Euler(...rotation, "XYZ")),
    new Vector3(...transform.scale),
  ), label);
}

function cleanVec3(values: Vec3): Vec3 {
  return values.map((value) => Math.abs(value) < 1e-12 ? 0 : value) as unknown as Vec3;
}
