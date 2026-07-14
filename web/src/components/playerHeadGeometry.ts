import * as THREE from "three";

export function createPlayerHeadGeometry(): THREE.BoxGeometry {
  // Minecraft 26.2 effective player_head item bounds after its model transform
  // and ItemDisplayRenderer's 180-degree Y rotation.
  const geometry = new THREE.BoxGeometry(0.5, 0.5, 0.5);
  geometry.translate(-0.5, 0.25, -0.5);
  return geometry;
}
