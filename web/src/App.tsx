import type { TargetedEvent } from "preact";
import { lazy, Suspense } from "preact/compat";
import { useCallback, useMemo, useReducer } from "preact/hooks";
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
  createConverterSession,
  createPreviewParts,
  EMPTY_SELECTION,
  findSkinCandidates,
  selectSessionAnimation,
  updateSessionAnimation,
  updateSessionAnimationOptions,
  type ConverterSession,
} from "./converterSession";
import { documentNodeSpaces, documentPartAssignments, documentPartOrders } from "./domain/conversionDocument";
import { downloadExport, downloadExports } from "./export/download";
import type { ExportResult } from "./export/types";
import type { NodeSpace } from "./format/emoteAnimation";
import { IMPORT_ADAPTERS } from "./import/adapters";
import { detectAdapter, importDetected } from "./import/adapterRegistry";
import { isImportedSequence } from "./import/adapter";
import { conversionErrorMessage, groupConversionWarnings } from "./foundation/diagnostics";
import { countImportedCommands } from "./import/securityWarning";
import type { ImportedAnimation } from "./domain/conversionSeed";
import { animationAvailability } from "./domain/conversionSeed";
import {
  selectPart,
  selectParts,
  type SkinPartId,
} from "./preview/skinAssignment";
import { INITIAL_WORKSPACE, workspaceReducer, type WorkspacePage } from "./workspace";
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
  const [workspace, dispatch] = useReducer(workspaceReducer, INITIAL_WORKSPACE);
  const { session, page, openError, exportError, operation } = workspace;
  const busy = operation.type !== "idle";
  const busyMessage = operation.type === "idle" ? null : operation.message;
  const updateSession = useCallback((update: (current: ConverterSession) => ConverterSession) => {
    dispatch({ type: "update_session", update });
  }, []);

  const project = session?.document ?? null;
  const animationIndex = session?.animationIndex ?? 0;
  const previewFrameIndex = session?.previewFrameIndex ?? 0;
  const assignments = useMemo(() => project ? documentPartAssignments(project) : {}, [project]);
  const orders = useMemo(() => project ? documentPartOrders(project) : {}, [project]);
  const spaces = useMemo(() => project ? documentNodeSpaces(project) : {}, [project]);
  const selectedParts = session?.selectedParts ?? EMPTY_SELECTION;
  const animation = project?.animations[animationIndex]?.source;
  const availability = animation ? animationAvailability(animation) : null;
  const previewDurationTicks = animation?.preview?.durationTicks ?? animation?.durationTicks ?? 0;
  const animationOptions = project?.animations[animationIndex]?.output;
  const importedCommandCount = useMemo(() => countImportedCommands(project), [project]);
  const warningGroups = useMemo(() => groupConversionWarnings(project?.diagnostics ?? []), [project]);
  const skinCandidates = useMemo(() => findSkinCandidates(project), [project]);
  const previewTick = availability?.preview !== "full" || previewFrameIndex === 0
    ? null
    : Math.min(previewFrameIndex - 1, Math.max(0, previewDurationTicks));
  const previewParts = useMemo(
    () => createPreviewParts(skinCandidates, animation, previewTick),
    [animation, previewTick, skinCandidates],
  );

  async function handleFileChange(event: TargetedEvent<HTMLInputElement>) {
    const inputElement = event.currentTarget;
    const file = inputElement.files?.[0];
    if (!file) return;
    if (busy) {
      inputElement.value = "";
      return;
    }
    dispatch({ type: "begin_open", message: "Opening animation project" });
    try {
      await showLoadingScreen();
      const input = { name: file.name, bytes: new Uint8Array(await file.arrayBuffer()) };
      const detected = await detectAdapter(IMPORT_ADAPTERS, input);
      const imported = await importDetected(detected, input);
      if (isImportedSequence(imported)) {
        downloadExport({
          blob: new Blob([JSON.stringify(imported.sequence)], { type: "application/json" }),
          fileName: imported.fileName,
        });
        return;
      }
      dispatch({ type: "finish_open", session: createConverterSession(imported, detected.adapter.label) });
    } catch (reason) {
      dispatch({ type: "fail_open", message: conversionErrorMessage(reason, "Could not import the file.") });
    } finally {
      dispatch({ type: "finish_operation" });
      inputElement.value = "";
    }
  }

  async function runExport(action: () => Promise<ExportResult>, fallbackMessage: string, progressMessage: string) {
    if (busy) return;
    dispatch({ type: "begin_export", message: progressMessage });

    try {
      await showLoadingScreen();
      downloadExport(await action());
    } catch (reason) {
      dispatch({ type: "fail_export", message: conversionErrorMessage(reason, fallbackMessage) });
    } finally {
      dispatch({ type: "finish_operation" });
    }
  }

  async function handleAnimationDownload(index: number) {
    if (!session) return;

    await runExport(async () => {
      const { exportDocumentAnimation } = await import("./export/projectExporter");
      return exportDocumentAnimation(session.document, index);
    }, "Conversion failed.", "Creating animation file");
  }

  async function handleAnimationBundle(includeSequence: boolean) {
    if (!session) return;
    if (busy) return;
    dispatch({ type: "begin_export", message: includeSequence ? "Creating sequence files" : "Creating animation files" });

    try {
      await showLoadingScreen();
      const { exportDocumentAnimationFiles } = await import("./export/projectExporter");
      downloadExports(exportDocumentAnimationFiles(session.document, includeSequence));
    } catch (reason) {
      dispatch({ type: "fail_export", message: conversionErrorMessage(reason, "File export failed.") });
    } finally {
      dispatch({ type: "finish_operation" });
    }
  }

  async function handleResourcePackDownload(index: number) {
    if (!session) return;

    await runExport(async () => {
      const { exportDocumentResourcePack } = await import("./export/resourcePackExporter");
      return exportDocumentResourcePack(session.document, index);
    }, "Resource export failed.", "Creating resource pack");
  }

  async function handleResourcePackZipMerge(file: File) {
    if (!session) return;

    await runExport(async () => {
      const { mergeDocumentResourcePackZip } = await import("./export/resourcePackMerger");
      return mergeDocumentResourcePackZip(session.document, file);
    }, "Resource pack merge failed.", "Merging resource pack");
  }

  async function handleResourcePackFolderMerge(files: File[]) {
    if (!session) return;

    await runExport(async () => {
      const { mergeDocumentResourcePackFolder } = await import("./export/resourcePackMerger");
      return mergeDocumentResourcePackFolder(session.document, files);
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
    updateSession((current) => assignSessionSkinPart(current, part));
  }

  function assignSelectedSpace(space: NodeSpace) {
    if (selectedParts.size === 0) return;
    updateSession((current) => assignSessionSpace(current, space));
  }

  function assignOrder(order: number) {
    updateSession((current) => assignSessionOrder(current, order));
  }

  function editCurrentAnimation(edit: (current: ImportedAnimation) => ImportedAnimation) {
    updateSession((current) => updateSessionAnimation(current, edit));
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

      {openError && <p className="message error" role="alert"><strong>Could not open the file.</strong><span>{openError}</span></p>}

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
              <strong>{project.origin.sourceName}</strong>
            </div>
            <label className="project-animation">
              <span>Animation</span>
              <select value={animationIndex} disabled={project.animations.length === 1} onChange={(event) => {
                const nextIndex = Number(event.currentTarget.value);
                updateSession((current) => selectSessionAnimation(current, nextIndex));
                const nextAnimation = project.animations[nextIndex]?.source;
                if (nextAnimation && animationAvailability(nextAnimation).preview === "unavailable") dispatch({ type: "set_page", page: 1 });
              }}>
                {project.animations.map((item, index) => <option value={index} key={`${item.source.id}:${index}`}>{item.source.name}</option>)}
              </select>
            </label>
            <dl>
              <div><dt>Format</dt><dd>{project.origin.adapterLabel}</dd></div>
              <div><dt>Nodes</dt><dd>{Object.keys(project.nodes).length}</dd></div>
              <div><dt>Animations</dt><dd>{project.animations.length}</dd></div>
            </dl>
          </section>

          <nav className="workflow-pages" aria-label="Conversion pages">
            {(["Review", "Settings", "Export"] as const).map((label, index) => (
              <button className={page === index ? "active" : ""} type="button" onClick={() => dispatch({ type: "set_page", page: index as WorkspacePage })} key={label}>
                <span>{index + 1}</span>{label}
              </button>
            ))}
          </nav>

          {warningGroups.map((group) => group.issues.length === 1 ? (
            <p className="message warning" key={group.code}>{group.issues[0].message}</p>
          ) : (
            <details className="message warning warning-group" key={group.code}>
              <summary>{group.label} ({group.issues.length})</summary>
              <ul>
                {group.issues.map((diagnostic, index) => (
                  <li key={`${diagnostic.sourcePath ?? "warning"}:${index}`}>
                    <span>{diagnostic.message}</span>
                    {diagnostic.sourcePath && <code>{diagnostic.sourcePath}</code>}
                  </li>
                ))}
              </ul>
            </details>
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
                {skinCandidates.length > 0 && availability?.preview === "full" && (
                  <label className="frame-slider">
                    <span>Preview frame</span>
                    <input type="range" min="0" max={previewDurationTicks + 1} step="1" value={previewFrameIndex} onChange={(event) => {
                      updateSession((current) => ({ ...current, previewFrameIndex: Number(event.currentTarget.value), selectedParts: new Set() }));
                    }} />
                    <output>{previewTick === null ? "Create pose" : `${previewTick} tick`}</output>
                  </label>
                )}
              </div>
            </div>
            {availability?.preview === "unavailable" ? (
              <div className="no-skin-parts"><strong>3D preview unavailable</strong><span>Edit the animation metadata on Page 2. See the warning above for the source expression that must be changed.</span></div>
            ) : skinCandidates.length > 0 ? (
              <div className="editor">
                <Suspense fallback={<div className="preview-loading" role="status">Loading 3D preview…</div>}>
                  <PartPreview
                    key={project.origin.sourceName}
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
            {availability?.exportable && <CommandPanel
              animation={animation}
              tick={previewTick}
              disabled={busy}
              onAdd={addCommandAtPreviewTick}
              onChange={changeFrameCommand}
              onRemove={deleteFrameCommand}
            />}
          </section>}

          {page === 1 && animationOptions && <SettingsPanel
            metadata={animationOptions}
            minecraftVersion={project.targetMinecraftVersion}
            disabled={busy}
            onMetadataChange={(metadata) => updateSession((current) => updateSessionAnimationOptions(current, metadata))}
            onMinecraftVersionChange={(minecraftVersion) => updateSession((current) => ({
              ...current,
              document: { ...current.document, targetMinecraftVersion: minecraftVersion },
            }))}
          />}

          {page === 2 && <ExportPanel
            assignmentSummary={assignmentSummary(project)}
            animations={project.animations.map((item) => {
              const itemAvailability = animationAvailability(item.source);
              return { label: item.output.displayName, detail: item.source.id, exportable: itemAvailability.exportable, reason: itemAvailability.reason };
            })}
            hasResources={project.resources.size > 0}
            error={exportError}
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
