// Client-side image downscaling for note attachments. Full images cap at
// 1600px (JPEG), previews at 256px; both are checked against NOTE_LIMITS
// before they ever reach the store, so oversized originals shrink instead
// of failing validation downstream.
import { NOTE_LIMITS, type Attachment } from "../../shared/note.js";

const FULL_MAX_DIM = 1600;
const THUMB_MAX_DIM = 256;

/** Decoded byte estimate for a base64 data URL (4/3 inflation). */
export function dataUrlBytes(dataUrl: string): number {
  return Math.ceil((dataUrl.length * 3) / 4);
}

function drawScaled(
  img: CanvasImageSource & { width: number; height: number },
  maxDim: number,
  quality: number,
): string {
  const scale = Math.min(1, maxDim / Math.max(img.width, img.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(img.width * scale));
  canvas.height = Math.max(1, Math.round(img.height * scale));
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("processImage: no 2d canvas context");
  ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
  return canvas.toDataURL("image/jpeg", quality);
}

function loadImage(file: Blob): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      resolve(img);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error("processImage: unreadable image"));
    };
    img.src = url;
  });
}

/**
 * Downscale an image file into an Attachment. Returns null when the file
 * is not an image or still exceeds caps after downscaling (caller shows
 * the rejection; nothing is thrown for user-file problems).
 */
export async function processImageFile(file: File): Promise<Attachment | null> {
  if (!file.type.startsWith("image/")) return null;
  let img: HTMLImageElement;
  try {
    img = await loadImage(file);
  } catch {
    return null;
  }
  let dataUrl = drawScaled(img, FULL_MAX_DIM, 0.85);
  if (dataUrlBytes(dataUrl) > NOTE_LIMITS.maxAttachmentBytes) {
    dataUrl = drawScaled(img, FULL_MAX_DIM, 0.6);
  }
  const thumbUrl = drawScaled(img, THUMB_MAX_DIM, 0.7);
  if (
    dataUrlBytes(dataUrl) > NOTE_LIMITS.maxAttachmentBytes ||
    dataUrlBytes(thumbUrl) > NOTE_LIMITS.maxThumbBytes
  ) {
    return null;
  }
  return {
    id: crypto.randomUUID(),
    name: file.name.slice(0, NOTE_LIMITS.maxAttachmentName) || "photo.jpg",
    mime: "image/jpeg",
    size: dataUrlBytes(dataUrl),
    dataUrl,
    thumbUrl,
  };
}
