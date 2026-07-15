import { useEffect, useRef, useState } from "react";
import * as THREE from "three";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import type { PlayerHeadPart } from "../preview/playerHeadPart";
import { SKIN_PARTS, type PartAssignments } from "../preview/skinMapping";
import { createPlayerHeadGeometry } from "./playerHeadGeometry";

interface PartPreviewProps {
  parts: PlayerHeadPart[];
  assignments: PartAssignments;
  selectedParts: ReadonlySet<string>;
  onSelectPart: (nodeId: string, additive: boolean) => void;
}

const ASSIGNMENT_COLORS = new Map(SKIN_PARTS.map((part) => [part.id, part.color]));

export function PartPreview({ parts, assignments, selectedParts, onSelectPart }: PartPreviewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const materialsRef = useRef(new Map<string, THREE.MeshStandardMaterial>());
  const onSelectPartRef = useRef(onSelectPart);
  const cameraStateRef = useRef<{ position: THREE.Vector3; target: THREE.Vector3 } | null>(null);
  const [renderError, setRenderError] = useState("");

  onSelectPartRef.current = onSelectPart;

  useEffect(() => {
    for (const [nodeId, material] of materialsRef.current) {
      const assignment = assignments[nodeId];
      material.color.set(assignment ? ASSIGNMENT_COLORS.get(assignment)! : "#777777");
      material.emissive.set(selectedParts.has(nodeId) ? "#3d73b9" : "#000000");
      material.emissiveIntensity = selectedParts.has(nodeId) ? 0.7 : 0;
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
      setRenderError("Could not create the 3D preview in this browser.");
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
    const geometry = createPlayerHeadGeometry();
    const edgeGeometry = new THREE.EdgesGeometry(geometry);

    for (const part of parts) {
      const assignment = assignments[part.nodeId];
      const material = new THREE.MeshStandardMaterial({
        color: assignment ? ASSIGNMENT_COLORS.get(assignment) : "#777777",
        emissive: selectedParts.has(part.nodeId) ? "#3d73b9" : "#000000",
        emissiveIntensity: selectedParts.has(part.nodeId) ? 0.7 : 0,
        roughness: 0.8,
      });
      materialsRef.current.set(part.nodeId, material);

      const mesh = new THREE.Mesh(geometry, material);
      mesh.matrixAutoUpdate = false;
      mesh.matrix.set(...part.matrix as MatrixValues);
      mesh.userData.nodeId = part.nodeId;
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
    const previousCamera = cameraStateRef.current;
    if (previousCamera) {
      controls.target.copy(previousCamera.target);
      camera.position.copy(previousCamera.position);
    } else {
      controls.target.copy(center);
      camera.position.copy(center).add(new THREE.Vector3(size * 2.4, size * 1.1, size * 2.8));
    }
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
    let clickCycle: { x: number; y: number; nodeIds: string[]; index: number } | null = null;
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
      const nodeIds = [...new Set(raycaster.intersectObjects(clickableMeshes, false)
        .map((intersection) => intersection.object.userData.nodeId as string))];
      if (nodeIds.length === 0) {
        clickCycle = null;
        return;
      }
      const continuesCycle = clickCycle !== null
        && Math.hypot(event.clientX - clickCycle.x, event.clientY - clickCycle.y) <= 8
        && nodeIds.length === clickCycle.nodeIds.length
        && nodeIds.every((nodeId, index) => nodeId === clickCycle!.nodeIds[index]);
      const index = continuesCycle ? (clickCycle!.index + 1) % nodeIds.length : 0;
      clickCycle = { x: event.clientX, y: event.clientY, nodeIds, index };
      onSelectPartRef.current(nodeIds[index], event.ctrlKey || event.metaKey || event.shiftKey);
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
      cameraStateRef.current = { position: camera.position.clone(), target: controls.target.clone() };
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
  }, [parts]);

  if (renderError) return <p className="preview-error">{renderError}</p>;
  return <div className="part-preview" ref={containerRef} aria-label="3D preview of emote pieces" />;
}

type MatrixValues = [number, number, number, number, number, number, number, number, number, number, number, number, number, number, number, number];
