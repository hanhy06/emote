import type { TargetedEvent } from "preact";
import { lazy, Suspense } from "preact/compat";
import { useCallback, useMemo, useReducer, useState } from "preact/hooks";
import { AssignmentPanel } from "./components/AssignmentPanel";
import { EventPanel } from "./components/EventPanel";
import { ExportPanel } from "./components/ExportPanel";
import { SettingsPanel } from "./components/SettingsPanel";
import { downloadExports } from "./export/download";
import type { ExportResult } from "./export/types";
import { INPUT_FORMATS } from "./import/formats";
import { importFileBatch } from "./import/importBatch";
import { conversionErrorMessage, groupConversionWarnings } from "./foundation/diagnostics";
import {
  assignmentSummary,
  eventReviewLocations,
  sequenceReferenceWarnings,
  EMPTY_SELECTION,
  INITIAL_WORKSPACE,
  workspaceReducer,
  type WorkspacePage,
} from "./workspace";
import { createPreviewModel } from "./preview/previewModel";
import { parseMinecraftTime } from "./format/time";
import { MINECRAFT_VERSION_PROFILES } from "./format/minecraftVersionProfiles";
import { skinPartAssignments, skinPartOrders } from "./domain/conversionDocument";
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
  const [eventsOpen, setEventsOpen] = useState(false);
  const [settingsValid, setSettingsValid] = useState(true);
  const [eventEditorRevision, setEventEditorRevision] = useState(0);
  const { session, page, openError, exportError, operation } = workspace;
  const busy = operation.type !== "idle";
  const busyMessage = operation.type === "idle" ? null : operation.message;

  const project = session?.document ?? null;
  const animationIndex = session?.animationIndex ?? 0;
  const selectedAnimation = project?.animations[animationIndex];
  const selectedAnimationTick = session?.selectedFrame.type === "timeline" ? session.selectedFrame.animationTick : null;
  const frameIndex = selectedAnimationTick === null ? 0 : selectedAnimationTick + 1;
  const durationTicks = selectedAnimation ? parseMinecraftTime(selectedAnimation.clip.duration, 1) : 0;
  const preview = useMemo(() => {
    if (!session) return null;
    const selectedAnimation = session.document.animations[session.animationIndex];
    return createPreviewModel(session.document, selectedAnimation, session.selectedFrame);
  }, [session]);
  const candidateParts = preview?.candidateParts ?? [];
  const assignments = useMemo(() => project ? skinPartAssignments(project) : {}, [project]);
  const orders = useMemo(() => project ? skinPartOrders(project) : {}, [project]);
  const selectedCandidateIds = session?.selectedCandidateIds ?? EMPTY_SELECTION;
  const availability = preview?.availability ?? null;
  const eventReview = useMemo(() => eventReviewLocations(project), [project]);
  const eventReviewTypes = [
    ...(eventReview.some((entry) => entry.locations.some((location) => location !== "callbacks")) ? ["commands"] : []),
    ...(eventReview.some((entry) => entry.locations.includes("callbacks")) ? ["callbacks"] : []),
  ];
  const warningGroups = useMemo(() => groupConversionWarnings([...(project?.diagnostics ?? []), ...sequenceReferenceWarnings(project)]), [project]);
  const previewParts = preview?.parts ?? [];
  const hasReviewNodes = candidateParts.length > 0;

  const clipEvents = selectedAnimation?.clip.events;
  const eventSummary = useMemo(() => {
    if (selectedAnimationTick === null) {
      const count = (clipEvents?.start?.length ?? 0) + (clipEvents?.loop?.length ?? 0) + (clipEvents?.stop?.length ?? 0);
      return `Create pose · ${count} events · ${selectedAnimation?.callbacks?.length ?? 0} callbacks`;
    }
    const count = clipEvents?.timeline?.filter(event => parseMinecraftTime(event.time) === selectedAnimationTick).length ?? 0;
    return `Tick ${selectedAnimationTick} · ${count} events`;
  }, [clipEvents, selectedAnimationTick, selectedAnimation?.callbacks]);

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
      setEventsOpen(false);
      setSettingsValid(true);
      setEventEditorRevision((revision) => revision + 1);
      dispatch({ type: "documents_open_succeeded", document });
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
      const { exportAnimation } = await import("./export/projectExporter");
      return exportAnimation(session.document, index);
    }, "Conversion failed.", "Creating animation file");
  }

  async function handleAnimationBundle(includeSequence: boolean) {
    if (!session) return;
    await runExport(async () => {
      const { exportAnimations } = await import("./export/projectExporter");
      return exportAnimations(session.document, includeSequence);
    }, "File export failed.", includeSequence ? "Creating sequence files" : "Creating animation files");
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
              <li><span>2</span><p><strong>Edit animation</strong><small>Assign skin parts and edit playback, metadata, and events.</small></p></li>
              <li><span>3</span><p><strong>Download the result</strong><small>Export Emote JSON and any generated resources.</small></p></li>
            </ol>
          </div>
        </section>
      )}

      {session && project && selectedAnimation && (
        <>
          <section className="project-summary" aria-label="Imported project">
            <div className="project-file">
              <span>Imported {project.origin.sourceName.includes(", ") ? "files" : "file"}</span>
              <strong>{project.origin.sourceName}</strong>
            </div>
            <div className="project-selectors">
              <label className="project-animation">
                <span>Animation</span>
                <select value={animationIndex} disabled={project.animations.length === 1 || !eventJsonValid || !settingsValid} onChange={(event) => {
                  const nextIndex = Number(event.currentTarget.value);
                  dispatch({ type: "animation_selected", index: nextIndex });
                }}>
                  {project.animations.map((item, index) => <option value={index} key={index}>{item.metadata.name}</option>)}
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
              <div><dt>Animations</dt><dd>{project.animations.length}</dd></div>
            </dl>
          </section>

          <nav className="workflow-pages" aria-label="Conversion pages">
            {(["Edit", "Export"] as const).map((label, index) => (
              <button aria-current={page === index ? "step" : undefined} className={page === index ? "active" : ""} type="button" disabled={(!eventJsonValid || !settingsValid) && page !== index} onClick={() => dispatch({ type: "page_selected", page: index as WorkspacePage })} key={label}>
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

          {(selectedAnimation?.skin?.default || selectedAnimation?.skin?.override) && (
            <p className="message warning" role="status">Animation {selectedAnimation.metadata.name} uses custom skin settings. Skin changes are applied in game and are not shown in the preview.</p>
          )}

          {eventReviewTypes.includes("commands") && (
            <p className="message warning" role="alert">
              <strong>Commands run with server operator permission.</strong>
              <span>They can modify or delete world data. Review every command before installing this emote, and only use files you trust.</span>
            </p>
          )}

          {eventReview.length > 0 && (
            <details className="message warning warning-group" role="alert">
              <summary>Review {eventReviewTypes.join(" and ")} at these locations ({eventReview.length})</summary>
              <ul>
                {eventReview.map((entry) => (
                  <li key={entry.owner}><strong>{entry.owner}</strong><span>{entry.locations.join(" · ")}</span></li>
                ))}
              </ul>
            </details>
          )}

          {page === 0 && <section className="workspace page-panel" aria-labelledby="workspace-title">
            <div className="section-heading">
              <div>
                <h2 id="workspace-title">Edit animation</h2>
              </div>
              <div className="preview-controls">
                {availability?.status === "approximate" && <output title={availability.reason}>Approximate preview</output>}
                <div className="frame-slider">
                  <span id="animation-frame-label">Animation frame</span>
                  <input type="range" aria-labelledby="animation-frame-label" min="0" max={durationTicks + 1} step="1" value={frameIndex} disabled={!eventJsonValid} onChange={(event) => {
                    const index = Number(event.currentTarget.value);
                    dispatch({ type: "frame_selected", frame: index === 0 ? { type: "create" } : { type: "timeline", animationTick: index - 1 } });
                  }} />
                  {selectedAnimationTick === null ? <output>Create pose</output> : <label className="frame-tick">
                    <input key={`${eventEditorRevision}:${animationIndex}:${selectedAnimationTick}`} type="number" aria-label="Animation tick"
                      min="0" max={durationTicks} step="1" defaultValue={selectedAnimationTick} disabled={!eventJsonValid} onBlur={(event) => {
                        const input = event.currentTarget;
                        const animationTick = Number(input.value);
                        if (!input.validity.valid || !Number.isInteger(animationTick) || animationTick < 0 || animationTick > durationTicks) {
                          input.value = String(selectedAnimationTick);
                          return;
                        }
                        input.value = String(animationTick);
                        if (animationTick !== selectedAnimationTick) dispatch({ type: "frame_selected", frame: { type: "timeline", animationTick } });
                      }} />
                    tick
                  </label>}
                </div>
              </div>
            </div>
            <div className={hasReviewNodes ? "editor" : "editor properties-only"}>
              {hasReviewNodes && (availability?.status === "unavailable" ? (
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
              </Suspense>)}
              <aside className="inspector" aria-label="Animation properties">
                {hasReviewNodes && <AssignmentPanel
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
                />}
                <SettingsPanel
                  key={`${eventEditorRevision}:${animationIndex}`}
                  animation={selectedAnimation}
                  disabled={busy}
                  onChange={(ir) => dispatch({ type: "animation_changed", ir })}
                  onValidityChange={setSettingsValid}
                />
                <button className="event-disclosure" type="button" aria-expanded={eventsOpen} aria-controls="frame-events" onClick={() => setEventsOpen(!eventsOpen)}>
                  <strong>Events</strong>
                  <small className={eventJsonValid ? "" : "error"}>{eventJsonValid ? eventSummary : "Invalid event JSON"}</small>
                </button>
              </aside>
            </div>
            <div id="frame-events" hidden={!eventsOpen}>
              <EventPanel
                key={`${eventEditorRevision}:${animationIndex}:${selectedAnimationTick === null ? "lifecycle" : selectedAnimationTick}`}
                events={{
                  start: selectedAnimation.clip.events?.start ?? [],
                  timeline: selectedAnimation.clip.events?.timeline ?? [],
                  loop: selectedAnimation.clip.events?.loop ?? [],
                  stop: selectedAnimation.clip.events?.stop ?? [],
                }}
                callbacks={selectedAnimation.callbacks}
                animationTick={selectedAnimationTick}
                disabled={busy}
                onLifecycleChange={(events) => dispatch({ type: "lifecycle_events_changed", events })}
                onTimelineChange={(animationTick, events) => dispatch({ type: "timeline_events_changed", animationTick, events })}
                onValidityChange={setEventJsonValid}
              />
            </div>
          </section>}

          {page === 1 && <ExportPanel
            assignmentSummary={assignmentSummary(project)}
            animations={project.animations}
            error={exportError}
            disabled={busy || !settingsValid}
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
