import type { ExportOptions } from "../export/projectExporter";

interface DownloadItem {
  label: string;
  detail: string;
}

interface ExportPanelProps {
  metadata: ExportOptions;
  assignmentSummary: string;
  animations: DownloadItem[];
  resources: DownloadItem[];
  error: string;
  onMetadataChange: (metadata: ExportOptions) => void;
  onDownloadAnimation: (index: number) => void;
  onDownloadResource: (index: number) => void;
}

export function ExportPanel({
  metadata,
  assignmentSummary,
  animations,
  resources,
  error,
  onMetadataChange,
  onDownloadAnimation,
  onDownloadResource,
}: ExportPanelProps) {
  return (
    <section className="export">
      <h2>Export</h2>
      <div className="fields">
        <label>Minecraft version<input value={metadata.minecraftVersion} onChange={(event) => onMetadataChange({ ...metadata, minecraftVersion: event.target.value })} /></label>
        <label>Namespace<input value={metadata.namespace} onChange={(event) => onMetadataChange({ ...metadata, namespace: event.target.value })} /></label>
        <label>Display name<input value={metadata.name} onChange={(event) => onMetadataChange({ ...metadata, name: event.target.value })} /></label>
        <label>Description<input value={metadata.description} onChange={(event) => onMetadataChange({ ...metadata, description: event.target.value })} /></label>
        <label className="checkbox"><input type="checkbox" checked={metadata.hide_player} onChange={(event) => onMetadataChange({ ...metadata, hide_player: event.target.checked })} />Hide original player</label>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      <p>{assignmentSummary}</p>
      <h3>Animations</h3>
      <ul className="download-list">
        {animations.map((animation, index) => (
          <li key={`${animation.detail}:${index}`}>
            <span><strong>{animation.label}</strong><small>{animation.detail}</small></span>
            <button type="button" onClick={() => onDownloadAnimation(index)}>Download JSON</button>
          </li>
        ))}
      </ul>
      {resources.length > 0 && (
        <>
          <h3>Animated Java model resources</h3>
          <p className="resource-note">Download each file and place it at the displayed path in the server resource pack.</p>
          <ul className="download-list resource-list">
            {resources.map((resource, index) => (
              <li key={resource.detail}>
                <span><strong>{resource.label}</strong><small>{resource.detail}</small></span>
                <button type="button" onClick={() => onDownloadResource(index)}>Download file</button>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
