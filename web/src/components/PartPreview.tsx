import { useEffect, useRef, useState } from "preact/hooks";
import * as THREE from "three";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import type { PlayerHeadPart } from "../preview/playerHeadPart";
import { SKIN_PARTS, type PartAssignments } from "../preview/skinAssignment";
import { createPlayerHeadGeometry } from "./playerHeadGeometry";

interface PartPreviewProps {
  parts: PlayerHeadPart[];
  assignments: PartAssignments;
  selectedParts: ReadonlySet<string>;
  onSelectPart: (nodeId: string, additive: boolean) => void;
  onSelectParts: (nodeIds: readonly string[], additive: boolean) => void;
}

const ASSIGNMENT_COLORS = new Map(SKIN_PARTS.map((part) => [part.id, part.color]));

export default function PartPreview({ parts, assignments, selectedParts, onSelectPart, onSelectParts }: PartPreviewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const materialsRef = useRef(new Map<string, THREE.MeshStandardMaterial>());
  const onSelectPartRef = useRef(onSelectPart);
  const onSelectPartsRef = useRef(onSelectParts);
  const selectedPartsRef = useRef(selectedParts);
  const cameraStateRef = useRef<{ position: THREE.Vector3; target: THREE.Vector3 } | null>(null);
  const resetViewRef = useRef<() => void>(() => {});
  const [renderError, setRenderError] = useState("");

  onSelectPartRef.current = onSelectPart;
  onSelectPartsRef.current = onSelectParts;
  selectedPartsRef.current = selectedParts;

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
      if (part.conversionMatrix) mesh.matrix.multiply(new THREE.Matrix4().set(...part.conversionMatrix as MatrixValues));
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
    const initialTarget = center.clone().add(new THREE.Vector3(0, size * 0.7, 0));
    const initialPosition = initialTarget.clone().add(new THREE.Vector3(size * 4.3, size * 1.9, size * 5));
    const previousCamera = cameraStateRef.current;
    if (previousCamera) {
      controls.target.copy(previousCamera.target);
      camera.position.copy(previousCamera.position);
    } else {
      controls.target.copy(initialTarget);
      camera.position.copy(initialPosition);
    }
    resetViewRef.current = () => {
      controls.target.copy(initialTarget);
      camera.position.copy(initialPosition);
      camera.up.set(0, 1, 0);
      controls.update();
      cameraStateRef.current = { position: camera.position.clone(), target: controls.target.clone() };
    };
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
    let rangeSelection = false;
    const selectionBox = document.createElement("div");
    selectionBox.className = "selection-box";

    const finishRangeSelection = (event: PointerEvent) => {
      if (!pointerStart || !rangeSelection) return false;
      const rectangle = renderer.domElement.getBoundingClientRect();
      const left = Math.max(rectangle.left, Math.min(pointerStart.x, event.clientX));
      const right = Math.min(rectangle.right, Math.max(pointerStart.x, event.clientX));
      const top = Math.max(rectangle.top, Math.min(pointerStart.y, event.clientY));
      const bottom = Math.min(rectangle.bottom, Math.max(pointerStart.y, event.clientY));
      const nodeIds = clickableMeshes.flatMap((mesh) => {
        const box = new THREE.Box3().setFromObject(mesh);
        const projectedMin = { x: Number.POSITIVE_INFINITY, y: Number.POSITIVE_INFINITY };
        const projectedMax = { x: Number.NEGATIVE_INFINITY, y: Number.NEGATIVE_INFINITY };
        let visible = false;
        for (const x of [box.min.x, box.max.x]) {
          for (const y of [box.min.y, box.max.y]) {
            for (const z of [box.min.z, box.max.z]) {
              const point = new THREE.Vector3(x, y, z).project(camera);
              if (point.z >= -1 && point.z <= 1) visible = true;
              projectedMin.x = Math.min(projectedMin.x, rectangle.left + (point.x + 1) * rectangle.width / 2);
              projectedMin.y = Math.min(projectedMin.y, rectangle.top + (1 - point.y) * rectangle.height / 2);
              projectedMax.x = Math.max(projectedMax.x, rectangle.left + (point.x + 1) * rectangle.width / 2);
              projectedMax.y = Math.max(projectedMax.y, rectangle.top + (1 - point.y) * rectangle.height / 2);
            }
          }
        }
        const intersects = visible
          && projectedMax.x >= left
          && projectedMin.x <= right
          && projectedMax.y >= top
          && projectedMin.y <= bottom;
        return intersects ? [mesh.userData.nodeId as string] : [];
      });
      onSelectPartsRef.current(nodeIds, true);
      return true;
    };

    const clearRangeSelection = () => {
      rangeSelection = false;
      selectionBox.remove();
      container.classList.remove("selection-active");
      controls.enabled = true;
    };

    const handlePointerDown = (event: PointerEvent) => {
      pointerStart = { x: event.clientX, y: event.clientY };
      if (!event.ctrlKey) return;
      event.preventDefault();
      controls.enabled = false;
      renderer.domElement.setPointerCapture(event.pointerId);
    };
    const handlePointerMove = (event: PointerEvent) => {
      if (!pointerStart || !event.ctrlKey || Math.hypot(event.clientX - pointerStart.x, event.clientY - pointerStart.y) <= 5) return;
      rangeSelection = true;
      const rectangle = renderer.domElement.getBoundingClientRect();
      const startX = Math.max(0, Math.min(rectangle.width, pointerStart.x - rectangle.left));
      const startY = Math.max(0, Math.min(rectangle.height, pointerStart.y - rectangle.top));
      const currentX = Math.max(0, Math.min(rectangle.width, event.clientX - rectangle.left));
      const currentY = Math.max(0, Math.min(rectangle.height, event.clientY - rectangle.top));
      selectionBox.style.left = `${Math.min(startX, currentX)}px`;
      selectionBox.style.top = `${Math.min(startY, currentY)}px`;
      selectionBox.style.width = `${Math.abs(currentX - startX)}px`;
      selectionBox.style.height = `${Math.abs(currentY - startY)}px`;
      if (!selectionBox.isConnected) {
        container.append(selectionBox);
        container.classList.add("selection-active");
      }
    };
    const handlePointerUp = (event: PointerEvent) => {
      if (finishRangeSelection(event)) {
        pointerStart = null;
        clearRangeSelection();
        return;
      }
      clearRangeSelection();
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
      if (nodeIds.length === 0) return;
      const selectedIndex = nodeIds.findIndex((nodeId) => selectedPartsRef.current.has(nodeId));
      const nextIndex = selectedIndex < 0 ? 0 : (selectedIndex + 1) % nodeIds.length;
      onSelectPartRef.current(nodeIds[nextIndex], event.ctrlKey || event.metaKey || event.shiftKey);
    };
    const handlePointerCancel = () => {
      pointerStart = null;
      clearRangeSelection();
    };
    renderer.domElement.addEventListener("pointerdown", handlePointerDown, true);
    renderer.domElement.addEventListener("pointermove", handlePointerMove, true);
    renderer.domElement.addEventListener("pointerup", handlePointerUp);
    renderer.domElement.addEventListener("pointercancel", handlePointerCancel);

    let animationFrame = 0;
    const render = () => {
      controls.update();
      renderer.render(scene, camera);
      animationFrame = requestAnimationFrame(render);
    };
    render();

    return () => {
      resetViewRef.current = () => {};
      cameraStateRef.current = { position: camera.position.clone(), target: controls.target.clone() };
      cancelAnimationFrame(animationFrame);
      resizeObserver.disconnect();
      renderer.domElement.removeEventListener("pointerdown", handlePointerDown, true);
      renderer.domElement.removeEventListener("pointermove", handlePointerMove, true);
      renderer.domElement.removeEventListener("pointerup", handlePointerUp);
      renderer.domElement.removeEventListener("pointercancel", handlePointerCancel);
      selectionBox.remove();
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
  return (
    <div className="part-preview" ref={containerRef} aria-label="3D preview of emote parts">
      <button type="button" className="preview-reset-view" onClick={() => resetViewRef.current()} aria-label="Reset view" title="Reset view">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5" />
          <circle cx="12" cy="12" r="3" />
        </svg>
      </button>
    </div>
  );
}

type MatrixValues = [number, number, number, number, number, number, number, number, number, number, number, number, number, number, number, number];
