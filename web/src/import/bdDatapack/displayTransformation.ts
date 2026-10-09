import { Matrix3, Matrix4, Quaternion, Vector3 } from "three";
import type { Matrix16 } from "../../domain/matrix";

export interface DisplayTransformation {
  position: number[];
  left_rotation: number[];
  scale: number[];
  right_rotation: number[];
}

export function decomposeDisplayMatrix(matrix: Matrix16): DisplayTransformation {
  const input = new Matrix4().set(...matrix);
  const factor = 1 / input.elements[15];
  const a = new Matrix3().setFromMatrix4(input).multiplyScalar(factor);
  const normal = a.clone().transpose().multiply(a);
  const right = new Quaternion();
  const at = (m: Matrix3, row: number, column: number) => m.elements[column * 3 + row];
  const rotation = (axis: "x" | "y" | "z", sinHalf: number, cosHalf: number) => new Quaternion(axis === "x" ? sinHalf : 0, axis === "y" ? sinHalf : 0, axis === "z" ? sinHalf : 0, cosHalf);
  const rotationMatrix = (q: Quaternion) => new Matrix3().setFromMatrix4(new Matrix4().makeRotationFromQuaternion(q));
  const givens = (first: number, offDiagonal: number, second: number): [number, number] => {
    const ch = 2 * (first - second);
    const sh = offDiagonal;
    if ((3 + 2 * Math.sqrt(2)) * sh * sh < ch * ch) {
      const length = Math.hypot(sh, ch);
      return [sh / length, ch / length];
    }
    return [Math.sin(Math.PI / 8), Math.cos(Math.PI / 8)];
  };
  for (let iteration = 0; iteration < 5; iteration++) {
    for (const [axis, first, second, inverse] of [["z", 0, 1, false], ["y", 0, 2, true], ["x", 1, 2, false]] as const) {
      if (at(normal, first, second) ** 2 + at(normal, second, first) ** 2 <= 1e-6) continue;
      const [sh, ch] = givens(at(normal, first, first), (at(normal, first, second) + at(normal, second, first)) / 2, at(normal, second, second));
      const q = rotation(axis, inverse ? -sh : sh, ch);
      right.multiply(q);
      const r = rotationMatrix(q);
      normal.copy(r.clone().transpose().multiply(normal).multiply(r));
    }
  }
  right.normalize();
  const zeroFirst = normal.elements[0] < 1e-6;
  const zeroSecond = normal.elements[4] < 1e-6;
  let residual = a.clone().multiply(rotationMatrix(right));
  const qr = (first: number, second: number): [number, number] => {
    const length = Math.hypot(first, second);
    let sh = length > 1e-6 ? second : 0;
    let ch = Math.abs(first) + Math.max(length, 1e-6);
    if (first < 0) [sh, ch] = [ch, sh];
    const norm = Math.hypot(sh, ch);
    return [sh / norm, ch / norm];
  };
  const left = new Quaternion();
  for (const axis of ["z", "y", "x"] as const) {
    const [sh, ch] = axis === "z" ? zeroFirst ? qr(at(residual, 1, 1), -at(residual, 0, 1)) : qr(at(residual, 0, 0), at(residual, 1, 0))
      : axis === "y" ? zeroFirst ? qr(at(residual, 2, 2), -at(residual, 0, 2)) : qr(at(residual, 0, 0), at(residual, 2, 0))
      : zeroSecond ? qr(at(residual, 2, 2), -at(residual, 1, 2)) : qr(at(residual, 1, 1), at(residual, 2, 1));
    const q = rotation(axis, axis === "y" ? -sh : sh, ch);
    left.multiply(q);
    residual = rotationMatrix(q).transpose().multiply(residual);
  }
  return {
    position: new Vector3().setFromMatrixPosition(input).multiplyScalar(factor).toArray(),
    left_rotation: left.toArray(), scale: [residual.elements[0], residual.elements[4], residual.elements[8]],
    right_rotation: right.conjugate().toArray(),
  };
}

export function interpolateDisplayTransformation(first: DisplayTransformation, second: DisplayTransformation, progress: number): DisplayTransformation {
  const p = Math.max(0, Math.min(1, progress));
  return {
    position: first.position.map((value, axis) => value + (second.position[axis] - value) * p),
    left_rotation: new Quaternion(...first.left_rotation as [number, number, number, number]).slerp(new Quaternion(...second.left_rotation as [number, number, number, number]), p).toArray(),
    scale: first.scale.map((value, axis) => value + (second.scale[axis] - value) * p),
    right_rotation: new Quaternion(...first.right_rotation as [number, number, number, number]).slerp(new Quaternion(...second.right_rotation as [number, number, number, number]), p).toArray(),
  };
}
