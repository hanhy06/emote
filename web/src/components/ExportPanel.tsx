import type { TargetedEvent } from "preact";
import { useEffect, useState } from "preact/hooks";
import type { ExportOptions } from "../export/types";

const STOP_CONDITION_OPTIONS = [
  ["jump", "Stop on jump"],
  ["submerge", "Stop when submerged"],
  ["ride", "Stop on mount"],
  ["damage", "Stop when damaged"],
  ["attack", "Stop on attack"],
  ["game_mode_change", "Stop on game mode change"],
] as const;

interface DownloadItem {
  label: string;
  detail: string;
}

interface ExportPanelProps {
  metadata: ExportOptions;
  assignmentSummary: string;
  animations: DownloadItem[];
  hasResources: boolean;
  error: string;
  onMetadataChange: (metadata: ExportOptions) => void;
  onDownloadAnimation: (index: number) => void;
  onDownloadResourcePack: (index: number) => void;
  onMergeResourcePackZip: (file: File) => void;
  onMergeResourcePackFolder: (files: File[]) => void;
}

export function ExportPanel({
  metadata,
  assignmentSummary,
  animations,
  hasResources,
  error,
  onMetadataChange,
  onDownloadAnimation,
  onDownloadResourcePack,
  onMergeResourcePackZip,
  onMergeResourcePackFolder,
}: ExportPanelProps) {
  const [mergeMenuIndex, setMergeMenuIndex] = useState<number | null>(null);
  const additionalMetadata = Object.entries(metadata.additionalMetadata);

  useEffect(() => {
    if (mergeMenuIndex === null) return;
    const closeMenu = () => setMergeMenuIndex(null);
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") closeMenu();
    };
    document.addEventListener("pointerdown", closeMenu);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", closeMenu);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [mergeMenuIndex]);

  function selectZip(event: TargetedEvent<HTMLInputElement>) {
    const file = event.currentTarget.files?.[0];
    if (file) onMergeResourcePackZip(file);
    setMergeMenuIndex(null);
    event.currentTarget.value = "";
  }

  function selectFolder(event: TargetedEvent<HTMLInputElement>) {
    const files = [...(event.currentTarget.files ?? [])];
    if (files.length) onMergeResourcePackFolder(files);
    setMergeMenuIndex(null);
    event.currentTarget.value = "";
  }

  function updatePlayerStopCondition(
    key: keyof ExportOptions["player"]["stop_conditions"],
    value: number | boolean,
  ) {
    onMetadataChange({
      ...metadata,
      player: {
        ...metadata.player,
        stop_conditions: { ...metadata.player.stop_conditions, [key]: value },
      },
    });
  }

  return (
    <section className="export">
      <div className="section-heading export-heading">
        <div>
          <span className="step-label">Final step</span>
          <h2>Export files</h2>
          <p>Review the output settings, then download the animation and its generated resource pack.</p>
        </div>
        <span className="summary-badge">{assignmentSummary}</span>
      </div>
      <div className="fields">
        <label>Minecraft version<input value={metadata.minecraftVersion} onChange={(event) => onMetadataChange({ ...metadata, minecraftVersion: event.currentTarget.value })} /></label>
        <label>Namespace<input value={metadata.namespace} onChange={(event) => onMetadataChange({ ...metadata, namespace: event.currentTarget.value })} /></label>
        <label>Display name<input value={metadata.name} onChange={(event) => onMetadataChange({ ...metadata, name: event.currentTarget.value })} /></label>
        <label>Description<input value={metadata.description} onChange={(event) => onMetadataChange({ ...metadata, description: event.currentTarget.value })} /></label>
      </div>
      <section className="playback-behavior" aria-labelledby="playback-behavior-heading">
        <h3 id="playback-behavior-heading">Playback behavior</h3>
        <p>Choose the playback mode, player visibility, and which actions stop the emote.</p>
        <div className="fields playback-behavior-primary">
          <label><span className="field-heading">Playback mode</span><select value={metadata.playbackMode} onChange={(event) => onMetadataChange({ ...metadata, playbackMode: event.currentTarget.value as ExportOptions["playbackMode"] })}>
            <option value="source">Source setting</option>
            <option value="once">Play once</option>
            <option value="loop">Loop</option>
            <option value="server_sync">Server-synchronized loop</option>
          </select></label>
          <label><span className="field-heading">Movement distance <small>Horizontal blocks; 0 allows movement.</small></span>
            <input type="number" min="0" step="0.05" value={metadata.player.stop_conditions.movement_distance} onChange={(event) => updatePlayerStopCondition("movement_distance", Number(event.currentTarget.value))} />
          </label>
        </div>
        <div className="fields playback-behavior-options">
          <label className="checkbox"><input type="checkbox" checked={metadata.player.hidden} onChange={(event) => onMetadataChange({ ...metadata, player: { ...metadata.player, hidden: event.currentTarget.checked } })} />Hide original player</label>
          {STOP_CONDITION_OPTIONS.map(([condition, label]) => (
            <label className="checkbox" key={condition}>
              <input type="checkbox" checked={metadata.player.stop_conditions[condition]} onChange={(event) => updatePlayerStopCondition(condition, event.currentTarget.checked)} />
              {label}
            </label>
          ))}
        </div>
      </section>
      {additionalMetadata.length > 0 && (
        <section className="additional-metadata" aria-labelledby="additional-metadata-heading">
          <h3 id="additional-metadata-heading">Additional metadata</h3>
          <p>Unrecognized source metadata is preserved in the exported animation.</p>
          <dl>
            {additionalMetadata.map(([key, value]) => (
              <div key={key}>
                <dt>{key}</dt>
                <dd><pre>{formatMetadataValue(value)}</pre></dd>
              </div>
            ))}
          </dl>
        </section>
      )}
      {error && <p className="error" role="alert">{error}</p>}
      <h3>Animations</h3>
      <ul className="download-list">
        {animations.map((animation, index) => (
          <li key={`${animation.detail}:${index}`}>
            <span><strong>{animation.label}</strong><small>{animation.detail}</small></span>
            <div className="download-actions">
              <button className="primary-button" type="button" onClick={() => onDownloadAnimation(index)}>Download JSON</button>
              {hasResources && (
                <div className="resource-pack-action">
                  <div className="resource-pack-button">
                    <button type="button" onClick={() => {
                      setMergeMenuIndex(null);
                      onDownloadResourcePack(index);
                    }}>Download resource pack</button>
                    <button
                      className="split-menu-toggle"
                      type="button"
                      aria-label="Resource pack merge options"
                      aria-haspopup="menu"
                      aria-expanded={mergeMenuIndex === index}
                      onPointerDown={(event) => event.stopPropagation()}
                      onClick={() => setMergeMenuIndex((current) => current === index ? null : index)}
                    >▼</button>
                  </div>
                  {mergeMenuIndex === index && (
                    <div className="merge-menu" role="menu" onPointerDown={(event) => event.stopPropagation()}>
                      <label className="button-file-input">Merge into ZIP<input type="file" accept=".zip,application/zip" onChange={selectZip} /></label>
                      <label className="button-file-input">Merge into folder<input type="file" multiple ref={(input) => input?.setAttribute("webkitdirectory", "")} onChange={selectFolder} /></label>
                    </div>
                  )}
                </div>
              )}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

function formatMetadataValue(value: unknown): string {
  if (typeof value === "string") return value;
  return JSON.stringify(value, null, 2) ?? String(value);
}
