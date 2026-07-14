import { useEffect, useRef, useState } from "react";
import * as THREE from "three";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import type { PlayerHeadPart } from "../converter/partParser";
import { SKIN_PARTS, type PartAssignments } from "../converter/skinMapping";

interface PartPreviewProps {
  parts: PlayerHeadPart[];
  assignments: PartAssignments;
  selectedParts: ReadonlySet<number>;
  onSelectPart: (partIndex: number, additive: boolean) => void;
}

const ASSIGNMENT_COLORS = new Map(SKIN_PARTS.map((part) => [part.id, part.color]));

export function PartPreview({ parts, assignments, selectedParts, onSelectPart }: PartPreviewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const materialsRef = useRef(new Map<number, THREE.MeshStandardMaterial>());
  const [renderError, setRenderError] = useState("");

  useEffect(() => {
    for (const [partIndex, material] of materialsRef.current) {
      const assignment = assignments[partIndex];
      material.color.set(assignment ? ASSIGNMENT_COLORS.get(assignment)! : "#777777");
      material.emissive.set(selectedParts.has(partIndex) ? "#3d73b9" : "#000000");
      material.emissiveIntensity = selectedParts.has(partIndex) ? 0.7 : 0;
    }
  }, [assignments, selectedParts]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0xf4f4f4);
    const camera = new THREE.PerspectiveCamera(38, 1, 0.01, 100);

    let renderer: THREE.WebGLRenderer;
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true });
      setRenderError("");
    } catch {
      setRenderError("이 브라우저에서 3D 화면을 만들지 못했습니다.");
      return;
    }

    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    container.append(renderer.domElement);

    const controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    scene.add(new THREE.HemisphereLight(0xffffff, 0x777777, 2.4));
    const keyLight = new THREE.DirectionalLight(0xffffff, 2.2);
    keyLight.position.set(4, 7, 5);
    scene.add(keyLight);
    scene.add(new THREE.GridHelper(5, 10, 0x888888, 0xcccccc));

    const partGroup = new THREE.Group();
    const clickableMeshes: THREE.Mesh[] = [];
    const geometry = new THREE.BoxGeometry(1, 1, 1);
    geometry.translate(0, 0.5, 0);
    const edgeGeometry = new THREE.EdgesGeometry(geometry);

    for (const part of parts) {
      const assignment = assignments[part.partIndex];
      const material = new THREE.MeshStandardMaterial({
        color: assignment ? ASSIGNMENT_COLORS.get(assignment) : "#777777",
        emissive: selectedParts.has(part.partIndex) ? "#3d73b9" : "#000000",
        emissiveIntensity: selectedParts.has(part.partIndex) ? 0.7 : 0,
        roughness: 0.8,
      });
      materialsRef.current.set(part.partIndex, material);

      const mesh = new THREE.Mesh(geometry, material);
      mesh.matrixAutoUpdate = false;
      mesh.matrix.set(...part.matrix as MatrixValues);
      mesh.userData.partIndex = part.partIndex;
      partGroup.add(mesh);
      clickableMeshes.push(mesh);

      const edges = new THREE.LineSegments(edgeGeometry, new THREE.LineBasicMaterial({ color: 0x333333 }));
      edges.matrixAutoUpdate = false;
      edges.matrix.copy(mesh.matrix);
      partGroup.add(edges);
    }
    scene.add(partGroup);

    const bounds = new THREE.Box3().setFromObject(partGroup);
    const center = bounds.getCenter(new THREE.Vector3());
    const size = Math.max(bounds.getSize(new THREE.Vector3()).length(), 1);
    controls.target.copy(center);
    camera.position.copy(center).add(new THREE.Vector3(size * 1.2, size * 0.5, size * 1.4));
    camera.near = Math.max(size / 100, 0.01);
    camera.far = Math.max(size * 20, 100);
    camera.updateProjectionMatrix();

    let renderedWidth = 0;
    let renderedHeight = 0;
    const resize = () => {
      const rectangle = container.getBoundingClientRect();
      const width = Math.floor(rectangle.width);
      const height = Math.floor(rectangle.height);
      if (width < 1 || height < 1 || (width === renderedWidth && height === renderedHeight)) return;
      renderedWidth = width;
      renderedHeight = height;
      renderer.setSize(width, height, false);
      camera.aspect = width / height;
      camera.updateProjectionMatrix();
    };
    resize();
    const resizeObserver = new ResizeObserver(resize);
    resizeObserver.observe(container);

    const raycaster = new THREE.Raycaster();
    const pointer = new THREE.Vector2();
    let pointerStart: { x: number; y: number } | null = null;
    const handlePointerDown = (event: PointerEvent) => {
      pointerStart = { x: event.clientX, y: event.clientY };
    };
    const handlePointerUp = (event: PointerEvent) => {
      if (!pointerStart || Math.hypot(event.clientX - pointerStart.x, event.clientY - pointerStart.y) > 5) {
        pointerStart = null;
        return;
      }
      pointerStart = null;
      const rectangle = renderer.domElement.getBoundingClientRect();
      pointer.x = ((event.clientX - rectangle.left) / rectangle.width) * 2 - 1;
      pointer.y = -((event.clientY - rectangle.top) / rectangle.height) * 2 + 1;
      raycaster.setFromCamera(pointer, camera);
      const intersection = raycaster.intersectObjects(clickableMeshes, false)[0];
      if (intersection) onSelectPart(intersection.object.userData.partIndex as number, event.ctrlKey || event.metaKey || event.shiftKey);
    };
    renderer.domElement.addEventListener("pointerdown", handlePointerDown);
    renderer.domElement.addEventListener("pointerup", handlePointerUp);

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
      renderer.domElement.removeEventListener("pointerdown", handlePointerDown);
      renderer.domElement.removeEventListener("pointerup", handlePointerUp);
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
      materialsRef.current.clear();
    };
  }, [onSelectPart, parts]);

  if (renderError) return <p className="preview-error">{renderError}</p>;
  return <div className="part-preview" ref={containerRef} aria-label="이모트 조각 3D 미리보기" />;
}

type MatrixValues = [number, number, number, number, number, number, number, number, number, number, number, number, number, number, number, number];
