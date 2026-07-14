import { useCallback, useState, type ChangeEvent } from "react";
import { PartPreview } from "./components/PartPreview";
import { convertDatapack, sanitizeCommandName, type ConversionOptions } from "./converter/converter";
import { loadDatapack, type LoadedDatapack } from "./converter/packFileSystem";
import { findEmoteModels, type ParsedEmoteModel } from "./converter/partParser";
import { SKIN_PARTS, type PartAssignments, type SkinPartId } from "./converter/skinMapping";

export function App() {
  const [datapack, setDatapack] = useState<LoadedDatapack | null>(null);
  const [models, setModels] = useState<ParsedEmoteModel[]>([]);
  const [modelIndex, setModelIndex] = useState(0);
  const [assignments, setAssignments] = useState<Record<string, PartAssignments>>({});
  const [selectedParts, setSelectedParts] = useState<Set<number>>(new Set());
  const [metadata, setMetadata] = useState<ConversionOptions>({ name: "", description: "", commandName: "", hidePlayer: true });
  const [error, setError] = useState("");
  const [conversionError, setConversionError] = useState("");
  const [loading, setLoading] = useState(false);
  const [converting, setConverting] = useState(false);

  async function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;

    setLoading(true);
    setError("");
    setDatapack(null);
    setModels([]);
    try {
      const loadedDatapack = await loadDatapack(file);
      const foundModels = findEmoteModels(loadedDatapack);
      if (foundModels.length === 0) throw new Error("create.mcfunction에서 player_head 조각을 찾지 못했습니다.");

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

  const model = models[modelIndex];
  const modelAssignments = model ? assignments[model.namespace] ?? {} : {};

  return (
    <main className="app">
      <header>
        <h1>Emote Converter</h1>
        <label className="file-input">BD Engine ZIP<input type="file" accept=".zip,application/zip" onChange={handleFileChange} disabled={loading} /></label>
      </header>

      {loading && <p>파일 읽는 중…</p>}
      {error && <p className="error" role="alert">{error}</p>}

      {datapack && model && (
        <>
          <div className="status">
            <strong>{datapack.fileName}</strong>
            <span>{model.parts.length}개 조각</span>
            {model.previewAnimation && <span>{model.previewAnimation} 첫 프레임</span>}
            {models.length > 1 && (
              <select value={modelIndex} onChange={(event) => { setModelIndex(Number(event.target.value)); setSelectedParts(new Set()); }}>
                {models.map((item, index) => <option value={index} key={item.namespace}>{item.namespace}</option>)}
              </select>
            )}
          </div>

          <section className="editor">
            <PartPreview parts={model.parts} assignments={modelAssignments} selectedParts={selectedParts} onSelectPart={handlePartSelect} />
            <aside>
              <p><strong>좌우 기준:</strong> 캐릭터를 뒤에서 바라본 방향입니다. 정면에서는 왼쪽과 오른쪽이 반대로 보입니다.</p>
              <p>박스를 클릭하면 같은 위치의 조각도 함께 선택됩니다.</p>
              <div className="assignment-buttons">
                {SKIN_PARTS.map((part) => (
                  <button type="button" key={part.id} disabled={selectedParts.size === 0} onClick={() => assignSelected(part.id)}>
                    <i style={{ backgroundColor: part.color }} />{part.label}
                  </button>
                ))}
                <button type="button" disabled={selectedParts.size === 0} onClick={() => assignSelected(null)}>미지정</button>
              </div>
              <ul className="part-list">
                {model.parts.map((part) => (
                  <li key={part.partIndex}>
                    <button type="button" className={selectedParts.has(part.partIndex) ? "selected" : ""} onClick={(event) => handlePartSelect(part.partIndex, event.ctrlKey || event.metaKey || event.shiftKey)}>
                      <span>#{part.partIndex}</span><span>{assignmentLabel(modelAssignments[part.partIndex])}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </aside>
          </section>

          <section className="export">
            <h2>내보내기</h2>
            <div className="fields">
              <label>표시 이름<input value={metadata.name} onChange={(event) => setMetadata({ ...metadata, name: event.target.value })} /></label>
              <label>명령어 이름<input value={metadata.commandName} onChange={(event) => setMetadata({ ...metadata, commandName: event.target.value })} /></label>
              <label>설명<input value={metadata.description} onChange={(event) => setMetadata({ ...metadata, description: event.target.value })} /></label>
              <label className="checkbox"><input type="checkbox" checked={metadata.hidePlayer} onChange={(event) => setMetadata({ ...metadata, hidePlayer: event.target.checked })} />원래 플레이어 숨기기</label>
            </div>
            {conversionError && <p className="error" role="alert">{conversionError}</p>}
            <div className="export-row"><span>{assignmentSummary(models, assignments)}</span><button type="button" onClick={handleConvert} disabled={converting}>{converting ? "변환 중…" : "ZIP 다운로드"}</button></div>
          </section>
        </>
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
  const assigned = models.reduce((total, item) => total + item.parts.filter((part) => assignments[item.namespace]?.[part.partIndex]).length, 0);
  const total = models.reduce((sum, item) => sum + item.parts.length, 0);
  return `${assigned}/${total}개 지정됨`;
}
