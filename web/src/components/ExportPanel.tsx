import type { ExportOptions } from "../export/projectExporter";

interface ExportPanelProps {
  metadata: ExportOptions;
  assignmentSummary: string;
  error: string;
  converting: boolean;
  onMetadataChange: (metadata: ExportOptions) => void;
  onConvert: () => void;
}

export function ExportPanel({
  metadata,
  assignmentSummary,
  error,
  converting,
  onMetadataChange,
  onConvert,
}: ExportPanelProps) {
  return (
    <section className="export">
      <h2>Export</h2>
      <div className="fields">
        <label>Minecraft version<input value={metadata.minecraftVersion} onChange={(event) => onMetadataChange({ ...metadata, minecraftVersion: event.target.value })} /></label>
        <label>Namespace<input value={metadata.namespace} onChange={(event) => onMetadataChange({ ...metadata, namespace: event.target.value })} /></label>
        <label>Display name<input value={metadata.name} onChange={(event) => onMetadataChange({ ...metadata, name: event.target.value })} /></label>
        <label>Command name<input value={metadata.command_name} onChange={(event) => onMetadataChange({ ...metadata, command_name: event.target.value })} /></label>
        <label>Description<input value={metadata.description} onChange={(event) => onMetadataChange({ ...metadata, description: event.target.value })} /></label>
        <label className="checkbox"><input type="checkbox" checked={metadata.hide_player} onChange={(event) => onMetadataChange({ ...metadata, hide_player: event.target.checked })} />Hide original player</label>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="export-row">
        <span>{assignmentSummary}</span>
        <button type="button" onClick={onConvert} disabled={converting}>{converting ? "Converting…" : "Download ZIP"}</button>
      </div>
    </section>
  );
}
