import * as THREE from "three";

export function createPlayerHeadGeometry(): THREE.BoxGeometry {
  // Minecraft's skull model occupies x/z -0.25..0.25 and y -0.5..0.
  // BD Engine matrices are authored against that model-space origin.
  const geometry = new THREE.BoxGeometry(0.5, 0.5, 0.5);
  geometry.translate(0, -0.25, 0);
  return geometry;
}
