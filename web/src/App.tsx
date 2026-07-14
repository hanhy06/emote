import { useCallback, useState, type ChangeEvent } from "react";
import { PartPreview } from "./components/PartPreview";
import { loadDatapack, type LoadedDatapack } from "./converter/packFileSystem";
import { findEmoteModels, type ParsedEmoteModel } from "./converter/partParser";
import { SKIN_PARTS, type PartAssignments, type SkinPartId } from "./converter/skinMapping";
import { convertDatapack, sanitizeCommandName, type ConversionOptions } from "./converter/converter";

export function App() {
  const [datapack, setDatapack] = useState<LoadedDatapack | null>(null);
  const [models, setModels] = useState<ParsedEmoteModel[]>([]);
  const [modelIndex, setModelIndex] = useState(0);
  const [assignments, setAssignments] = useState<Record<string, PartAssignments>>({});
  const [selectedParts, setSelectedParts] = useState<Set<number>>(new Set());
  const [metadata, setMetadata] = useState<ConversionOptions>({ name: "", description: "", commandName: "", hidePlayer: true });
  const [conversionError, setConversionError] = useState("");
  const [converting, setConverting] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }

    setLoading(true);
    setError("");
    setDatapack(null);
    setModels([]);
    try {
      const loadedDatapack = await loadDatapack(file);
      const foundModels = findEmoteModels(loadedDatapack);
      if (foundModels.length === 0) {
        throw new Error("호환되는 create.mcfunction에서 player_head 조각을 찾지 못했습니다.");
      }
      setDatapack(loadedDatapack);
      setModels(foundModels);
      setAssignments(Object.fromEntries(foundModels.map((model) => [
        model.namespace,
        Object.fromEntries(model.parts.map((part) => [part.partIndex, part.existingAssignment])),
      ])));
      setModelIndex(0);
      setSelectedParts(new Set());
      const defaultName = prettifyName(file.name.replace(/\.zip$/i, "").replace(/^emote\./i, ""));
      setMetadata({
        name: defaultName,
        description: `${defaultName} emote.`,
        commandName: foundModels.length === 1 ? foundModels[0].namespace : sanitizeCommandName(defaultName),
        hidePlayer: true,
      });
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "ZIP 파일을 읽지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }

  async function handleConvert() {
    if (!datapack) return;
    setConverting(true);
    setConversionError("");
    try {
      const result = await convertDatapack(datapack, models, assignments, metadata);
      const url = URL.createObjectURL(result.blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = result.fileName;
      anchor.click();
      setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (reason) {
      setConversionError(reason instanceof Error ? reason.message : "변환에 실패했습니다.");
    } finally {
      setConverting(false);
    }
  }

  const handlePartSelect = useCallback((partIndex: number, additive: boolean) => {
    const model = models[modelIndex];
    const selectedPart = model?.parts.find((part) => part.partIndex === partIndex);
    if (!model || !selectedPart) return;
    const groupedIndices = model.parts
      .filter((part) => distance(part.anchor, selectedPart.anchor) <= 0.05)
      .map((part) => part.partIndex);

    setSelectedParts((current) => {
      const next = additive ? new Set(current) : new Set<number>();
      const shouldRemove = additive && groupedIndices.every((index) => next.has(index));
      groupedIndices.forEach((index) => shouldRemove ? next.delete(index) : next.add(index));
      return next;
    });
  }, [modelIndex, models]);

  function assignSelected(skinPart: SkinPartId | null) {
    const namespace = models[modelIndex]?.namespace;
    if (!namespace || selectedParts.size === 0) return;
    setAssignments((current) => ({
      ...current,
      [namespace]: {
        ...current[namespace],
        ...Object.fromEntries([...selectedParts].map((partIndex) => [partIndex, skinPart])),
      },
    }));
  }

  return (
    <main className="app-shell">
      <section className="intro">
        <p className="eyebrow">BD ENGINE → EMOTE</p>
        <h1>이모트 조각을 눈으로 확인하고 변환하세요.</h1>
        <p>파일은 서버로 전송되지 않고 이 브라우저 안에서만 처리됩니다.</p>
      </section>

      <section className="upload-card" aria-labelledby="upload-title">
        <div>
          <h2 id="upload-title">BD Engine 데이터팩</h2>
          <p>현재는 ZIP 파일 입력을 지원합니다.</p>
        </div>
        <label className="file-button">
          <span>{loading ? "읽는 중…" : "ZIP 선택"}</span>
          <input type="file" accept=".zip,application/zip" onChange={handleFileChange} disabled={loading} />
        </label>
      </section>

      {error && <p className="notice error" role="alert">{error}</p>}
      {datapack && (
        <section className="notice success" aria-live="polite">
          <strong>{datapack.fileName}</strong>
          <span>데이터팩 루트: {datapack.rootPath || "/"}</span>
          <span>{datapack.files.size.toLocaleString()}개 파일을 찾았습니다.</span>
        </section>
      )}

      {models[modelIndex] && (
        <section className="workspace">
          <div className="workspace-header">
            <div>
              <p className="eyebrow">3D PREVIEW</p>
              <h2>{models[modelIndex].namespace}</h2>
            </div>
            {models.length > 1 && (
              <select value={modelIndex} onChange={(event) => { setModelIndex(Number(event.target.value)); setSelectedParts(new Set()); }}>
                {models.map((model, index) => <option value={index} key={model.namespace}>{model.namespace}</option>)}
              </select>
            )}
          </div>
          <div className="preview-layout">
            <PartPreview
              parts={models[modelIndex].parts}
              assignments={assignments[models[modelIndex].namespace] ?? {}}
              selectedParts={selectedParts}
              onSelectPart={handlePartSelect}
            />
            <aside className="part-list">
              <h3>신체 부위 지정</h3>
              <p>박스를 클릭하면 같은 위치의 조각을 함께 선택합니다.</p>
              <div className="assignment-grid">
                {SKIN_PARTS.map((part) => (
                  <button type="button" key={part.id} disabled={selectedParts.size === 0} onClick={() => assignSelected(part.id)}>
                    <i style={{ backgroundColor: part.color }} />{part.label}
                  </button>
                ))}
                <button type="button" className="unassign" disabled={selectedParts.size === 0} onClick={() => assignSelected(null)}>미지정</button>
              </div>
              <ol>
                {models[modelIndex].parts.map((part) => (
                  <li key={part.partIndex} className={selectedParts.has(part.partIndex) ? "selected" : ""}>
                    <button type="button" onClick={(event) => handlePartSelect(part.partIndex, event.ctrlKey || event.metaKey || event.shiftKey)}>
                      <strong>#{part.partIndex}</strong>
                      <span>{assignmentLabel(assignments[models[modelIndex].namespace]?.[part.partIndex])}</span>
                    </button>
                  </li>
                ))}
              </ol>
            </aside>
          </div>
        </section>
      )}

      {datapack && models.length > 0 && (
        <section className="export-card">
          <div className="export-heading">
            <div>
              <p className="eyebrow">EXPORT</p>
              <h2>이모트 정보</h2>
            </div>
            <p>여러 애니메이션이 있으면 각각 독립된 네임스페이스로 분리됩니다.</p>
          </div>
          <div className="metadata-grid">
            <label>표시 이름<input value={metadata.name} onChange={(event) => setMetadata({ ...metadata, name: event.target.value })} /></label>
            <label>명령어 이름<input value={metadata.commandName} onChange={(event) => setMetadata({ ...metadata, commandName: event.target.value })} /></label>
            <label className="wide">설명<input value={metadata.description} onChange={(event) => setMetadata({ ...metadata, description: event.target.value })} /></label>
            <label className="checkbox wide"><input type="checkbox" checked={metadata.hidePlayer} onChange={(event) => setMetadata({ ...metadata, hidePlayer: event.target.checked })} />재생 중 원래 플레이어 숨기기</label>
          </div>
          {conversionError && <p className="inline-error" role="alert">{conversionError}</p>}
          <div className="export-actions">
            <span>{assignmentSummary(models, assignments)}</span>
            <button type="button" onClick={handleConvert} disabled={converting}>{converting ? "변환 중…" : "Emote ZIP 다운로드"}</button>
          </div>
        </section>
      )}
    </main>
  );
}

function distance(first: { x: number; y: number; z: number }, second: { x: number; y: number; z: number }): number {
  return Math.hypot(first.x - second.x, first.y - second.y, first.z - second.z);
}

function assignmentLabel(assignment: SkinPartId | null | undefined): string {
  return SKIN_PARTS.find((part) => part.id === assignment)?.label ?? "미지정";
}

function prettifyName(value: string): string {
  return value.replaceAll("_", " ").replaceAll("-", " ").trim() || value;
}

function assignmentSummary(models: ParsedEmoteModel[], assignments: Record<string, PartAssignments>): string {
  const assigned = models.reduce((total, model) => total + model.parts.filter((part) => assignments[model.namespace]?.[part.partIndex]).length, 0);
  const total = models.reduce((sum, model) => sum + model.parts.length, 0);
  return `${assigned}/${total}개 조각에 스킨 부위 지정됨`;
}
