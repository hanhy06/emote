import { lazy, Suspense, useCallback, useMemo, useState, type ChangeEvent } from "react";
import { AssignmentPanel } from "./components/AssignmentPanel";
import { ExportPanel } from "./components/ExportPanel";
import { downloadExport } from "./export/download";
import type { ExportOptions } from "./export/types";
import { IMPORT_ADAPTERS } from "./import/adapters";
import { detectAdapter, importDetected } from "./import/adapterRegistry";
import { conversionErrorMessage } from "./import/errors";
import { countImportedCommands } from "./import/securityWarning";
import type { ImportedAnimation, ImportedNode, ImportedProject, ImportedSkinPart } from "./import/types";
import { createPlayerHeadPart, type PlayerHeadPart } from "./preview/playerHeadPart";
import {
  isPlayerHeadItemStack,
  selectPart,
  selectParts,
  type PartAssignments,
  type PartOrders,
  type SkinPartId,
} from "./preview/skinAssignment";

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
const PartPreview = lazy(() => import("./components/PartPreview")
  .then((module) => ({ default: module.PartPreview })));
const ACCEPTED_EXTENSIONS = [...new Set(IMPORT_ADAPTERS.flatMap((adapter) => adapter.extensions))]
  .map((extension) => `.${extension}`)
  .join(",");
const IMPORT_FORMATS = [
  {
    label: "BD Engine",
    extensions: ".bdengine, .zip",
    description: "The format that works best with the Emote mod. Set the display order and assign each part to the player skin.",
  },
  {
    label: "Animated Java",
    extensions: ".ajblueprint, .json",
    description: "Imports Animated Java animations and generated resources. Player skin assignment is available only for parts that use player heads.",
  },
  {
    label: "GeckoLib",
    extensions: ".bbmodel",
    description: "Imports GeckoLib cube models and lets individual cubes be replaced with player-skin parts.",
  },
] as const;

export function App() {
  const [session, setSession] = useState<ConverterSession | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const updateSession = useCallback((update: (current: ConverterSession) => ConverterSession) => {
    setSession((current) => current ? update(current) : current);
  }, []);
  const setConversionError = useCallback((conversionError: string) => {
    updateSession((current) => ({ ...current, conversionError }));
  }, [updateSession]);

  const project = session?.project ?? null;
  const animationIndex = session?.animationIndex ?? 0;
  const previewFrameIndex = session?.previewFrameIndex ?? 0;
  const assignments = session?.assignments ?? EMPTY_ASSIGNMENTS;
  const orders = session?.orders ?? EMPTY_ORDERS;
  const selectedParts = session?.selectedParts ?? EMPTY_SELECTION;
  const animation = project?.animations[animationIndex];
  const importedCommandCount = useMemo(() => countImportedCommands(project), [project]);
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
        assignments: Object.fromEntries(candidates.map((candidate) => [candidate.nodeId, (candidate.node.suggestedSkin ?? candidate.node.skin)?.part ?? null])),
        orders: Object.fromEntries(candidates.map((candidate) => [candidate.nodeId, (candidate.node.suggestedSkin ?? candidate.node.skin)?.order ?? null])),
        metadata: {
          minecraftVersion: imported.suggestedMinecraftVersion ?? "26.2",
          namespace: imported.suggestedNamespace ?? imported.suggestedMetadata.name,
          playbackMode: "source",
          name: imported.suggestedMetadata.name,
          description: imported.suggestedMetadata.description,
          player: imported.suggestedPlayer,
          additionalMetadata: Object.fromEntries(Object.entries(imported.suggestedMetadata)
            .filter(([key]) => key !== "name" && key !== "description")),
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

  async function handleAnimationDownload(index: number) {
    if (!session) return;
    setConversionError("");
    try {
      const { exportAnimation } = await import("./export/projectExporter");
      downloadExport(exportAnimation(session.project, session.metadata, skinAssignments(), index));
    } catch (reason) {
      const message = conversionErrorMessage(reason, "Conversion failed.");
      setConversionError(message);
    }
  }

  async function handleResourcePackDownload(index: number) {
    if (!session) return;
    setConversionError("");
    try {
      const { exportResourcePack } = await import("./export/resourcePackExporter");
      downloadExport(exportResourcePack(session.project, session.metadata, skinAssignments(), index));
    } catch (reason) {
      const message = conversionErrorMessage(reason, "Resource export failed.");
      setConversionError(message);
    }
  }

  async function handleResourcePackZipMerge(file: File) {
    if (!session) return;
    setConversionError("");
    try {
      const { mergeResourcePackZip } = await import("./export/resourcePackMerger");
      downloadExport(await mergeResourcePackZip(session.project, session.metadata, file));
    } catch (reason) {
      const message = conversionErrorMessage(reason, "Resource pack merge failed.");
      setConversionError(message);
    }
  }

  async function handleResourcePackFolderMerge(files: File[]) {
    if (!session) return;
    setConversionError("");
    try {
      const { mergeResourcePackFolder } = await import("./export/resourcePackMerger");
      downloadExport(await mergeResourcePackFolder(session.project, session.metadata, files));
    } catch (reason) {
      const message = conversionErrorMessage(reason, "Resource pack merge failed.");
      setConversionError(message);
    }
  }

  const handlePartSelect = useCallback((nodeId: string, additive: boolean) => {
    updateSession((current) => ({ ...current, selectedParts: selectPart(current.selectedParts, nodeId, additive) }));
  }, [updateSession]);

  const handlePartsSelect = useCallback((nodeIds: readonly string[], additive: boolean) => {
    updateSession((current) => ({ ...current, selectedParts: selectParts(current.selectedParts, nodeIds, additive) }));
  }, [updateSession]);

  function assignSelected(part: SkinPartId | null) {
    if (selectedParts.size === 0) return;
    updateSession((current) => ({
      ...current,
      assignments: { ...current.assignments, ...Object.fromEntries([...selectedParts].map((nodeId) => [nodeId, part])) },
      orders: {
        ...current.orders,
        ...Object.fromEntries([...selectedParts].map((nodeId) => [nodeId, part ? current.orders[nodeId] ?? 0 : null])),
      },
    }));
  }

  function assignOrder(order: number) {
    const nodeIds = [...selectedParts].filter((nodeId) => assignments[nodeId] != null);
    if (nodeIds.length) updateSession((current) => ({
      ...current,
      orders: { ...current.orders, ...Object.fromEntries(nodeIds.map((nodeId) => [nodeId, order])) },
    }));
  }

  const hasSelectedAssignment = [...selectedParts].some((nodeId) => assignments[nodeId] != null);
  const filePicker = (
    <label className={`file-input${loading ? " disabled" : ""}`}>
      <span>{session ? "Open another file" : "Choose animation file"}</span>
      <input type="file" accept={ACCEPTED_EXTENSIONS} onChange={handleFileChange} disabled={loading} />
    </label>
  );

  return (
    <main className="app">
      <header className="app-header">
        <div>
          <span className="product-label">Emote tools</span>
          <h1>Emote Converter</h1>
          <p>Convert animation projects into server-ready Emote files.</p>
        </div>
        {session && filePicker}
      </header>

      {loading && <p className="message">Reading and validating the file…</p>}
      {error && <p className="message error" role="alert"><strong>Could not open the file.</strong><span>{error}</span></p>}

      {!session && (
        <section className="start-panel" aria-labelledby="start-title">
          <div className="start-copy">
            <span className="step-label">Start a conversion</span>
            <h2 id="start-title">Open an animation project</h2>
            <p>Choose a supported project or an existing Emote JSON file. Files are processed locally in your browser.</p>
            {filePicker}
          </div>
          <div className="start-details">
            <h3>Supported files</h3>
            <ul className="format-list">
              {IMPORT_FORMATS.map((format) => (
                <li key={format.label}>
                  <div className="format-heading"><strong>{format.label}</strong><span>{format.extensions}</span></div>
                  <p>{format.description}</p>
                </li>
              ))}
            </ul>
            <h3>Workflow</h3>
            <ol className="workflow-list">
              <li><span>1</span><p><strong>Open a file</strong><small>The format is detected automatically.</small></p></li>
              <li><span>2</span><p><strong>Review skin parts</strong><small>Assign player skin parts when the project contains them.</small></p></li>
              <li><span>3</span><p><strong>Download the result</strong><small>Export Emote JSON and any generated resources.</small></p></li>
            </ol>
          </div>
        </section>
      )}

      {session && animation && (
        <>
          <section className="project-summary" aria-label="Imported project">
            <div className="project-file">
              <span>Imported file</span>
              <strong>{project.sourceName}</strong>
            </div>
            <dl>
              <div><dt>Format</dt><dd>{session.adapterLabel}</dd></div>
              <div><dt>Nodes</dt><dd>{Object.keys(project.nodes).length}</dd></div>
              <div><dt>Animations</dt><dd>{project.animations.length}</dd></div>
            </dl>
          </section>

          {project.diagnostics.filter((diagnostic) => diagnostic.severity === "warning").map((diagnostic) => (
            <p className="message warning" key={`${diagnostic.code}:${diagnostic.sourcePath ?? ""}`}>{diagnostic.message}</p>
          ))}

          {importedCommandCount > 0 && (
            <p className="message warning" role="alert">
              <strong>Review event commands before installing this animation.</strong>
              <span>
                This project contains {importedCommandCount} {importedCommandCount === 1 ? "command" : "commands"} that will run with server operator permission. Only install animations from sources you trust.
              </span>
            </p>
          )}

          <section className="workspace" aria-labelledby="workspace-title">
            <div className="section-heading">
              <div>
                <span className="step-label">Step 2</span>
                <h2 id="workspace-title">{skinCandidates.length > 0 ? "Review player skin parts" : "Review imported animation"}</h2>
                <p>{skinCandidates.length > 0
                  ? "Select parts in the preview, then assign each one to a player body part."
                   : "This file does not contain skin-compatible parts, so no skin assignment is needed."}</p>
              </div>
              <div className="preview-controls">
                {skinCandidates.length > 0 && (
                  <label className="frame-slider">
                    <span>Preview frame</span>
                    <input type="range" min="0" max={previewTicks.length} step="1" value={previewFrameIndex} onChange={(event) => {
                      updateSession((current) => ({ ...current, previewFrameIndex: Number(event.target.value), selectedParts: new Set() }));
                    }} />
                    <output>{previewTick === null ? "Create pose" : `${previewTick} tick`}</output>
                  </label>
                )}
                {project.animations.length > 1 && (
                  <label className="animation-select"><span>Animation</span><select value={animationIndex} onChange={(event) => {
                    updateSession((current) => ({ ...current, animationIndex: Number(event.target.value), previewFrameIndex: 0, selectedParts: new Set() }));
                  }}>
                    {project.animations.map((item, index) => <option value={index} key={item.id}>{item.name}</option>)}
                  </select></label>
                )}
              </div>
            </div>
            {skinCandidates.length > 0 ? (
              <div className="editor">
                <Suspense fallback={<div className="preview-loading" role="status">Loading 3D preview…</div>}>
                  <PartPreview
                    key={project.sourceName}
                    parts={previewParts}
                    assignments={assignments}
                    selectedParts={selectedParts}
                    onSelectPart={handlePartSelect}
                    onSelectParts={handlePartsSelect}
                  />
                </Suspense>
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
              </div>
            ) : (
              <div className="no-skin-parts"><strong>Ready to export</strong><span>No player skin assignments are required.</span></div>
            )}
          </section>

          <ExportPanel
            metadata={session.metadata}
            assignmentSummary={assignmentSummary(skinCandidates, assignments, project.resources.size)}
            animations={project.animations.map((item) => ({ label: item.name, detail: item.id }))}
            hasResources={project.resources.size > 0}
            error={session.conversionError}
            onMetadataChange={(metadata) => updateSession((current) => ({ ...current, metadata }))}
            onDownloadAnimation={handleAnimationDownload}
            onDownloadResourcePack={handleResourcePackDownload}
            onMergeResourcePackZip={handleResourcePackZipMerge}
            onMergeResourcePackFolder={handleResourcePackFolderMerge}
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
    let isPlayerHead = false;
    try {
      isPlayerHead = isPlayerHeadItemStack(node.itemStackSnbt);
    } catch {
      isPlayerHead = false;
    }
    if (!isPlayerHead && !node.playerHeadConversion) return [];
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
    const sourceMatrix = tick === null
      ? candidate.node.defaultMatrix
      : animation?.tracks[candidate.nodeId]?.transforms.filter((keyframe) => keyframe.tick <= tick).at(-1)?.matrix
        ?? candidate.node.defaultMatrix;
    return createPlayerHeadPart(candidate.nodeId, candidate.partIndex, sourceMatrix, candidate.node.playerHeadConversion?.matrix);
  });
}

function assignmentSummary(candidates: SkinCandidate[], assignments: PartAssignments, resourceCount: number): string {
  const assigned = candidates.filter((candidate) => assignments[candidate.nodeId]).length;
  const skin = candidates.length ? `${assigned}/${candidates.length} skin parts assigned` : "No skin assignment needed";
  return resourceCount ? `${skin} · ${resourceCount} resource files` : skin;
}
