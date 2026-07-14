import { useEffect, useRef } from "react";
import * as THREE from "three";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import type { PlayerHeadPart } from "../converter/partParser";

interface PartPreviewProps {
  parts: PlayerHeadPart[];
}

export function PartPreview({ parts }: PartPreviewProps) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) {
      return;
    }

    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x0d1017);

    const camera = new THREE.PerspectiveCamera(38, 1, 0.01, 100);
    camera.position.set(4.5, 3.2, 5.5);

    const renderer = new THREE.WebGLRenderer({ antialias: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    container.append(renderer.domElement);

    const controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    controls.target.set(0, 1.2, 0);

    scene.add(new THREE.HemisphereLight(0xdde8ff, 0x171b26, 2.4));
    const keyLight = new THREE.DirectionalLight(0xffffff, 2.2);
    keyLight.position.set(4, 7, 5);
    scene.add(keyLight);

    const grid = new THREE.GridHelper(5, 10, 0x394052, 0x202530);
    scene.add(grid);

    const partGroup = new THREE.Group();
    const geometry = new THREE.BoxGeometry(1, 1, 1);
    geometry.translate(0, 0.5, 0);
    const edgeGeometry = new THREE.EdgesGeometry(geometry);

    for (const part of parts) {
      const material = new THREE.MeshStandardMaterial({
        color: new THREE.Color().setHSL((part.partIndex * 0.083) % 1, 0.62, 0.58),
        roughness: 0.72,
        metalness: 0.04,
      });
      const mesh = new THREE.Mesh(geometry, material);
      mesh.matrixAutoUpdate = false;
      mesh.matrix.set(...part.matrix as [number, number, number, number, number, number, number, number, number, number, number, number, number, number, number, number]);
      partGroup.add(mesh);

      const edges = new THREE.LineSegments(edgeGeometry, new THREE.LineBasicMaterial({ color: 0xdce5ff }));
      edges.matrixAutoUpdate = false;
      edges.matrix.copy(mesh.matrix);
      partGroup.add(edges);
    }
    scene.add(partGroup);

    const bounds = new THREE.Box3().setFromObject(partGroup);
    if (!bounds.isEmpty()) {
      const center = bounds.getCenter(new THREE.Vector3());
      const size = bounds.getSize(new THREE.Vector3()).length();
      controls.target.copy(center);
      camera.position.copy(center).add(new THREE.Vector3(size * 1.2, size * 0.5, size * 1.4));
      camera.near = Math.max(size / 100, 0.01);
      camera.far = Math.max(size * 20, 100);
      camera.updateProjectionMatrix();
    }

    const resizeObserver = new ResizeObserver(() => {
      const width = container.clientWidth;
      const height = container.clientHeight;
      renderer.setSize(width, height, false);
      camera.aspect = width / Math.max(height, 1);
      camera.updateProjectionMatrix();
    });
    resizeObserver.observe(container);

    let animationFrame = 0;
    const render = () => {
      controls.update();
      renderer.render(scene, camera);
      animationFrame = requestAnimationFrame(render);
    };
    render();

    return () => {
      cancelAnimationFrame(animationFrame);
      resizeObserver.disconnect();
      controls.dispose();
      geometry.dispose();
      edgeGeometry.dispose();
      scene.traverse((object) => {
        if (object instanceof THREE.Mesh || object instanceof THREE.LineSegments) {
          const materials = Array.isArray(object.material) ? object.material : [object.material];
          materials.forEach((material) => material.dispose());
        }
      });
      renderer.dispose();
      renderer.domElement.remove();
    };
  }, [parts]);

  return <div className="part-preview" ref={containerRef} aria-label="이모트 조각 3D 미리보기" />;
}
