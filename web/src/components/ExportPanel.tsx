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
      <h2>내보내기</h2>
      <div className="fields">
        <label>표시 이름<input value={metadata.name} onChange={(event) => onMetadataChange({ ...metadata, name: event.target.value })} /></label>
        <label>명령어 이름<input value={metadata.commandName} onChange={(event) => onMetadataChange({ ...metadata, commandName: event.target.value })} /></label>
        <label>설명<input value={metadata.description} onChange={(event) => onMetadataChange({ ...metadata, description: event.target.value })} /></label>
        <label className="checkbox"><input type="checkbox" checked={metadata.hidePlayer} onChange={(event) => onMetadataChange({ ...metadata, hidePlayer: event.target.checked })} />원래 플레이어 숨기기</label>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="export-row">
        <span>{assignmentSummary}</span>
        <button type="button" onClick={onConvert} disabled={converting}>{converting ? "변환 중…" : "ZIP 다운로드"}</button>
      </div>
    </section>
  );
}
