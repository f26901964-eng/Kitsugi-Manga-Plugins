// Small PNG toolkit: build synthetic pages, decode real WebP-transcoded pages, and
// copy pixel tiles. Node has no image decoder, so the runtime test keeps everything
// in plain RGBA buffers and only uses PNG for the synthetic shapes it creates itself.
import zlib from 'node:zlib';

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

function crc32(buf) {
  let table = crc32.table;
  if (!table) {
    table = crc32.table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c;
    }
  }
  let crc = -1;
  for (let i = 0; i < buf.length; i++) crc = (crc >>> 8) ^ table[(crc ^ buf[i]) & 0xff];
  return (crc ^ -1) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

export function encodePng(width, height, rgba) {
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0;
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([
    SIGNATURE,
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

export function decodePng(buffer) {
  let offset = 8;
  let width = 0;
  let height = 0;
  let bitDepth = 0;
  let colorType = 0;
  const idat = [];
  while (offset < buffer.length) {
    const len = buffer.readUInt32BE(offset);
    const type = buffer.toString('ascii', offset + 4, offset + 8);
    const data = buffer.subarray(offset + 8, offset + 8 + len);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colorType = data[9];
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    offset += 12 + len;
  }
  if (bitDepth !== 8 || (colorType !== 6 && colorType !== 2)) {
    throw new Error(`unsupported png: depth=${bitDepth} color=${colorType}`);
  }
  const channels = colorType === 6 ? 4 : 3;
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const out = Buffer.alloc(width * height * 4);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride));
    for (let i = 0; i < stride; i++) {
      const left = i >= channels ? line[i - channels] : 0;
      const up = prev[i];
      const upLeft = i >= channels ? prev[i - channels] : 0;
      let value = line[i];
      if (filter === 1) value += left;
      else if (filter === 2) value += up;
      else if (filter === 3) value += (left + up) >> 1;
      else if (filter === 4) {
        const p = left + up - upLeft;
        const pa = Math.abs(p - left);
        const pb = Math.abs(p - up);
        const pc = Math.abs(p - upLeft);
        value += pa <= pb && pa <= pc ? left : pb <= pc ? up : upLeft;
      }
      line[i] = value & 0xff;
    }
    for (let x = 0; x < width; x++) {
      const src = x * channels;
      const dst = (y * width + x) * 4;
      out[dst] = line[src];
      out[dst + 1] = line[src + 1];
      out[dst + 2] = line[src + 2];
      out[dst + 3] = channels === 4 ? line[src + 3] : 255;
    }
    prev = line;
  }
  return { width, height, data: out };
}

// A page whose tiles are all visually distinct, so a wrong unscramble is obvious.
export function syntheticPage(width, height, grid) {
  const data = Buffer.alloc(width * height * 4);
  const tileW = Math.max(1, Math.floor(width / grid));
  const tileH = Math.max(1, Math.floor(height / grid));
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const tx = Math.min(grid - 1, Math.floor(x / tileW));
      const ty = Math.min(grid - 1, Math.floor(y / tileH));
      const i = (y * width + x) * 4;
      data[i] = (tx * 23 + 7) & 0xff;
      data[i + 1] = (ty * 31 + 11) & 0xff;
      data[i + 2] = ((tx * 17 + ty * 13) ^ 0x5a) & 0xff;
      data[i + 3] = 255;
    }
  }
  return { width, height, data };
}

// Copy `srcRect` of `src` into `dstRect` of `dst` (nearest neighbour, one-to-one when
// both rectangles have identical sizes, which is the case for the descrambler tiles).
export function blit(src, dst, srcRect, dstRect) {
  for (let dy = 0; dy < dstRect.h; dy++) {
    const sy = srcRect.y + Math.floor((dy * srcRect.h) / dstRect.h);
    for (let dx = 0; dx < dstRect.w; dx++) {
      const sx = srcRect.x + Math.floor((dx * srcRect.w) / dstRect.w);
      const s = (sy * src.width + sx) * 4;
      const d = ((dstRect.y + dy) * dst.width + (dstRect.x + dx)) * 4;
      dst.data[d] = src.data[s];
      dst.data[d + 1] = src.data[s + 1];
      dst.data[d + 2] = src.data[s + 2];
      dst.data[d + 3] = src.data[s + 3];
    }
  }
}

export function tileStats(image, grid) {
  const { width, height, data } = image;
  // Mean per-tile colour + a cheap signature, used to compare two renders.
  const tileW = Math.max(1, Math.floor(width / grid));
  const tileH = Math.max(1, Math.floor(height / grid));
  const stats = [];
  for (let ty = 0; ty < grid; ty++) {
    for (let tx = 0; tx < grid; tx++) {
      let r = 0;
      let g = 0;
      let b = 0;
      let n = 0;
      for (let y = ty * tileH; y < Math.min(height, (ty + 1) * tileH); y += 3) {
        for (let x = tx * tileW; x < Math.min(width, (tx + 1) * tileW); x += 3) {
          const i = (y * width + x) * 4;
          r += data[i];
          g += data[i + 1];
          b += data[i + 2];
          n++;
        }
      }
      stats.push(n ? [Math.round(r / n), Math.round(g / n), Math.round(b / n)] : [0, 0, 0]);
    }
  }
  return stats;
}

export function pngToRgba(buffer) {
  return decodePng(buffer);
}
