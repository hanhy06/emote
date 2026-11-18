import type { TargetedEvent } from "preact";
import { lazy, Suspense } from "preact/compat";
import { useCallback, useMemo, useReducer, useState } from "preact/hooks";
import { AssignmentPanel } from "./components/AssignmentPanel";
import { EventPanel } from "./components/EventPanel";
import { ExportPanel } from "./components/ExportPanel";
import { SettingsPanel } from "./components/SettingsPanel";
import { downloadExports } from "./export/download";
import type { ExportSelection } from "./export/projectExporter";
import { INPUT_FORMATS } from "./import/formats";
import { importFileBatch } from "./import/importBatch";
import { conversionErrorMessage } from "./foundation/diagnostics";
import {
  assignmentSummary,
  eventReviewSummary,
  sequenceReferenceWarnings,
  EMPTY_SELECTION,
  INITIAL_WORKSPACE,
  workspaceReducer,
  type WorkspacePage,
  type SelectedFrame,
} from "./workspace";
import { createPreviewModel } from "./preview/previewModel";
import { formatMinecraftTime, parseMinecraftTime } from "./format/time";
import { MINECRAFT_VERSION_PROFILES } from "./format/minecraftVersionProfiles";
import type { EventIR } from "./domain/animationIR";
import { animationEntries, skinPartAssignments, skinPartOrders, type ConversionEntry } from "./domain/conversionDocument";
const PartPreview = lazy(() => import("./components/PartPreview"));
const ACCEPTED_EXTENSIONS = [...new Set(Object.values(INPUT_FORMATS).map((format) => format.extension))]
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
  const [eventJsonValid, setEventJsonValid] = useState(true);
  const [settingsCategory, setSettingsCategory] = useState("settings");
  const [settingsValid, setSettingsValid] = useState(true);
  const [eventEditorRevision, setEventEditorRevision] = useState(0);
  const [commandSourceRevision, setCommandSourceRevision] = useState(0);
  const { session, page, openError, exportError, operation } = workspace;
  const busy = operation.type !== "idle";
  const busyMessage = operation.type === "idle" ? null : operation.message;

  const project = session?.document ?? null;
  const animations = useMemo(() => project ? animationEntries(project) : [], [project]);
  const selectedIndex = session?.selectedIndex ?? 0;
  const selectedEntry = project?.entries.at(selectedIndex);
  const changeEntry = (entry: ConversionEntry) => dispatch({ type: "entry_changed", index: selectedIndex, entry });
  const selectedAnimation = selectedEntry?.type === "animation" ? selectedEntry : undefined;
  const selectedAnimationTick = session?.selectedFrame.type === "timeline" ? session.selectedFrame.animationTick : null;
  const frameIndex = selectedAnimationTick === null ? 0 : selectedAnimationTick + 1;
  const duration = selectedAnimation?.clip.duration;
  const durationTicks = duration === undefined ? undefined : parseMinecraftTime(duration, 1);
  const initialFrameLabel = "Create pose";
  const preview = useMemo(() => {
    if (!session || !selectedAnimation) return null;
    return createPreviewModel(session.document, selectedAnimation, session.selectedFrame);
  }, [session, selectedAnimation]);
  const candidateParts = preview?.candidateParts ?? [];
  const assignments = useMemo(() => project ? skinPartAssignments(project) : {}, [project]);
  const orders = useMemo(() => project ? skinPartOrders(project) : {}, [project]);
  const selectedCandidateIds = session?.selectedCandidateIds ?? EMPTY_SELECTION;
  const availability = preview?.availability ?? null;
  const eventReview = useMemo(() => eventReviewSummary(project, selectedIndex), [project, selectedIndex]);
  const reviewTicks = eventReview.commandTicks;
  const reviewFrameIndex = selectedAnimationTick === null || reviewTicks.length === 0 ? 0
    : reviewTicks.reduce((nearest, tick, index) => Math.abs(tick - selectedAnimationTick) < Math.abs(reviewTicks[nearest] - selectedAnimationTick) ? index : nearest, 0) + 1;
  const conversionIssues = useMemo(() => [...(project?.diagnostics ?? []), ...sequenceReferenceWarnings(project)], [project]);
  const previewParts = preview?.parts ?? [];
  const hasReviewNodes = candidateParts.length > 0;

  const commandSources = useMemo(() => {
    const options = new Map<string, { label: string; source: EventIR["source"] }>([
      ["player", { label: "Player", source: { type: "player" } }],
      ["server", { label: "Server", source: { type: "server" } }],
    ]);
    for (const nodeId of selectedAnimation?.nodeIds ?? []) {
      const node = project?.nodes[nodeId];
      for (const attachment of Object.keys(node?.attachments ?? {})) {
        options.set(JSON.stringify([nodeId, attachment]), {
          label: `Node · ${node?.name ?? nodeId} · ${attachment}`,
          source: { type: "node", node: nodeId, attachment },
        });
      }
    }
    const current = new Set<string>();
    for (const events of Object.values(selectedAnimation?.clip.events ?? {})) {
      for (const event of events) {
        if (event.action.type !== "commands") continue;
        const source = event.source;
        const value = source.type === "node" ? JSON.stringify([source.node, source.attachment]) : source.type;
        current.add(value);
        if (!options.has(value) && source.type === "node") options.set(value, {
          label: `Node · ${project?.nodes[source.node]?.name ?? source.node} · ${source.attachment}`, source,
        });
      }
    }
    return { options, value: current.size === 1 ? current.values().next().value ?? "" : "", hasCommands: current.size > 0 };
  }, [selectedAnimation, project?.nodes]);

  function handleCommandSourceChange(value: string) {
    const option = commandSources.options.get(value);
    if (!option || !selectedAnimation?.clip.events || busy || !eventJsonValid) return;
    const events = structuredClone(selectedAnimation.clip.events);
    for (const phase of Object.values(events)) {
      for (const event of phase) {
        if (event.action.type === "commands") event.source = { ...option.source };
      }
    }
    changeEntry({ ...selectedAnimation, clip: { ...selectedAnimation.clip, events } });
    setCommandSourceRevision(revision => revision + 1);
  }

  function handleReviewLocation(frame: SelectedFrame) {
    if (busy || !eventJsonValid || !settingsValid) return;
    dispatch({ type: "frame_selected", frame });
    dispatch({ type: "page_selected", page: 0 });
    setSettingsCategory("events");
  }

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
      const document = await importFileBatch(files);
      setEventJsonValid(true);
      setSettingsValid(true);
      setSettingsCategory("settings");
      setEventEditorRevision((revision) => revision + 1);
      dispatch({ type: "documents_open_succeeded", document });
    } catch (reason) {
      dispatch({ type: "open_failed", message: conversionErrorMessage(reason, "Could not import the file.") });
    } finally {
      dispatch({ type: "operation_finished" });
      inputElement.value = "";
    }
  }

  async function handleDownload(selection: ExportSelection) {
    if (!session || busy) return;
    const singleAnimation = typeof selection === "number";
    const message = selection === "sequence" ? (animations.length ? "Creating sequence files" : "Creating sequence file")
      : singleAnimation ? "Creating animation file" : "Creating animation files";
    dispatch({ type: "export_started", message });

    try {
      await showLoadingScreen();
      const { exportProject } = await import("./export/projectExporter");
      downloadExports(await exportProject(session.document, selection));
    } catch (reason) {
      dispatch({ type: "export_failed", message: conversionErrorMessage(reason, singleAnimation ? "Conversion failed." : "File export failed.") });
    } finally {
      dispatch({ type: "operation_finished" });
    }
  }

  const handleCandidateSelect = useCallback((candidateId: string, additive: boolean) => {
    dispatch({ type: "candidate_selected", candidateId, additive });
  }, []);

  const handleCandidatesSelect = useCallback((candidateIds: readonly string[], additive: boolean) => {
    dispatch({ type: "candidates_selected", candidateIds, additive });
  }, []);

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

      {openError && <p className="message error" role="alert"><strong>Error · Could not open the file.</strong><span>{openError}</span></p>}

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
              <li><span>2</span><p><strong>Edit animation</strong><small>Assign skin parts and edit playback, metadata, and events.</small></p></li>
              <li><span>3</span><p><strong>Download the result</strong><small>Export Emote JSON and any generated resources.</small></p></li>
            </ol>
          </div>
        </section>
      )}

      {session && project && (
        <>
          <section className="project-summary" aria-label="Imported project">
            <div className="project-file">
              <span>Imported {project.origin.sourceName.includes(", ") ? "files" : "file"}</span>
              <strong>{project.origin.sourceName}</strong>
            </div>
            <div className="project-selectors">
              <label className="project-animation">
                <span>Emote</span>
                <select value={selectedIndex} disabled={busy || project.entries.length === 1 || !eventJsonValid || !settingsValid} onChange={(event) => {
                  dispatch({ type: "entry_selected", index: Number(event.currentTarget.value) });
                  setSettingsCategory("settings");
                }}>
                  {project.entries.map((entry, index) => <option value={index} key={index}>{entry.metadata.name}{entry.type === "sequence" && " (Sequence)"}</option>)}
                </select>
              </label>
              <label className="project-version">
                <span>Minecraft version</span>
                <select value={project.targetMinecraftVersion} disabled={busy} onChange={(event) => dispatch({ type: "minecraft_version_changed", version: event.currentTarget.value })}>
                  {Object.keys(MINECRAFT_VERSION_PROFILES).map((version) => <option key={version} value={version}>{version}</option>)}
                </select>
              </label>
            </div>
            <dl>
              <div><dt>Format</dt><dd>{project.origin.formatLabel}</dd></div>
              <div><dt>Nodes</dt><dd>{Object.keys(project.nodes).length}</dd></div>
              <div><dt>Animations</dt><dd>{animations.length}</dd></div>
            </dl>
          </section>

          <nav className="workflow-pages" aria-label="Conversion pages">
            {(["Edit", "Export"] as const).map((label, index) => (
              <button aria-current={page === index ? "step" : undefined} className={page === index ? "active" : ""} type="button" disabled={(!eventJsonValid || !settingsValid) && page !== index} onClick={() => dispatch({ type: "page_selected", page: index as WorkspacePage })} key={label}>
                <span>{index + 1}</span>{label}
              </button>
            ))}
          </nav>

          {(["error", "warning"] as const).map(severity => {
            const issues = conversionIssues.filter(issue => issue.severity === severity);
            const showSkinWarning = severity === "warning" && (selectedAnimation?.skin?.default || selectedAnimation?.skin?.override);
            const showCommandWarning = severity === "warning" && eventReview.hasCommands;
            const showReview = severity === "warning" && selectedAnimation && eventReview.hasSelectedReview;
            if (!issues.length && !showSkinWarning && !showCommandWarning && !showReview) return null;
            return <details className={`message diagnostic-group ${severity === "error" ? "error" : "warning"}`} key={`${eventEditorRevision}:${severity}`}
              open={showCommandWarning || issues.some(issue => issue.code === "unsupported_sequence_animation")}>
              <summary>{severity === "error" ? "Error" : "Warn"}</summary>
              <div className="diagnostic-body">
                {(issues.length > 0 || showSkinWarning || showCommandWarning) && <ul className="diagnostic-messages">
                  {issues.map((issue, index) => <li key={`${issue.code}:${index}`}>
                    <p>{issue.message}</p>
                    {issue.sourcePath && <code>{issue.sourcePath}</code>}
                  </li>)}
                  {showSkinWarning && <li>Animation {selectedAnimation?.metadata.name} uses custom skin settings. Skin changes are applied in game and are not shown in the preview.</li>}
                  {showCommandWarning && <li><strong>Commands run with server operator permission.</strong> They can modify or delete world data. Review every command before installing this emote, and only use files you trust.</li>}
                </ul>}
                {showReview && <div className="command-frame-control">
                  <input id="command-frame" type="range" aria-label="Command frame" min="0" max={reviewTicks.length} step="1" value={reviewFrameIndex}
                    disabled={busy || !eventJsonValid || !settingsValid || reviewTicks.length === 0}
                    aria-valuetext={selectedAnimationTick === null ? initialFrameLabel : `${selectedAnimationTick}t`}
                    onInput={event => {
                      const index = Number(event.currentTarget.value);
                      handleReviewLocation(index === 0 ? { type: "create" } : { type: "timeline", animationTick: reviewTicks[index - 1] });
                    }} />
                  <output htmlFor="command-frame">{selectedAnimationTick === null ? initialFrameLabel : `${selectedAnimationTick}t`}</output>
                </div>}
              </div>
            </details>;
          })}

          {page === 0 && selectedEntry && <section className="workspace page-panel" aria-labelledby="workspace-title">
            <div className="section-heading">
              <div>
                <h2 id="workspace-title">Edit emote</h2>
              </div>
              {availability?.status === "approximate" && <output title={availability.reason}>Approximate preview</output>}
            </div>
            {hasReviewNodes && <div className="editor">
              {availability?.status === "unavailable" ? (
                <div className="no-skin-parts"><strong>3D preview unavailable</strong><span>{availability.reason}</span></div>
              ) : <Suspense fallback={<div className="preview-loading" role="status">Loading 3D preview…</div>}>
                <PartPreview
                  key={project.origin.sourceName}
                  parts={previewParts}
                  assignments={assignments}
                  selectedCandidateIds={selectedCandidateIds}
                  onSelectCandidate={handleCandidateSelect}
                  onSelectCandidates={handleCandidatesSelect}
                />
              </Suspense>}
              <aside className="inspector" aria-label="Skin assignments">
                <AssignmentPanel
                  parts={candidateParts}
                  assignments={assignments}
                  orders={orders}
                  selectedCandidateIds={selectedCandidateIds}
                  onAssignPart={(part) => {
                    if (selectedCandidateIds.size === 0) return;
                    dispatch({ type: "skin_part_assigned", part });
                  }}
                  onAssignOrder={(order) => dispatch({ type: "skin_order_assigned", order })}
                  onSelectCandidate={handleCandidateSelect}
                />
              </aside>
            </div>}
            <SettingsPanel
              key={`${eventEditorRevision}:${selectedIndex}`}
              entry={selectedEntry}
              onChange={changeEntry}
              category={settingsCategory}
              onCategoryChange={setSettingsCategory}
              disabled={busy}
              onValidityChange={setSettingsValid}
              frameControls={durationTicks !== undefined && (
                <div className="frame-slider">
                  <span id="event-frame-label">Frame</span>
                  <input type="range" aria-labelledby="event-frame-label" min="0" max={durationTicks + 1} step="1" value={frameIndex} disabled={busy || !eventJsonValid} onChange={(event) => {
                    const index = Number(event.currentTarget.value);
                    dispatch({ type: "frame_selected", frame: index === 0 ? { type: "create" } : { type: "timeline", animationTick: index - 1 } });
                  }} />
                  {selectedAnimationTick === null ? <output>{initialFrameLabel}</output> : <label className="frame-tick">
                    <input key={`${eventEditorRevision}:${selectedIndex}:${selectedAnimationTick}`} type="number" aria-label="Event tick"
                      min="0" max={durationTicks} step="1" placeholder="0" defaultValue={selectedAnimationTick ?? ""} disabled={busy || !eventJsonValid}
                      onKeyDown={(event) => { if (event.key === "Enter") event.currentTarget.blur(); }} onBlur={(event) => {
                        const input = event.currentTarget;
                        try {
                          const tick = input.validity.valid ? input.valueAsNumber : NaN;
                          const animationTick = parseMinecraftTime(formatMinecraftTime(tick));
                          input.value = String(animationTick);
                          if (animationTick !== selectedAnimationTick) dispatch({ type: "frame_selected", frame: { type: "timeline", animationTick } });
                        } catch {
                          input.value = selectedAnimationTick === null ? "" : String(selectedAnimationTick);
                        }
                      }} />
                    tick
                  </label>}
                </div>
              )}
              eventsValid={eventJsonValid}
            >
              <EventPanel
                key={`${eventEditorRevision}:${commandSourceRevision}:${selectedIndex}:${selectedAnimationTick === null ? "lifecycle" : selectedAnimationTick}`}
                entry={selectedEntry}
                animationTick={selectedAnimationTick}
                onChange={changeEntry}
                commandSourceControl={selectedAnimation && <label className="event-command-source">Command source
                  <select value={commandSources.value} disabled={busy || !eventJsonValid || !commandSources.hasCommands}
                    onChange={event => handleCommandSourceChange(event.currentTarget.value)}>
                    {!commandSources.value && <option value="" disabled>{commandSources.hasCommands ? "Mixed" : "No commands"}</option>}
                    {[...commandSources.options].map(([value, option]) => <option key={value} value={value}>{option.label}</option>)}
                  </select>
                </label>}
                disabled={busy}
                onValidityChange={setEventJsonValid}
              />
            </SettingsPanel>
          </section>}

          {page === 1 && <ExportPanel
            assignmentSummary={animations.length > 0 ? assignmentSummary(project) : undefined}
            animations={animations}
            error={exportError}
            disabled={busy || !settingsValid}
            onDownloadAnimation={handleDownload}
            onDownloadAllAnimations={() => handleDownload("animations")}
            onDownloadSequence={() => handleDownload("sequence")}
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
