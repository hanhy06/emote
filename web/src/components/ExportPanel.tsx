import type { ChangeEvent } from "react";
import type { ExportOptions } from "../export/projectExporter";

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
  function selectZip(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (file) onMergeResourcePackZip(file);
    event.target.value = "";
  }

  function selectFolder(event: ChangeEvent<HTMLInputElement>) {
    const files = [...(event.target.files ?? [])];
    if (files.length) onMergeResourcePackFolder(files);
    event.target.value = "";
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
        <label>Minecraft version<input value={metadata.minecraftVersion} onChange={(event) => onMetadataChange({ ...metadata, minecraftVersion: event.target.value })} /></label>
        <label>Namespace<input value={metadata.namespace} onChange={(event) => onMetadataChange({ ...metadata, namespace: event.target.value })} /></label>
        <label>Display name<input value={metadata.name} onChange={(event) => onMetadataChange({ ...metadata, name: event.target.value })} /></label>
        <label>Description<input value={metadata.description} onChange={(event) => onMetadataChange({ ...metadata, description: event.target.value })} /></label>
        <label>Playback mode<select value={metadata.playbackMode} onChange={(event) => onMetadataChange({ ...metadata, playbackMode: event.target.value as ExportOptions["playbackMode"] })}>
          <option value="source">Source setting</option>
          <option value="once">Play once</option>
          <option value="loop">Loop</option>
          <option value="server_sync">Server-synchronized loop</option>
        </select></label>
        <label className="checkbox"><input type="checkbox" checked={metadata.hide_player} onChange={(event) => onMetadataChange({ ...metadata, hide_player: event.target.checked })} />Hide original player</label>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      <h3>Animations</h3>
      <ul className="download-list">
        {animations.map((animation, index) => (
          <li key={`${animation.detail}:${index}`}>
            <span><strong>{animation.label}</strong><small>{animation.detail}</small></span>
            <div className="download-actions">
              <button className="primary-button" type="button" onClick={() => onDownloadAnimation(index)}>Download JSON</button>
              {hasResources && <button type="button" onClick={() => onDownloadResourcePack(index)}>Download resource pack</button>}
              {hasResources && (
                <>
                  <label className="button-file-input">Merge into ZIP<input type="file" accept=".zip,application/zip" onChange={selectZip} /></label>
                  <label className="button-file-input">Merge into folder<input type="file" multiple ref={(input) => input?.setAttribute("webkitdirectory", "")} onChange={selectFolder} /></label>
                </>
              )}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
