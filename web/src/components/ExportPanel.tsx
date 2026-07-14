import type { ConversionOptions } from "../converter/converter";

interface ExportPanelProps {
  metadata: ConversionOptions;
  assignmentSummary: string;
  error: string;
  converting: boolean;
  onMetadataChange: (metadata: ConversionOptions) => void;
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
        <label>Display name<input value={metadata.name} onChange={(event) => onMetadataChange({ ...metadata, name: event.target.value })} /></label>
        <label>Command name<input value={metadata.commandName} onChange={(event) => onMetadataChange({ ...metadata, commandName: event.target.value })} /></label>
        <label>Description<input value={metadata.description} onChange={(event) => onMetadataChange({ ...metadata, description: event.target.value })} /></label>
        <label className="checkbox"><input type="checkbox" checked={metadata.hidePlayer} onChange={(event) => onMetadataChange({ ...metadata, hidePlayer: event.target.checked })} />Hide original player</label>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="export-row">
        <span>{assignmentSummary}</span>
        <button type="button" onClick={onConvert} disabled={converting}>{converting ? "Converting…" : "Download ZIP"}</button>
      </div>
    </section>
  );
}
