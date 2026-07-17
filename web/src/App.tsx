import { useCallback, useMemo, useState, type ChangeEvent } from "react";
import { AssignmentPanel } from "./components/AssignmentPanel";
import { ExportPanel } from "./components/ExportPanel";
import { PartPreview } from "./components/PartPreview";
import { downloadExport, exportAnimation, exportResource, type ExportOptions } from "./export/projectExporter";
import { IMPORT_ADAPTERS } from "./import/adapters";
import { detectAdapter, importDetected } from "./import/adapterRegistry";
import { conversionErrorMessage } from "./import/errors";
import { countImportedCommands } from "./import/securityWarning";
import type { ImportedAnimation, ImportedNode, ImportedProject, ImportedSkinPart } from "./import/types";
import { createPlayerHeadPart, type PlayerHeadPart } from "./preview/playerHeadPart";
import { selectPart, selectParts, type PartAssignments, type PartOrders, type SkinPartId } from "./preview/skinMapping";
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
const IMPORT_FORMATS = IMPORT_ADAPTERS.map((adapter) => ({
  label: adapter.label,
  extensions: adapter.extensions.map((extension) => `.${extension}`).join(", "),
}));

export function App() {
  const [session, setSession] = useState<ConverterSession | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

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

  const handlePartsSelect = useCallback((nodeIds: readonly string[], additive: boolean) => {
    setSession((current) => current ? { ...current, selectedParts: selectParts(current.selectedParts, nodeIds, additive) } : current);
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
                <li key={format.label}><strong>{format.label}</strong><span>{format.extensions}</span></li>
              ))}
            </ul>
            <h3>Workflow</h3>
            <ol className="workflow-list">
              <li><span>1</span><p><strong>Open a file</strong><small>The format is detected automatically.</small></p></li>
              <li><span>2</span><p><strong>Review skin parts</strong><small>Assign player skin pieces when the project contains them.</small></p></li>
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
                  ? "Select pieces in the preview, then assign each one to a player body part."
                  : "This file does not contain player-head pieces, so no skin mapping is needed."}</p>
              </div>
              <div className="preview-controls">
                {skinCandidates.length > 0 && (
                  <label className="frame-slider">
                    <span>Preview frame</span>
                    <input type="range" min="0" max={previewTicks.length} step="1" value={previewFrameIndex} onChange={(event) => {
                      setSession((current) => current ? { ...current, previewFrameIndex: Number(event.target.value), selectedParts: new Set() } : current);
                    }} />
                    <output>{previewTick === null ? "Create pose" : `${previewTick} tick`}</output>
                  </label>
                )}
                {project.animations.length > 1 && (
                  <label className="animation-select"><span>Animation</span><select value={animationIndex} onChange={(event) => {
                    setSession((current) => current ? { ...current, animationIndex: Number(event.target.value), previewFrameIndex: 0, selectedParts: new Set() } : current);
                  }}>
                    {project.animations.map((item, index) => <option value={index} key={item.id}>{item.name}</option>)}
                  </select></label>
                )}
              </div>
            </div>
            {skinCandidates.length > 0 ? (
              <div className="editor">
                <PartPreview
                  key={project.sourceName}
                  parts={previewParts}
                  assignments={assignments}
                  selectedParts={selectedParts}
                  onSelectPart={handlePartSelect}
                  onSelectParts={handlePartsSelect}
                />
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
