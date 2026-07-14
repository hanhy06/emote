import { useState, type ChangeEvent } from "react";
import { PartPreview } from "./components/PartPreview";
import { loadDatapack, type LoadedDatapack } from "./converter/packFileSystem";
import { findEmoteModels, type ParsedEmoteModel } from "./converter/partParser";

export function App() {
  const [datapack, setDatapack] = useState<LoadedDatapack | null>(null);
  const [models, setModels] = useState<ParsedEmoteModel[]>([]);
  const [modelIndex, setModelIndex] = useState(0);
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
      setModelIndex(0);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "ZIP 파일을 읽지 못했습니다.");
    } finally {
      setLoading(false);
    }
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
              <select value={modelIndex} onChange={(event) => setModelIndex(Number(event.target.value))}>
                {models.map((model, index) => <option value={index} key={model.namespace}>{model.namespace}</option>)}
              </select>
            )}
          </div>
          <div className="preview-layout">
            <PartPreview parts={models[modelIndex].parts} />
            <aside className="part-list">
              <h3>감지한 조각</h3>
              <p>{models[modelIndex].parts.length}개의 player_head</p>
              <ol>
                {models[modelIndex].parts.map((part) => (
                  <li key={part.partIndex}>
                    <strong>#{part.partIndex}</strong>
                    <span>{formatVector(part.anchor)}</span>
                  </li>
                ))}
              </ol>
            </aside>
          </div>
        </section>
      )}
    </main>
  );
}

function formatVector(vector: { x: number; y: number; z: number }): string {
  return `${vector.x.toFixed(2)}, ${vector.y.toFixed(2)}, ${vector.z.toFixed(2)}`;
}
