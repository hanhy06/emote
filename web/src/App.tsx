import type { TargetedEvent } from "preact";
import { lazy, Suspense } from "preact/compat";
import { useCallback, useMemo, useRef, useState } from "preact/hooks";
import { AssignmentPanel } from "./components/AssignmentPanel";
import { CommandPanel } from "./components/CommandPanel";
import { ExportPanel } from "./components/ExportPanel";
import { SettingsPanel } from "./components/SettingsPanel";
import { addFrameCommand, removeFrameCommand, updateFrameCommand } from "./components/frameCommands";
import {
  assignmentSummary,
  assignSessionOrder,
  assignSessionSkinPart,
  assignSessionSpace,
  buildSkinAssignments,
  createConverterSession,
  createPreviewParts,
  EMPTY_ASSIGNMENTS,
  EMPTY_ORDERS,
  EMPTY_SELECTION,
  findSkinCandidates,
  type ConverterSession,
} from "./converterSession";
import { downloadExport } from "./export/download";
import type { ExportResult } from "./export/types";
import type { NodeSpace } from "./format/emoteAnimation";
import { IMPORT_ADAPTERS } from "./import/adapters";
import { detectAdapter, importDetected } from "./import/adapterRegistry";
import { conversionErrorMessage } from "./import/errors";
import { countImportedCommands } from "./import/securityWarning";
import type { ImportedAnimation } from "./import/types";
import { convertSequenceInput } from "./import/emoteJson/sequenceJsonConverter";
import {
  selectPart,
  selectParts,
  type SkinPartId,
} from "./preview/skinAssignment";
const PartPreview = lazy(() => import("./components/PartPreview"));
const ACCEPTED_EXTENSIONS = [...new Set(IMPORT_ADAPTERS.flatMap((adapter) => adapter.extensions))]
  .map((extension) => `.${extension}`)
  .join(",");
const IMPORT_FORMATS = [
  {
    label: "BD Engine",
    extensions: ".bdengine, .zip",
    description: "Imports BD Engine projects and datapacks with animations, display nodes, commands, and player-skin parts.",
  },
  {
    label: "GeckoLib",
    extensions: ".bbmodel",
    description: "Imports GeckoLib cube models, detects standard player-model cubes, and lets them be assigned to player-skin parts.",
  },
  {
    label: "Animated Java",
    extensions: ".ajblueprint, .json",
    description: "Imports native cube rigs and plugin blueprints, detects player-model cubes, and supports player-skin assignment.",
  },
] as const;

export function App() {
  const [session, setSession] = useState<ConverterSession | null>(null);
  const [error, setError] = useState("");
  const [busyMessage, setBusyMessage] = useState<string | null>(null);
  const [page, setPage] = useState<0 | 1 | 2>(0);
  const busyRef = useRef(false);
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
  const spaces = session?.spaces ?? {};
  const selectedParts = session?.selectedParts ?? EMPTY_SELECTION;
  const animation = project?.animations[animationIndex];
  const importedCommandCount = useMemo(() => countImportedCommands(project), [project]);
  const skinCandidates = useMemo(() => findSkinCandidates(project), [project]);
  const previewTick = previewFrameIndex === 0
    ? null
    : Math.min(previewFrameIndex - 1, Math.max(0, (animation?.durationTicks ?? 1) - 1));
  const previewParts = useMemo(
    () => createPreviewParts(skinCandidates, animation, previewTick),
    [animation, previewTick, skinCandidates],
  );

  async function handleFileChange(event: TargetedEvent<HTMLInputElement>) {
    const inputElement = event.currentTarget;
    const file = inputElement.files?.[0];
    if (!file) return;
    if (busyRef.current) {
      inputElement.value = "";
      return;
    }
    busyRef.current = true;
    setBusyMessage("Opening animation project");
    setError("");
    setSession(null);
    try {
      await showLoadingScreen();
      const input = { name: file.name, bytes: new Uint8Array(await file.arrayBuffer()) };
      const sequence = convertSequenceInput(input);
      if (sequence) {
        downloadExport({
          blob: new Blob([JSON.stringify(sequence)], { type: "application/json" }),
          fileName: file.name,
        });
        return;
      }
      const detected = await detectAdapter(IMPORT_ADAPTERS, input);
      const imported = await importDetected(detected, input);
      setSession(createConverterSession(imported, detected.adapter.label));
      setPage(0);
    } catch (reason) {
      setError(conversionErrorMessage(reason, "Could not import the file."));
    } finally {
      busyRef.current = false;
      setBusyMessage(null);
      inputElement.value = "";
    }
  }

  async function runExport(action: () => Promise<ExportResult>, fallbackMessage: string, progressMessage: string) {
    if (busyRef.current) return;
    busyRef.current = true;
    setConversionError("");
    setBusyMessage(progressMessage);

    try {
      await showLoadingScreen();
      downloadExport(await action());
    } catch (reason) {
      setConversionError(conversionErrorMessage(reason, fallbackMessage));
    } finally {
      busyRef.current = false;
      setBusyMessage(null);
    }
  }

  async function handleAnimationDownload(index: number) {
    if (!session) return;

    await runExport(async () => {
      const { exportAnimation } = await import("./export/projectExporter");
      return exportAnimation(session.project, session.metadata, buildSkinAssignments(session, skinCandidates), index, session.spaces);
    }, "Conversion failed.", "Creating animation file");
  }

  async function handleAnimationBundle(includeSequence: boolean) {
    if (!session) return;
    await runExport(async () => {
      const { exportAnimationBundle } = await import("./export/projectExporter");
      return exportAnimationBundle(session.project, session.metadata, buildSkinAssignments(session, skinCandidates), includeSequence, session.spaces);
    }, "Bundle export failed.", includeSequence ? "Creating sequence ZIP" : "Creating animation ZIP");
  }

  async function handleResourcePackDownload(index: number) {
    if (!session) return;

    await runExport(async () => {
      const { exportResourcePack } = await import("./export/resourcePackExporter");
      return exportResourcePack(session.project, session.metadata, buildSkinAssignments(session, skinCandidates), index, session.spaces);
    }, "Resource export failed.", "Creating resource pack");
  }

  async function handleResourcePackZipMerge(file: File) {
    if (!session) return;

    await runExport(async () => {
      const { mergeResourcePackZip } = await import("./export/resourcePackMerger");
      return mergeResourcePackZip(session.project, session.metadata, file);
    }, "Resource pack merge failed.", "Merging resource pack");
  }

  async function handleResourcePackFolderMerge(files: File[]) {
    if (!session) return;

    await runExport(async () => {
      const { mergeResourcePackFolder } = await import("./export/resourcePackMerger");
      return mergeResourcePackFolder(session.project, session.metadata, files);
    }, "Resource pack merge failed.", "Merging resource pack");
  }

  const handlePartSelect = useCallback((nodeId: string, additive: boolean) => {
    updateSession((current) => ({ ...current, selectedParts: selectPart(current.selectedParts, nodeId, additive) }));
  }, [updateSession]);

  const handlePartsSelect = useCallback((nodeIds: readonly string[], additive: boolean) => {
    updateSession((current) => ({ ...current, selectedParts: selectParts(current.selectedParts, nodeIds, additive) }));
  }, [updateSession]);

  function assignSelected(part: SkinPartId | null) {
    if (selectedParts.size === 0) return;
    updateSession((current) => assignSessionSkinPart(current, skinCandidates, part));
  }

  function assignSelectedSpace(space: NodeSpace) {
    if (selectedParts.size === 0) return;
    updateSession((current) => assignSessionSpace(current, skinCandidates, space));
  }

  function assignOrder(order: number) {
    updateSession((current) => assignSessionOrder(current, skinCandidates, order));
  }

  function editCurrentAnimation(edit: (current: ImportedAnimation) => ImportedAnimation) {
    updateSession((current) => ({
      ...current,
      project: {
        ...current.project,
        animations: current.project.animations.map((item, index) => index === current.animationIndex ? edit(item) : item),
      },
    }));
  }

  function addCommandAtPreviewTick() {
    if (previewTick !== null) editCurrentAnimation((current) => addFrameCommand(current, previewTick));
  }

  function changeFrameCommand(eventIndex: number, commandIndex: number, command: string) {
    editCurrentAnimation((current) => updateFrameCommand(current, eventIndex, commandIndex, command));
  }

  function deleteFrameCommand(eventIndex: number, commandIndex: number) {
    editCurrentAnimation((current) => removeFrameCommand(current, eventIndex, commandIndex));
  }

  const hasSelectedAssignment = [...selectedParts].some((nodeId) => assignments[nodeId] != null);
  const busy = busyMessage !== null;
  const filePicker = (
    <label className={`file-input${busy ? " disabled" : ""}`}>
      <span>{session ? "Open another file" : "Choose animation file"}</span>
      <input type="file" accept={ACCEPTED_EXTENSIONS} onChange={handleFileChange} disabled={busy} />
    </label>
  );

  return (
    <main className="app" aria-busy={busy}>
      {busyMessage && (
        <div className="loading-overlay" role="status" aria-live="polite">
          <div className="loading-dialog">
            <span className="loading-spinner" aria-hidden="true" />
            <span className="loading-copy">
              <strong>{busyMessage}</strong>
              <small>Large files may take a moment. Keep this tab open.</small>
            </span>
          </div>
        </div>
      )}
      <header className="app-header">
        <div>
          <span className="product-label">Emote tools</span>
          <h1>Emote Converter</h1>
          <p>Convert BD Engine, GeckoLib, and Animated Java projects into server-ready Emote files with player-skin support.</p>
        </div>
        {session && filePicker}
      </header>

      {error && <p className="message error" role="alert"><strong>Could not open the file.</strong><span>{error}</span></p>}

      {!session && (
        <section className="start-panel" aria-labelledby="start-title">
          <div className="start-copy">
            <span className="step-label">Start a conversion</span>
            <h2 id="start-title">Open an animation project</h2>
            <p>Open any supported model project or an existing Emote JSON file. Models, animations, and skin parts are processed locally in your browser.</p>
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

          <nav className="workflow-pages" aria-label="Conversion pages">
            {(["Rigging & commands", "Metadata, settings & other", "Output"] as const).map((label, index) => (
              <button className={page === index ? "active" : ""} type="button" onClick={() => setPage(index as 0 | 1 | 2)} key={label}>
                <span>{index + 1}</span>{label}
              </button>
            ))}
          </nav>

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

          {page === 0 && <section className="workspace page-panel" aria-labelledby="workspace-title">
            <div className="section-heading">
              <div>
                <span className="step-label">Page 1</span>
                <h2 id="workspace-title">{skinCandidates.length > 0 ? "Review player skin parts" : "Review imported animation"}</h2>
                <p>{skinCandidates.length > 0
                  ? "Select parts in the preview, then assign each one to a player body part."
                   : "This file does not contain skin-compatible parts, so no skin assignment is needed."}</p>
              </div>
              <div className="preview-controls">
                {skinCandidates.length > 0 && (
                  <label className="frame-slider">
                    <span>Preview frame</span>
                    <input type="range" min="0" max={animation.durationTicks} step="1" value={previewFrameIndex} onChange={(event) => {
                      updateSession((current) => ({ ...current, previewFrameIndex: Number(event.currentTarget.value), selectedParts: new Set() }));
                    }} />
                    <output>{previewTick === null ? "Create pose" : `${previewTick} tick`}</output>
                  </label>
                )}
                {project.animations.length > 1 && (
                  <label className="animation-select"><span>Animation</span><select value={animationIndex} onChange={(event) => {
                    updateSession((current) => ({ ...current, animationIndex: Number(event.currentTarget.value), previewFrameIndex: 0, selectedParts: new Set() }));
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
                  spaces={spaces}
                  selectedParts={selectedParts}
                  hasSelectedAssignment={hasSelectedAssignment}
                  onAssignPart={assignSelected}
                  onAssignOrder={assignOrder}
                  onAssignSpace={assignSelectedSpace}
                  onSelectPart={handlePartSelect}
                />
              </div>
            ) : (
              <div className="no-skin-parts"><strong>Ready to export</strong><span>No player skin assignments are required.</span></div>
            )}
            <CommandPanel
              animation={animation}
              tick={previewTick}
              disabled={busy}
              onAdd={addCommandAtPreviewTick}
              onChange={changeFrameCommand}
              onRemove={deleteFrameCommand}
            />
          </section>}

          {page === 1 && <SettingsPanel
            metadata={session.metadata}
            disabled={busy}
            onMetadataChange={(metadata) => updateSession((current) => ({ ...current, metadata }))}
          />}

          {page === 2 && <ExportPanel
            assignmentSummary={assignmentSummary(skinCandidates, assignments, project.resources.size)}
            animations={project.animations.map((item) => ({ label: item.name, detail: item.id }))}
            hasResources={project.resources.size > 0}
            error={session.conversionError}
            disabled={busy}
            onDownloadAnimation={handleAnimationDownload}
            onDownloadAllAnimations={() => handleAnimationBundle(false)}
            onDownloadSequence={() => handleAnimationBundle(true)}
            onDownloadResourcePack={handleResourcePackDownload}
            onMergeResourcePackZip={handleResourcePackZipMerge}
            onMergeResourcePackFolder={handleResourcePackFolderMerge}
          />}
        </>
      )}
    </main>
  );
}

function showLoadingScreen(): Promise<void> {
  return new Promise((resolve) => {
    let complete = false;
    const finish = () => {
      if (complete) return;
      complete = true;
      clearTimeout(fallback);
      resolve();
    };
    const fallback = setTimeout(finish, 100);
    requestAnimationFrame(() => requestAnimationFrame(finish));
  });
}
