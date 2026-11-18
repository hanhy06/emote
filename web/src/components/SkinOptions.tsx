import { useRef, useState } from "preact/hooks";
import type { SkinIR } from "../domain/animationIR";
import { createSkinOverride, decodeSkinPng, MAX_SKIN_PNG_BYTES } from "../domain/skin";

type SkinField = "default" | "texture" | "mask";
type Upload = { data?: string; error?: string };

interface SkinOptionsProps {
  skin?: SkinIR;
  disabled: boolean;
  onChange: (skin: SkinIR | undefined) => void;
}

export function SkinOptions({ skin, disabled, onChange }: SkinOptionsProps) {
  const [uploads, setUploads] = useState<Partial<Record<SkinField, Upload>>>({});
  const [errors, setErrors] = useState<{ default?: string; override?: string }>({});
  const maskInput = useRef<HTMLInputElement>(null);

  function selectFile(field: SkinField, file: File | undefined) {
    const group = field === "default" ? "default" : "override";
    setErrors(current => ({ ...current, [group]: undefined }));
    if (!file) {
      setUploads(current => ({ ...current, [field]: undefined }));
      return;
    }
    if (file.size > MAX_SKIN_PNG_BYTES) {
      setUploads(current => ({ ...current, [field]: { error: "Skin PNG must not exceed 1 MiB." } }));
      return;
    }

    const pending: Upload = {};
    setUploads(current => ({ ...current, [field]: pending }));
    const reader = new FileReader();
    const finish = (upload: Upload) => setUploads(current =>
      current[field] === pending ? { ...current, [field]: upload } : current);
    reader.onload = () => finish({ data: String(reader.result).split(",", 2)[1] ?? "" });
    reader.onerror = () => finish({ error: "Could not read the PNG file." });
    reader.readAsDataURL(file);
  }

  function addDefault() {
    const data = uploads.default?.data;
    if (data === undefined) return;
    try {
      decodeSkinPng(data);
      onChange({ ...skin, default: data });
      setUploads(current => ({ ...current, default: undefined }));
    } catch (reason) {
      setErrors(current => ({ ...current, default: reason instanceof Error ? reason.message : "Invalid default skin." }));
    }
  }

  function addOverride() {
    const texture = uploads.texture?.data;
    if (texture === undefined || (uploads.mask && uploads.mask.data === undefined)) return;
    try {
      onChange({ ...skin, override: createSkinOverride(texture, uploads.mask?.data) });
      setUploads(current => ({ ...current, texture: undefined, mask: undefined }));
    } catch (reason) {
      setErrors(current => ({ ...current, override: reason instanceof Error ? reason.message : "Invalid skin override." }));
    }
  }

  function fileField(field: SkinField, label: string) {
    const upload = uploads[field];
    return (
      <div className="skin-file-field">
        <label>{label}
          <input type="file" accept=".png,image/png" disabled={disabled} ref={field === "mask" ? maskInput : undefined}
            onChange={event => selectFile(field, event.currentTarget.files?.[0])} />
          {upload?.error ? <span className="error" role="alert">{upload.error}</span>
            : upload && upload.data === undefined && <span role="status">Reading PNG…</span>}
        </label>
        {field === "mask" && upload && <button type="button" disabled={disabled} onClick={() => {
          if (maskInput.current) maskInput.current.value = "";
          selectFile("mask", undefined);
        }}>Clear selected mask</button>}
      </div>
    );
  }

  return (
    <section className="skin-options" aria-labelledby="skin-options-heading">
      <h3 id="skin-options-heading">Skin</h3>
      <p className="field-hint">Use 64×64 PNGs with classic (wide) arm UVs.</p>
      <fieldset className="skin-option">
        <legend>Default skin</legend>
        {skin?.default !== undefined ? (
          <button type="button" disabled={disabled} onClick={() => onChange(skin.override ? { override: skin.override } : undefined)}>Remove default skin</button>
        ) : (
          <div className="fields">
            {fileField("default", "Skin PNG")}
            <button type="button" disabled={disabled || uploads.default?.data === undefined} onClick={addDefault}>Add default skin</button>
          </div>
        )}
        {errors.default && <p className="error" role="alert">{errors.default}</p>}
      </fieldset>
      <fieldset className="skin-option">
        <legend>Skin override</legend>
        {skin?.override ? (
          <button type="button" disabled={disabled} onClick={() => onChange(skin.default !== undefined ? { default: skin.default } : undefined)}>Remove skin override</button>
        ) : (
          <div className="fields">
            {fileField("texture", "Texture PNG")}
            {fileField("mask", "Mask PNG (optional)")}
            <button type="button" disabled={disabled || uploads.texture?.data === undefined || (uploads.mask !== undefined && uploads.mask.data === undefined)} onClick={addOverride}>Add skin override</button>
            <p className="field-hint">If omitted, the mask is generated from texture transparency.</p>
          </div>
        )}
        {errors.override && <p className="error" role="alert">{errors.override}</p>}
      </fieldset>
    </section>
  );
}
