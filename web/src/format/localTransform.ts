import { Euler, Matrix4, Quaternion, Vector3 } from "three";
import type { LocalTransform, Matrix16, Vec3 } from "./emoteAnimation";
import { matrix4ToRowMajor, stabilizeDisplayMatrix } from "./matrix";

const ZERO_SCALE_EPSILON = 1e-12;

export function matrixToLocalTransform(matrix: Matrix16, label: string): LocalTransform {
  const stable = stabilizeDisplayMatrix(matrix, label);
  const position = new Vector3();
  const rotation = new Quaternion();
  const scale = new Vector3();
  const source = new Matrix4().set(...stable);
  const axes = [
    new Vector3(stable[0], stable[4], stable[8]),
    new Vector3(stable[1], stable[5], stable[9]),
    new Vector3(stable[2], stable[6], stable[10]),
  ];
  const lengths = axes.map((axis) => axis.length());
  if (lengths.every((length) => length > ZERO_SCALE_EPSILON)) {
    source.decompose(position, rotation, scale);
  } else {
    position.set(stable[3], stable[7], stable[11]);
    scale.set(lengths[0], lengths[1], lengths[2]);
    recoverSingularRotation(axes, lengths, rotation);
  }
  const euler = new Euler().setFromQuaternion(rotation, "XYZ");
  return {
    position: cleanVec3([position.x, position.y, position.z]),
    rotation: cleanVec3([euler.x * 180 / Math.PI, euler.y * 180 / Math.PI, euler.z * 180 / Math.PI]),
    scale: cleanVec3([scale.x, scale.y, scale.z]),
  };
}

export function matrixToContinuousLocalTransform(matrix: Matrix16, previousRotation: Vec3, label: string): LocalTransform {
  const transform = matrixToLocalTransform(matrix, label);
  return { ...transform, rotation: closestEquivalentRotation(transform.rotation, previousRotation) };
}

function closestEquivalentRotation(rotation: Vec3, reference: Vec3): Vec3 {
  // XYZ Euler angles have two equivalent branches before whole turns are applied.
  // Select the representation nearest to the previous sample so matrix decomposition retains winding.
  const [x, y, z] = rotation;
  const branches: Vec3[] = [rotation, [x + 180, 180 - y, z + 180]];
  const candidates = branches.map((branch) => branch.map((value, axis) =>
    value + 360 * Math.round((reference[axis] - value) / 360)) as unknown as Vec3);
  const closest = candidates.reduce((result, candidate) => rotationDistance(candidate, reference) < rotationDistance(result, reference)
    ? candidate
    : result);
  return cleanVec3(closest);
}

function recoverSingularRotation(axes: Vector3[], lengths: number[], rotation: Quaternion): void {
  const present = lengths.map((length) => length > ZERO_SCALE_EPSILON);
  const count = present.filter(Boolean).length;
  if (count === 0) return;
  axes.forEach((axis, index) => {
    if (present[index]) axis.normalize();
  });
  if (count === 1) {
    const index = present.findIndex(Boolean);
    const basis = [new Vector3(1, 0, 0), new Vector3(0, 1, 0), new Vector3(0, 0, 1)];
    rotation.setFromUnitVectors(basis[index], axes[index]);
    return;
  }
  if (!present[0]) axes[0].crossVectors(axes[1], axes[2]).normalize();
  if (!present[1]) axes[1].crossVectors(axes[2], axes[0]).normalize();
  if (!present[2]) axes[2].crossVectors(axes[0], axes[1]).normalize();
  rotation.setFromRotationMatrix(new Matrix4().makeBasis(axes[0], axes[1], axes[2]));
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

function rotationDistance(rotation: Vec3, reference: Vec3): number {
  return rotation.reduce((distance, value, axis) => distance + (value - reference[axis]) ** 2, 0);
}
