import { useCallback, useMemo, useState, type ChangeEvent } from "react";
import { AssignmentPanel } from "./components/AssignmentPanel";
import { ExportPanel } from "./components/ExportPanel";
import { PartPreview } from "./components/PartPreview";
import { downloadExport, exportAnimation, exportResource, type ExportOptions } from "./export/projectExporter";
import { IMPORT_ADAPTERS } from "./import/adapters";
import { detectAdapter } from "./import/adapterRegistry";
import type { ImportedAnimation, ImportedNode, ImportedProject, ImportedSkinPart } from "./import/types";
import { createPlayerHeadPart, type PlayerHeadPart } from "./preview/playerHeadPart";
import type { PartAssignments, PartOrders, SkinPartId } from "./preview/skinMapping";

interface SkinCandidate {
  nodeId: string;
  partIndex: number;
  node: Extract<ImportedNode, { type: "item_display" }>;
}

export function App() {
  const [project, setProject] = useState<ImportedProject | null>(null);
  const [adapterLabel, setAdapterLabel] = useState("");
  const [animationIndex, setAnimationIndex] = useState(0);
  const [previewFrameIndex, setPreviewFrameIndex] = useState(0);
  const [assignments, setAssignments] = useState<PartAssignments>({});
  const [orders, setOrders] = useState<PartOrders>({});
  const [selectedParts, setSelectedParts] = useState<Set<number>>(new Set());
  const [metadata, setMetadata] = useState<ExportOptions>(emptyOptions());
  const [error, setError] = useState("");
  const [conversionError, setConversionError] = useState("");
  const [loading, setLoading] = useState(false);

  const animation = project?.animations[animationIndex];
  const skinCandidates = useMemo(() => findSkinCandidates(project), [project]);
  const previewTimes = useMemo(() => animationTimes(animation), [animation]);
  const previewTime = previewTimes[Math.min(previewFrameIndex, previewTimes.length - 1)] ?? 0;
  const previewParts = useMemo(
    () => createPreviewParts(skinCandidates, animation, previewTime),
    [animation, previewTime, skinCandidates],
  );

  async function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    setLoading(true);
    setError("");
    setConversionError("");
    setProject(null);
    try {
      const input = { name: file.name, bytes: new Uint8Array(await file.arrayBuffer()) };
      const detected = await detectAdapter(IMPORT_ADAPTERS, input);
      const imported = await detected.adapter.import(input);
      const candidates = findSkinCandidates(imported);
      setProject(imported);
      setAdapterLabel(detected.adapter.label);
      setAnimationIndex(0);
      setPreviewFrameIndex(0);
      setSelectedParts(new Set());
      setAssignments(Object.fromEntries(candidates.map((candidate) => [candidate.partIndex, candidate.node.skin?.part ?? null])));
      setOrders(Object.fromEntries(candidates.map((candidate) => [candidate.partIndex, candidate.node.skin?.order ?? null])));
      setMetadata({
        minecraftVersion: imported.suggestedMinecraftVersion ?? "26.2",
        namespace: imported.suggestedNamespace ?? imported.suggestedMetadata.command_name,
        ...imported.suggestedMetadata,
      });
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Could not import the file.");
    } finally {
      setLoading(false);
      event.target.value = "";
    }
  }

  function skinAssignments(): Record<string, ImportedSkinPart | null> {
    const skins: Record<string, ImportedSkinPart | null> = {};
    for (const candidate of skinCandidates) {
      const part = assignments[candidate.partIndex];
      skins[candidate.nodeId] = part ? { part, order: orders[candidate.partIndex] ?? 0 } : null;
    }
    return skins;
  }

  function handleAnimationDownload(index: number) {
    if (!project) return;
    setConversionError("");
    try {
      downloadExport(exportAnimation(project, metadata, skinAssignments(), index));
    } catch (reason) {
      setConversionError(reason instanceof Error ? reason.message : "Conversion failed.");
    }
  }

  function handleResourceDownload(index: number) {
    if (!project) return;
    setConversionError("");
    try {
      downloadExport(exportResource(project, metadata.minecraftVersion, index));
    } catch (reason) {
      setConversionError(reason instanceof Error ? reason.message : "Resource export failed.");
    }
  }

  const handlePartSelect = useCallback((partIndex: number, additive: boolean) => {
    const selectedPart = previewParts.find((part) => part.partIndex === partIndex);
    if (!selectedPart) return;
    const grouped = previewParts.filter((part) => distance(part.anchor, selectedPart.anchor) <= 0.05).map((part) => part.partIndex);
    setSelectedParts((current) => {
      const next = additive ? new Set(current) : new Set<number>();
      const remove = additive && grouped.every((index) => next.has(index));
      grouped.forEach((index) => remove ? next.delete(index) : next.add(index));
      return next;
    });
  }, [previewParts]);

  function assignSelected(part: SkinPartId | null) {
    if (selectedParts.size === 0) return;
    setAssignments((current) => ({ ...current, ...Object.fromEntries([...selectedParts].map((index) => [index, part])) }));
    if (!part) {
      setOrders((current) => ({ ...current, ...Object.fromEntries([...selectedParts].map((index) => [index, null])) }));
    }
  }

  function assignOrder(order: number) {
    const indices = [...selectedParts].filter((index) => assignments[index] != null);
    if (indices.length) setOrders((current) => ({ ...current, ...Object.fromEntries(indices.map((index) => [index, order])) }));
  }

  const hasSelectedAssignment = [...selectedParts].some((index) => assignments[index] != null);

  return (
    <main className="app">
      <header>
        <h1>Emote JSON Converter</h1>
        <label className="file-input">Animation file<input type="file" accept=".zip,.bdengine,.json,application/zip,application/json" onChange={handleFileChange} disabled={loading} /></label>
      </header>

      {loading && <p>Reading file…</p>}
      {error && <p className="error" role="alert">{error}</p>}

      {project && animation && (
        <>
          <div className="status">
            <strong>{project.sourceName}</strong>
            <span>{adapterLabel}</span>
            <span>{Object.keys(project.nodes).length} nodes</span>
            {previewTimes.length > 1 && skinCandidates.length > 0 && (
              <label className="frame-slider">
                <span>Preview</span>
                <input type="range" min="0" max={previewTimes.length - 1} step="1" value={previewFrameIndex} onChange={(event) => {
                  setPreviewFrameIndex(Number(event.target.value));
                  setSelectedParts(new Set());
                }} />
                <output>{Math.round(previewTime * 20)} tick</output>
              </label>
            )}
            {project.animations.length > 1 && (
              <select value={animationIndex} onChange={(event) => {
                setAnimationIndex(Number(event.target.value));
                setPreviewFrameIndex(0);
                setSelectedParts(new Set());
              }}>
                {project.animations.map((item, index) => <option value={index} key={item.id}>{item.name}</option>)}
              </select>
            )}
          </div>

          {project.diagnostics.filter((diagnostic) => diagnostic.severity === "warning").map((diagnostic) => (
            <p className="warning" key={`${diagnostic.code}:${diagnostic.sourcePath ?? ""}`}>{diagnostic.message}</p>
          ))}

          {skinCandidates.length > 0 ? (
            <section className="editor">
              <PartPreview parts={previewParts} assignments={assignments} selectedParts={selectedParts} onSelectPart={handlePartSelect} />
              <AssignmentPanel
                parts={previewParts}
                assignments={assignments}
                orders={orders}
                selectedParts={selectedParts}
                hasSelectedAssignment={hasSelectedAssignment}
                onAssignPart={assignSelected}
                onAssignOrder={assignOrder}
                onSelectPart={handlePartSelect}
              />
            </section>
          ) : (
            <section className="no-skin-parts">This input has no player_head pieces. It can be exported without skin mapping.</section>
          )}

          <ExportPanel
            metadata={metadata}
            assignmentSummary={assignmentSummary(skinCandidates, assignments, project.artifacts.length)}
            animations={project.animations.map((item) => ({ label: item.name, detail: item.id }))}
            resources={project.artifacts.map((item) => ({ label: item.path.split("/").at(-1) ?? item.path, detail: item.path }))}
            error={conversionError}
            onMetadataChange={setMetadata}
            onDownloadAnimation={handleAnimationDownload}
            onDownloadResource={handleResourceDownload}
          />
        </>
      )}
    </main>
  );
}

function findSkinCandidates(project: ImportedProject | null): SkinCandidate[] {
  if (!project) return [];
  return Object.entries(project.nodes).flatMap(([nodeId, node]) => {
    if (node.type !== "item_display" || !/minecraft:player_head/.test(node.itemStackSnbt)) return [];
    return [{ nodeId, partIndex: 0, node }];
  }).map((candidate, partIndex) => ({ ...candidate, partIndex }));
}

function animationTimes(animation: ImportedAnimation | undefined): number[] {
  if (!animation) return [0];
  const times = new Set<number>([0]);
  Object.values(animation.tracks).forEach((track) => track.transforms.forEach((keyframe) => times.add(keyframe.timeSeconds)));
  return [...times].sort((first, second) => first - second);
}

function createPreviewParts(
  candidates: SkinCandidate[],
  animation: ImportedAnimation | undefined,
  time: number,
): PlayerHeadPart[] {
  return candidates.map((candidate) => {
    const track = animation?.tracks[candidate.nodeId];
    const matrix = track?.transforms.filter((keyframe) => keyframe.timeSeconds <= time).at(-1)?.matrix ?? candidate.node.defaultMatrix;
    return createPlayerHeadPart(candidate.partIndex, matrix);
  });
}

function assignmentSummary(candidates: SkinCandidate[], assignments: PartAssignments, artifactCount: number): string {
  const assigned = candidates.filter((candidate) => assignments[candidate.partIndex]).length;
  const skin = candidates.length ? `${assigned}/${candidates.length} skin pieces assigned` : "No skin mapping needed";
  return artifactCount ? `${skin} · ${artifactCount} resource files` : skin;
}

function emptyOptions(): ExportOptions {
  return { minecraftVersion: "26.2", namespace: "emote", name: "", description: "", command_name: "", hide_player: true };
}

function distance(first: { x: number; y: number; z: number }, second: { x: number; y: number; z: number }): number {
  return Math.hypot(first.x - second.x, first.y - second.y, first.z - second.z);
}
