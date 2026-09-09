import type { TargetedEvent } from "preact";
import { lazy, Suspense } from "preact/compat";
import { useCallback, useMemo, useReducer } from "preact/hooks";
import { AssignmentPanel } from "./components/AssignmentPanel";
import { CommandPanel } from "./components/CommandPanel";
import { ExportPanel } from "./components/ExportPanel";
import { SettingsPanel } from "./components/SettingsPanel";
import { downloadExport, downloadExports } from "./export/download";
import type { ExportResult } from "./export/types";
import type { NodeSpace, PlayerSkinPart } from "./format/emoteAnimation";
import { IMPORT_ADAPTERS } from "./import/adapters";
import { detectAdapter, importDetected } from "./import/adapterRegistry";
import { isImportedSequence } from "./import/adapter";
import { conversionErrorMessage, groupConversionWarnings } from "./foundation/diagnostics";
import { countImportedCommands } from "./import/common/securityWarning";
import { animationAvailability } from "./domain/conversionSeed";
import {
  assignmentSummary,
  EMPTY_SELECTION,
  INITIAL_WORKSPACE,
  workspaceReducer,
  type WorkspacePage,
} from "./workspace";
import { createPreviewModel } from "./preview/previewModel";
import { createConversionDocument } from "./domain/conversionDocument";
import { combineConversionDocuments } from "./domain/conversionBatch";
const PartPreview = lazy(() => import("./components/PartPreview"));
const ACCEPTED_EXTENSIONS = [...new Set(IMPORT_ADAPTERS.flatMap((adapter) => adapter.extensions))]
  .map((extension) => `.${extension}`)
  .join(",");
const IMPORT_FORMATS = [
  {
    label: "BD Engine",
    extensions: ".zip",
    description: "In BD Engine, open Get Command and export the animation as a datapack.",
  },
  {
    label: "GeckoLib",
    extensions: ".bbmodel",
    description: "Use the original .bbmodel file for an emote created in Blockbench with the GeckoLib format. Model, animation, and skin data are imported.",
  },
  {
    label: "Animated Java",
    extensions: ".ajblueprint",
    description: "Use the original .ajblueprint project from Animated Java. Model, animation, and skin data are imported.",
  },
  {
    label: "Bedrock & Emotecraft",
    extensions: ".json .emotecraft",
    description: "These formats are experimental and may not be fully supported.",
  },
] as const;

export function App() {
  const [workspace, dispatch] = useReducer(workspaceReducer, INITIAL_WORKSPACE);
  const { session, page, openError, exportError, operation } = workspace;
  const busy = operation.type !== "idle";
  const busyMessage = operation.type === "idle" ? null : operation.message;

  const project = session?.document ?? null;
  const animationIndex = session?.animationIndex ?? 0;
  const previewFrameIndex = session?.previewFrameIndex ?? 0;
  const preview = useMemo(() => session
    ? createPreviewModel(session.document, session.animationIndex, session.previewFrameIndex)
    : null, [session]);
  const assignments = preview?.assignments ?? {};
  const orders = preview?.orders ?? {};
  const spaces = preview?.spaces ?? {};
  const selectedNodeIds = session?.selectedNodeIds ?? EMPTY_SELECTION;
  const animation = project?.animations[animationIndex]?.source;
  const availability = preview?.availability ?? null;
  const previewDurationTicks = preview?.durationTicks ?? 0;
  const animationOptions = project?.animations[animationIndex]?.output;
  const importedCommandCount = useMemo(() => countImportedCommands(project), [project]);
  const warningGroups = useMemo(() => groupConversionWarnings(project?.diagnostics ?? []), [project]);
  const previewTick = preview?.tick ?? null;
  const previewParts = preview?.parts ?? [];
  const hasReviewNodes = preview?.hasReviewNodes ?? false;

  async function handleFileChange(event: TargetedEvent<HTMLInputElement>) {
    const inputElement = event.currentTarget;
    const files = [...(inputElement.files ?? [])];
    if (files.length === 0) return;
    if (busy) {
      inputElement.value = "";
      return;
    }
    dispatch({ type: "open_started", message: files.length === 1 ? "Opening animation project" : `Opening ${files.length} animation projects` });
    try {
      await showLoadingScreen();
      const imported = await Promise.all(files.map(async (file) => {
        const input = { name: file.name, bytes: new Uint8Array(await file.arrayBuffer()) };
        const detected = await detectAdapter(IMPORT_ADAPTERS, input);
        return { source: await importDetected(detected, input), adapterLabel: detected.adapter.label };
      }));
      for (const item of imported) {
        if (!isImportedSequence(item.source)) continue;
        downloadExport({ blob: new Blob([JSON.stringify(item.source.sequence)], { type: "application/json" }), fileName: item.source.fileName });
      }
      const documents = imported.flatMap((item) => isImportedSequence(item.source)
        ? []
        : [createConversionDocument(item.source, item.adapterLabel)]);
      if (documents.length > 0) dispatch({ type: "documents_open_succeeded", document: combineConversionDocuments(documents) });
    } catch (reason) {
      dispatch({ type: "open_failed", message: conversionErrorMessage(reason, "Could not import the file.") });
    } finally {
      dispatch({ type: "operation_finished" });
      inputElement.value = "";
    }
  }

  async function runExport(action: () => Promise<readonly ExportResult[]>, fallbackMessage: string, progressMessage: string) {
    if (busy) return;
    dispatch({ type: "export_started", message: progressMessage });

    try {
      await showLoadingScreen();
      downloadExports(await action());
    } catch (reason) {
      dispatch({ type: "export_failed", message: conversionErrorMessage(reason, fallbackMessage) });
    } finally {
      dispatch({ type: "operation_finished" });
    }
  }

  async function handleAnimationDownload(index: number) {
    if (!session) return;

    await runExport(async () => {
      const { createDocumentAnimationDownload } = await import("./export/projectExporter");
      return createDocumentAnimationDownload(session.document, index);
    }, "Conversion failed.", "Creating animation file");
  }

  async function handleAnimationBundle(includeSequence: boolean) {
    if (!session) return;
    await runExport(async () => {
      const { createDocumentAnimationBundleDownload } = await import("./export/projectExporter");
      return createDocumentAnimationBundleDownload(session.document, includeSequence);
    }, "File export failed.", includeSequence ? "Creating sequence files" : "Creating animation files");
  }

  const handleNodeSelect = useCallback((nodeId: string, additive: boolean) => {
    dispatch({ type: "node_selected", nodeId, additive });
  }, []);

  const handleNodesSelect = useCallback((nodeIds: readonly string[], additive: boolean) => {
    dispatch({ type: "nodes_selected", nodeIds, additive });
  }, []);

  function assignSelected(part: PlayerSkinPart | null) {
    if (selectedNodeIds.size === 0) return;
    dispatch({ type: "skin_part_assigned", part });
  }

  function assignSelectedSpace(space: NodeSpace) {
    if (selectedNodeIds.size === 0) return;
    dispatch({ type: "node_space_assigned", space });
  }

  function assignOrder(order: number) {
    dispatch({ type: "skin_order_assigned", order });
  }

  function addCommandAtPreviewTick() {
    if (previewTick !== null) dispatch({ type: "frame_command_added", tick: previewTick });
  }

  function changeFrameCommand(eventIndex: number, commandIndex: number, command: string) {
    dispatch({ type: "frame_command_changed", eventIndex, commandIndex, command });
  }

  function deleteFrameCommand(eventIndex: number, commandIndex: number) {
    dispatch({ type: "frame_command_removed", eventIndex, commandIndex });
  }

  const hasSelectedAssignment = [...selectedNodeIds].some((nodeId) => assignments[nodeId] != null);
  const filePicker = (
    <label className={`file-input${busy ? " disabled" : ""}`}>
      <span>{session ? "Open other files" : "Choose animation files"}</span>
      <input type="file" accept={ACCEPTED_EXTENSIONS} multiple onChange={handleFileChange} disabled={busy} />
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
            <p>Open one or more supported model projects or existing Emote JSON files. Models, animations, and skin parts are processed locally in your browser.</p>
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
              <li><span>1</span><p><strong>Open files</strong><small>Choose one or more files. Each format is detected automatically.</small></p></li>
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
              <span>Imported {project.origin.sourceName.includes(", ") ? "files" : "file"}</span>
              <strong>{project.origin.sourceName}</strong>
            </div>
            <label className="project-animation">
              <span>Animation</span>
              <select value={animationIndex} disabled={project.animations.length === 1} onChange={(event) => {
                const nextIndex = Number(event.currentTarget.value);
                dispatch({ type: "animation_selected", index: nextIndex });
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
              <button className={page === index ? "active" : ""} type="button" onClick={() => dispatch({ type: "page_selected", page: index as WorkspacePage })} key={label}>
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
                <h2 id="workspace-title">{hasReviewNodes ? "Review model assignments" : "Review imported animation"}</h2>
                <p>{hasReviewNodes
                  ? "Select model parts, then assign their player role."
                  : "This file does not contain assignable model parts."}</p>
              </div>
              <div className="preview-controls">
                {hasReviewNodes && availability?.preview === "full" && (
                  <label className="frame-slider">
                    <span>Preview frame</span>
                    <input type="range" min="0" max={previewDurationTicks + 1} step="1" value={previewFrameIndex} onChange={(event) => {
                      dispatch({ type: "preview_frame_selected", index: Number(event.currentTarget.value) });
                    }} />
                    <output>{previewTick === null ? "Create pose" : `${previewTick} tick`}</output>
                  </label>
                )}
              </div>
            </div>
            {availability?.preview === "unavailable" ? (
              <div className="no-skin-parts"><strong>3D preview unavailable</strong><span>Edit the animation metadata on Page 2. See the warning above for the source expression that must be changed.</span></div>
            ) : hasReviewNodes ? (
              <div className="editor">
                <Suspense fallback={<div className="preview-loading" role="status">Loading 3D preview…</div>}>
                  <PartPreview
                    key={project.origin.sourceName}
                    parts={previewParts}
                    assignments={assignments}
                    selectedNodeIds={selectedNodeIds}
                    onSelectNode={handleNodeSelect}
                    onSelectNodes={handleNodesSelect}
                  />
                </Suspense>
                <AssignmentPanel
                  parts={previewParts}
                  assignments={assignments}
                  orders={orders}
                  spaces={spaces}
                  selectedNodeIds={selectedNodeIds}
                  hasSelectedAssignment={hasSelectedAssignment}
                  onAssignPart={assignSelected}
                  onAssignOrder={assignOrder}
                  onAssignSpace={assignSelectedSpace}
                  onSelectNode={handleNodeSelect}
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
            onMetadataChange={(output) => dispatch({ type: "animation_output_changed", output })}
            onMinecraftVersionChange={(version) => dispatch({ type: "minecraft_version_changed", version })}
          />}

          {page === 2 && <ExportPanel
            assignmentSummary={assignmentSummary(project)}
            animations={project.animations.map((item) => {
              const itemAvailability = animationAvailability(item.source);
              return { label: item.output.displayName, detail: item.source.id, exportable: itemAvailability.exportable, reason: itemAvailability.reason };
            })}
            error={exportError}
            disabled={busy}
            onDownloadAnimation={handleAnimationDownload}
            onDownloadAllAnimations={() => handleAnimationBundle(false)}
            onDownloadSequence={() => handleAnimationBundle(true)}
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
