import { useCallback, useMemo, useState, type ChangeEvent } from "react";
import { AssignmentPanel } from "./components/AssignmentPanel";
import { ExportPanel } from "./components/ExportPanel";
import { PartPreview } from "./components/PartPreview";
import { downloadExport, exportAnimation, exportResource, type ExportOptions } from "./export/projectExporter";
import { IMPORT_ADAPTERS } from "./import/adapters";
import { detectAdapter, importDetected } from "./import/adapterRegistry";
import { conversionErrorMessage } from "./import/errors";
import type { ImportedAnimation, ImportedNode, ImportedProject, ImportedSkinPart } from "./import/types";
import { createPlayerHeadPart, type PlayerHeadPart } from "./preview/playerHeadPart";
import { selectPart, type PartAssignments, type PartOrders, type SkinPartId } from "./preview/skinMapping";
import { readSnbtStringField } from "./format/snbt";

interface SkinCandidate {
  nodeId: string;
  partIndex: number;
  node: Extract<ImportedNode, { type: "item_display" }>;
}

interface ConverterSession {
  project: ImportedProject;
  adapterLabel: string;
  animationIndex: number;
  previewFrameIndex: number;
  assignments: PartAssignments;
  orders: PartOrders;
  selectedParts: Set<string>;
  metadata: ExportOptions;
  conversionError: string;
}

const EMPTY_ASSIGNMENTS: PartAssignments = {};
const EMPTY_ORDERS: PartOrders = {};
const EMPTY_SELECTION = new Set<string>();
const ACCEPTED_EXTENSIONS = [...new Set(IMPORT_ADAPTERS.flatMap((adapter) => adapter.extensions))]
  .map((extension) => `.${extension}`)
  .join(",");

export function App() {
  const [session, setSession] = useState<ConverterSession | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [explodedPreview, setExplodedPreview] = useState(false);

  const project = session?.project ?? null;
  const animationIndex = session?.animationIndex ?? 0;
  const previewFrameIndex = session?.previewFrameIndex ?? 0;
  const assignments = session?.assignments ?? EMPTY_ASSIGNMENTS;
  const orders = session?.orders ?? EMPTY_ORDERS;
  const selectedParts = session?.selectedParts ?? EMPTY_SELECTION;
  const animation = project?.animations[animationIndex];
  const skinCandidates = useMemo(() => findSkinCandidates(project), [project]);
  const previewTicks = useMemo(() => animationTicks(animation), [animation]);
  const previewTick = previewFrameIndex === 0
    ? null
    : previewTicks[Math.min(previewFrameIndex - 1, previewTicks.length - 1)] ?? 0;
  const previewParts = useMemo(
    () => createPreviewParts(skinCandidates, animation, previewTick),
    [animation, previewTick, skinCandidates],
  );

  async function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    setLoading(true);
    setError("");
    setSession(null);
    setExplodedPreview(false);
    try {
      const input = { name: file.name, bytes: new Uint8Array(await file.arrayBuffer()) };
      const detected = await detectAdapter(IMPORT_ADAPTERS, input);
      const imported = await importDetected(detected, input);
      const candidates = findSkinCandidates(imported);
      setSession({
        project: imported,
        adapterLabel: detected.adapter.label,
        animationIndex: 0,
        previewFrameIndex: 0,
        selectedParts: new Set(),
        assignments: Object.fromEntries(candidates.map((candidate) => [candidate.nodeId, candidate.node.skin?.part ?? null])),
        orders: Object.fromEntries(candidates.map((candidate) => [candidate.nodeId, candidate.node.skin?.order ?? null])),
        metadata: {
          minecraftVersion: imported.suggestedMinecraftVersion ?? "26.2",
          namespace: imported.suggestedNamespace ?? imported.suggestedMetadata.name,
          playbackMode: "source",
          ...imported.suggestedMetadata,
        },
        conversionError: "",
      });
    } catch (reason) {
      setError(conversionErrorMessage(reason, "Could not import the file."));
    } finally {
      setLoading(false);
      event.target.value = "";
    }
  }

  function skinAssignments(): Record<string, ImportedSkinPart | null> {
    const skins: Record<string, ImportedSkinPart | null> = {};
    for (const candidate of skinCandidates) {
      const part = assignments[candidate.nodeId];
      skins[candidate.nodeId] = part ? { part, order: orders[candidate.nodeId] ?? 0 } : null;
    }
    return skins;
  }

  function handleAnimationDownload(index: number) {
    if (!session) return;
    setSession((current) => current ? { ...current, conversionError: "" } : current);
    try {
      downloadExport(exportAnimation(session.project, session.metadata, skinAssignments(), index));
    } catch (reason) {
      const message = conversionErrorMessage(reason, "Conversion failed.");
      setSession((current) => current ? { ...current, conversionError: message } : current);
    }
  }

  function handleResourceDownload(index: number) {
    if (!session) return;
    setSession((current) => current ? { ...current, conversionError: "" } : current);
    try {
      downloadExport(exportResource(session.project, session.metadata.minecraftVersion, index));
    } catch (reason) {
      const message = conversionErrorMessage(reason, "Resource export failed.");
      setSession((current) => current ? { ...current, conversionError: message } : current);
    }
  }

  const handlePartSelect = useCallback((nodeId: string, additive: boolean) => {
    setSession((current) => current ? { ...current, selectedParts: selectPart(current.selectedParts, nodeId, additive) } : current);
  }, []);

  function assignSelected(part: SkinPartId | null) {
    if (selectedParts.size === 0) return;
    setSession((current) => current ? {
      ...current,
      assignments: { ...current.assignments, ...Object.fromEntries([...selectedParts].map((nodeId) => [nodeId, part])) },
      orders: {
        ...current.orders,
        ...Object.fromEntries([...selectedParts].map((nodeId) => [nodeId, part ? current.orders[nodeId] ?? 0 : null])),
      },
    } : current);
  }

  function assignOrder(order: number) {
    const nodeIds = [...selectedParts].filter((nodeId) => assignments[nodeId] != null);
    if (nodeIds.length) setSession((current) => current ? {
      ...current,
      orders: { ...current.orders, ...Object.fromEntries(nodeIds.map((nodeId) => [nodeId, order])) },
    } : current);
  }

  const hasSelectedAssignment = [...selectedParts].some((nodeId) => assignments[nodeId] != null);

  return (
    <main className="app">
      <header>
        <h1>Emote JSON Converter</h1>
        <label className="file-input">Animation file<input type="file" accept={ACCEPTED_EXTENSIONS} onChange={handleFileChange} disabled={loading} /></label>
      </header>

      {loading && <p>Reading file…</p>}
      {error && <p className="error" role="alert">{error}</p>}

      {session && animation && (
        <>
          <div className="status">
            <strong>{project.sourceName}</strong>
            <span>{session.adapterLabel}</span>
            <span>{Object.keys(project.nodes).length} nodes</span>
            {skinCandidates.length > 0 && (
              <>
                <label className="frame-slider">
                  <span>Preview</span>
                  <input type="range" min="0" max={previewTicks.length} step="1" value={previewFrameIndex} onChange={(event) => {
                    setSession((current) => current ? { ...current, previewFrameIndex: Number(event.target.value), selectedParts: new Set() } : current);
                  }} />
                  <output>{previewTick === null ? "Create" : `${previewTick} tick`}</output>
                </label>
                <label className="explode-toggle">
                  <input type="checkbox" checked={explodedPreview} onChange={(event) => setExplodedPreview(event.target.checked)} />
                  Explode
                </label>
              </>
            )}
            {project.animations.length > 1 && (
              <select value={animationIndex} onChange={(event) => {
                setSession((current) => current ? { ...current, animationIndex: Number(event.target.value), previewFrameIndex: 0, selectedParts: new Set() } : current);
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
              <PartPreview parts={previewParts} assignments={assignments} selectedParts={selectedParts} exploded={explodedPreview} onSelectPart={handlePartSelect} />
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
            metadata={session.metadata}
            assignmentSummary={assignmentSummary(skinCandidates, assignments, project.artifacts.size)}
            animations={project.animations.map((item) => ({ label: item.name, detail: item.id }))}
            resources={[...project.artifacts.keys()].map((path) => ({ label: path.split("/").at(-1) ?? path, detail: path }))}
            error={session.conversionError}
            onMetadataChange={(metadata) => setSession((current) => current ? { ...current, metadata } : current)}
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
    if (node.type !== "item_display") return [];
    try {
      if (readSnbtStringField(node.itemStackSnbt, "id") !== "minecraft:player_head") return [];
    } catch {
      return [];
    }
    return [{ nodeId, partIndex: 0, node }];
  }).map((candidate, partIndex) => ({ ...candidate, partIndex }));
}

function animationTicks(animation: ImportedAnimation | undefined): number[] {
  if (!animation) return [0];
  const ticks = new Set<number>([0]);
  Object.values(animation.tracks).forEach((track) => track.transforms.forEach((keyframe) => ticks.add(keyframe.tick)));
  return [...ticks].sort((first, second) => first - second);
}

function createPreviewParts(
  candidates: SkinCandidate[],
  animation: ImportedAnimation | undefined,
  tick: number | null,
): PlayerHeadPart[] {
  return candidates.map((candidate) => {
    if (tick === null) return createPlayerHeadPart(candidate.nodeId, candidate.partIndex, candidate.node.defaultMatrix);
    const track = animation?.tracks[candidate.nodeId];
    const matrix = track?.transforms.filter((keyframe) => keyframe.tick <= tick).at(-1)?.matrix ?? candidate.node.defaultMatrix;
    return createPlayerHeadPart(candidate.nodeId, candidate.partIndex, matrix);
  });
}

function assignmentSummary(candidates: SkinCandidate[], assignments: PartAssignments, artifactCount: number): string {
  const assigned = candidates.filter((candidate) => assignments[candidate.nodeId]).length;
  const skin = candidates.length ? `${assigned}/${candidates.length} skin pieces assigned` : "No skin mapping needed";
  return artifactCount ? `${skin} · ${artifactCount} resource files` : skin;
}
