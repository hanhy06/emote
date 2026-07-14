import * as THREE from "three";

export function createPlayerHeadGeometry(): THREE.BoxGeometry {
  // BD Engine matrices use the logical player-head center at (0, 0.5, 0).
  // The rendered head itself is 0.5 blocks wide in Minecraft 26.2.
  const geometry = new THREE.BoxGeometry(0.5, 0.5, 0.5);
  geometry.translate(0, 0.5, 0);
  return geometry;
}
