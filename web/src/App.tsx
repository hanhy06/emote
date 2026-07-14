import { useState, type ChangeEvent } from "react";
import { loadDatapack, type LoadedDatapack } from "./converter/packFileSystem";

export function App() {
  const [datapack, setDatapack] = useState<LoadedDatapack | null>(null);
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
    try {
      setDatapack(await loadDatapack(file));
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
    </main>
  );
}
