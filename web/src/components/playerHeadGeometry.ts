import * as THREE from "three";
import type { AttachmentIR } from "../domain/animationIR";

const ATLAS = {
  head: { base: [0, 0], outer: [32, 0], width: 8, height: 8, depth: 8 },
  body: { base: [16, 16], outer: [16, 32], width: 8, height: 12, depth: 4 },
  right_arm: { base: [40, 16], outer: [40, 32], width: 4, height: 12, depth: 4 },
  left_arm: { base: [32, 48], outer: [48, 48], width: 4, height: 12, depth: 4 },
  right_leg: { base: [0, 16], outer: [0, 32], width: 4, height: 12, depth: 4 },
  left_leg: { base: [16, 48], outer: [0, 48], width: 4, height: 12, depth: 4 },
};

export function createPlayerHeadGeometry(skin?: Extract<AttachmentIR, { type: "player_skin" }>, outer = false): THREE.BoxGeometry {
  // Minecraft's skull model occupies x/z -0.25..0.25 and y -0.5..0.
  // BD Engine matrices are authored against that model-space origin.
  const size = outer ? 0.5 * 17 / 16 : 0.5;
  const geometry = new THREE.BoxGeometry(size, size, size);
  geometry.translate(0, -0.25, 0);
  if (skin) {
    const atlas = ATLAS[skin.part];
    const [u, v] = outer ? atlas.outer : atlas.base;
    const { width, height, depth } = atlas;
    const start = Math.min(height - 1, Math.floor(height * skin.region.from));
    const end = Math.min(height, Math.max(start + 1, Math.floor(height * skin.region.to)));
    const faces = [
      [u + depth + width, v + depth + start, depth, end - start],
      [u, v + depth + start, depth, end - start],
      [u + depth, v, width, depth],
      [u + depth + width, v, width, depth],
      [u + depth * 2 + width, v + depth + start, width, end - start],
      [u + depth, v + depth + start, width, end - start],
    ];
    const uv = geometry.getAttribute("uv");
    faces.forEach(([x, y, w, h], face) => {
      uv.setXY(face * 4, x / 64, 1 - y / 64);
      uv.setXY(face * 4 + 1, (x + w) / 64, 1 - y / 64);
      uv.setXY(face * 4 + 2, x / 64, 1 - (y + h) / 64);
      uv.setXY(face * 4 + 3, (x + w) / 64, 1 - (y + h) / 64);
    });
  }
  return geometry;
}
