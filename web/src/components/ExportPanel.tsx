import type { TargetedEvent } from "preact";
import { useEffect, useState } from "preact/hooks";

interface DownloadItem {
  label: string;
  detail: string;
}

interface ExportPanelProps {
  assignmentSummary: string;
  animations: DownloadItem[];
  hasResources: boolean;
  error: string;
  disabled: boolean;
  onDownloadAnimation: (index: number) => void;
  onDownloadAllAnimations: () => void;
  onDownloadSequence: () => void;
  onDownloadResourcePack: (index: number) => void;
  onMergeResourcePackZip: (file: File) => void;
  onMergeResourcePackFolder: (files: File[]) => void;
}

export function ExportPanel({
  assignmentSummary,
  animations,
  hasResources,
  error,
  disabled,
  onDownloadAnimation,
  onDownloadAllAnimations,
  onDownloadSequence,
  onDownloadResourcePack,
  onMergeResourcePackZip,
  onMergeResourcePackFolder,
}: ExportPanelProps) {
  const [mergeMenuIndex, setMergeMenuIndex] = useState<number | null>(null);
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

  return (
    <section className="export">
      <div className="section-heading export-heading">
        <div>
          <span className="step-label">Page 3</span>
          <h2>Output</h2>
          <p>Download one animation or package all animations together.</p>
        </div>
        <span className="summary-badge">{assignmentSummary}</span>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      {animations.length > 1 && <div className="bundle-actions">
        <button type="button" disabled={disabled} onClick={onDownloadAllAnimations}>Download all JSON as ZIP</button>
        <button className="primary-button" type="button" disabled={disabled} onClick={onDownloadSequence}>Download sequence ZIP</button>
      </div>}
      <h3>Animations</h3>
      <ul className="download-list">
        {animations.map((animation, index) => (
          <li key={`${animation.detail}:${index}`}>
            <span><strong>{animation.label}</strong><small>{animation.detail}</small></span>
            <div className="download-actions">
              <button className="primary-button" type="button" disabled={disabled} onClick={() => onDownloadAnimation(index)}>Download JSON</button>
              {hasResources && (
                <div className="resource-pack-action">
                  <div className="resource-pack-button">
                    <button type="button" disabled={disabled} onClick={() => {
                      setMergeMenuIndex(null);
                      onDownloadResourcePack(index);
                    }}>Download resource pack</button>
                    <button
                      className="split-menu-toggle"
                      type="button"
                      disabled={disabled}
                      aria-label="Resource pack merge options"
                      aria-haspopup="menu"
                      aria-expanded={mergeMenuIndex === index}
                      onPointerDown={(event) => event.stopPropagation()}
                      onClick={() => setMergeMenuIndex((current) => current === index ? null : index)}
                    >▼</button>
                  </div>
                  {mergeMenuIndex === index && (
                    <div className="merge-menu" role="menu" onPointerDown={(event) => event.stopPropagation()}>
                      <label className="button-file-input">Merge into ZIP<input type="file" accept=".zip,application/zip" disabled={disabled} onChange={selectZip} /></label>
                      <label className="button-file-input">Merge into folder<input type="file" multiple disabled={disabled} ref={(input) => input?.setAttribute("webkitdirectory", "")} onChange={selectFolder} /></label>
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
