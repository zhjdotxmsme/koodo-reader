#!/usr/bin/env node
/**
 * Generate Readme Reader launcher PNGs from a source logo.
 *
 * Why this exists: the adaptive icon (mipmap-anydpi-v26) covers API 26+, but
 * API 24/25 devices and some launchers still want a raster `mipmap/ic_launcher.png`,
 * and Play Console wants a 512x512 asset. This script rasterises the provided
 * brand logo (deep-navy rounded square + open book + teal folder) into:
 *
 *   android/app/src/main/res/mipmap-mdpi/ic_launcher.png     (48)
 *   android/app/src/main/res/mipmap-hdpi/ic_launcher.png     (72)
 *   android/app/src/main/res/mipmap-xhdpi/ic_launcher.png    (96)
 *   android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png   (144)
 *   android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png  (192)
 *   assets/icons/readme-reader-512.png                       (512, Play Store)
 *
 * Pure Node: PNG decode (8-bit RGB/RGBA, non-interlaced) + zlib + nearest
 * neighbour resample + PNG encode. No npm dependencies, no native tools.
 *
 * Usage:
 *   node scripts/gen-readme-icons.js <source-logo.png>
 *
 * The source should be square (>=512px recommended). Non-square sources are
 * letterboxed onto transparency. The source PNG is NOT committed by this
 * script; drop the brand logo anywhere local and pass its path.
 */

"use strict";

const fs = require("fs");
const path = require("path");
const zlib = require("zlib");

// ---------------------------------------------------------------- CRC32
const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

// ---------------------------------------------------------------- decode
function decodePng(file) {
  const buf = fs.readFileSync(file);
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error("not a PNG file");
  let pos = 8;
  let width = 0;
  let height = 0;
  let bitDepth = 0;
  let colorType = 0;
  let interlace = 0;
  const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString("ascii", pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === "IHDR") {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colorType = data[9];
      interlace = data[12];
    } else if (type === "IDAT") {
      idat.push(data);
    } else if (type === "IEND") {
      break;
    }
    pos += 12 + len;
  }
  if (bitDepth !== 8) throw new Error(`unsupported bit depth ${bitDepth} (need 8)`);
  if (interlace !== 0) throw new Error("interlaced PNG not supported");
  if (colorType !== 6 && colorType !== 2) {
    throw new Error(`unsupported color type ${colorType} (need 2=RGB or 6=RGBA)`);
  }
  const channels = colorType === 6 ? 4 : 3;
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const out = Buffer.alloc(width * height * 4);

  let prev = Buffer.alloc(stride); // previous reconstructed row
  let recon = Buffer.alloc(stride); // current reconstructed row
  const line = Buffer.alloc(stride); // filtered current row
  let p = 0;
  for (let y = 0; y < height; y++) {
    const filter = raw[p++];
    raw.copy(line, 0, p, p + stride);
    p += stride;
    for (let i = 0; i < stride; i++) {
      const a = i >= channels ? recon[i - channels] : 0;
      const b = prev[i];
      const c = i >= channels ? prev[i - channels] : 0;
      let v = line[i];
      switch (filter) {
        case 0: break;
        case 1: v = (v + a) & 0xff; break;
        case 2: v = (v + b) & 0xff; break;
        case 3: v = (v + ((a + b) >> 1)) & 0xff; break;
        case 4: {
          const pa = Math.abs(b - c);
          const pb = Math.abs(a - c);
          const pc = Math.abs(a + b - 2 * c);
          const pred = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
          v = (v + pred) & 0xff;
          break;
        }
        default: throw new Error(`bad filter ${filter}`);
      }
      recon[i] = v;
    }
    for (let x = 0; x < width; x++) {
      const si = x * channels;
      const di = (y * width + x) * 4;
      out[di] = recon[si];
      out[di + 1] = recon[si + 1];
      out[di + 2] = recon[si + 2];
      out[di + 3] = channels === 4 ? recon[si + 3] : 255;
    }
    // prev ← recon, reuse the old prev buffer as the next recon
    const t = prev;
    prev = recon;
    recon = t;
  }
  return { width, height, data: out };
}

// ---------------------------------------------------------------- resample
function resizeNearest(src, sw, sh, dw, dh) {
  const out = Buffer.alloc(dw * dh * 4);
  for (let y = 0; y < dh; y++) {
    const sy = Math.min(sh - 1, Math.floor((y * sh) / dh));
    for (let x = 0; x < dw; x++) {
      const sx = Math.min(sw - 1, Math.floor((x * sw) / dw));
      const si = (sy * sw + sx) * 4;
      const di = (y * dw + x) * 4;
      out[di] = src[si];
      out[di + 1] = src[si + 1];
      out[di + 2] = src[si + 2];
      out[di + 3] = src[si + 3];
    }
  }
  return out;
}

// ---------------------------------------------------------------- encode
function encodePng(width, height, rgba) {
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA
  // raw scanlines with filter byte 0
  const raw = Buffer.alloc(height * (width * 4 + 1));
  for (let y = 0; y < height; y++) {
    const ro = y * (width * 4 + 1);
    raw[ro] = 0;
    rgba.copy(raw, ro + 1, y * width * 4, (y + 1) * width * 4);
  }
  const idat = zlib.deflateSync(raw, { level: 9 });
  return Buffer.concat([
    sig,
    chunk("IHDR", ihdr),
    chunk("IDAT", idat),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

// ---------------------------------------------------------------- main
function main() {
  const src = process.argv[2];
  if (!src || src === "-h" || src === "--help") {
    console.log("usage: node scripts/gen-readme-icons.js <source-logo.png>");
    process.exit(src ? 0 : 2);
  }
  const repo = path.resolve(__dirname, "..");
  const img = decodePng(path.resolve(src));

  const square = Math.min(img.width, img.height);
  const cropped = Buffer.alloc(square * square * 4);
  {
    const ox = Math.floor((img.width - square) / 2);
    const oy = Math.floor((img.height - square) / 2);
    for (let y = 0; y < square; y++) {
      const si = ((oy + y) * img.width + ox) * 4;
      img.data.copy(cropped, y * square * 4, si, si + square * 4);
    }
  }

  const targets = [
    ["android/app/src/main/res/mipmap-mdpi/ic_launcher.png", 48],
    ["android/app/src/main/res/mipmap-hdpi/ic_launcher.png", 72],
    ["android/app/src/main/res/mipmap-xhdpi/ic_launcher.png", 96],
    ["android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png", 144],
    ["android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png", 192],
    ["assets/icons/readme-reader-512.png", 512],
  ];
  for (const [rel, size] of targets) {
    const dest = path.join(repo, rel);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    const resized = resizeNearest(cropped, square, square, size, size);
    fs.writeFileSync(dest, encodePng(size, size, resized));
    console.log(`wrote ${rel} (${size}x${size})`);
  }
}

main();
