import { useLayoutEffect, useMemo, useRef, useState } from "preact/hooks";
import type { SkinIR } from "../domain/animationIR";
import { composeSkin, createSkinOverride, decodeSkinPng, encodeSkinPng } from "../domain/skin";

interface SkinSettingsProps {
  skin?: SkinIR;
  disabled: boolean;
  onChange: (skin: SkinIR | undefined) => void;
}

export function SkinSettings({ skin, disabled, onChange }: SkinSettingsProps) {
  const latest = useRef({ skin, onChange });
  latest.current = { skin, onChange };
  const active = useRef(true);
  const [reading, setReading] = useState(false);
  const [error, setError] = useState("");
  const preview = useMemo(() => {
    try { return { image: skin ? encodeSkinPng(composeSkin(skin)) : undefined, error: "" }; }
    catch (reason) { return { image: undefined, error: reason instanceof Error ? reason.message : String(reason) }; }
  }, [skin]);
  useLayoutEffect(() => {
    active.current = true;
    return () => { active.current = false; };
  }, []);

  function update(next: SkinIR) {
    latest.current.onChange(next.default || next.override ? next : undefined);
    setError("");
  }

  async function upload(field: "default" | "override" | "mask", file: File | undefined) {
    if (!file) return;
    setReading(true);
    setError("");
    try {
      if (file.size > 1_048_576) throw new Error("Skin PNG must not exceed 1 MiB.");
      const bytes = new Uint8Array(await file.arrayBuffer());
      if (!active.current) return;
      const base64 = btoa(Array.from(bytes, (byte) => String.fromCharCode(byte)).join(""));
      const current = latest.current.skin ?? {};
      if (field === "default") {
        decodeSkinPng(base64);
        update({ ...current, default: base64 });
      } else if (field === "override") {
        update({ ...current, override: createSkinOverride(base64) });
      } else if (current.override) {
        update({ ...current, override: createSkinOverride(current.override.texture, base64) });
      }
    } catch (reason) {
      if (active.current) setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      if (active.current) setReading(false);
    }
  }

  return (
    <section className="skin-settings" aria-labelledby="skin-heading">
      <h3 id="skin-heading">Skin</h3>
      <p>Use 64×64 PNGs with classic arm UVs. A complete player skin takes priority over the default skin. The override applies to either.</p>
      <div className="fields">
        <div className="skin-input">
          <label>Default skin<input type="file" accept="image/png,.png" disabled={disabled || reading} onChange={(event) => {
            void upload("default", event.currentTarget.files?.[0]);
            event.currentTarget.value = "";
          }} /></label>
          <small>{skin?.default ? "Default skin included." : "No default skin included."}</small>
          <button type="button" disabled={disabled || reading || !skin?.default} onClick={() => update({ ...skin, default: undefined })}>Remove default</button>
        </div>
        <div className="skin-input">
          <label>Override<input type="file" accept="image/png,.png" disabled={disabled || reading} onChange={(event) => {
            void upload("override", event.currentTarget.files?.[0]);
            event.currentTarget.value = "";
          }} /></label>
          <small>Uploading an override creates a mask from its visible pixels.</small>
          <button type="button" disabled={disabled || reading || !skin?.override} onClick={() => update({ ...skin, override: undefined })}>Remove override</button>
        </div>
        <div className="skin-input">
          <label>Override mask<input type="file" accept="image/png,.png" disabled={disabled || reading || !skin?.override} onChange={(event) => {
            void upload("mask", event.currentTarget.files?.[0]);
            event.currentTarget.value = "";
          }} /></label>
          <small>White replaces pixels; black or transparent preserves them. A white mask can erase the outer layer.</small>
          <button type="button" disabled={disabled || reading || !skin?.override} onClick={() => {
            try { update({ ...skin, override: createSkinOverride(skin!.override!.texture) }); }
            catch (reason) { setError(reason instanceof Error ? reason.message : String(reason)); }
          }}>Generate mask from override</button>
        </div>
      </div>
      {(error || preview.error) && <p className="error" role="alert">{error || preview.error}</p>}
      {preview.image && <figure className="skin-atlas-preview">
        <img src={`data:image/png;base64,${preview.image}`} width="192" height="192" alt="Skin with the override applied, including the inner and outer layers" />
        <figcaption>{skin?.default ? "Default skin with override" : "Override on a gray preview skin"}</figcaption>
      </figure>}
    </section>
  );
}
