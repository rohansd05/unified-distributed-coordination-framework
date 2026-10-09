/**
 * The browser side of the "uploaded .txt" input. The file is read into memory only for the one
 * POST that sends it (never into localStorage, never written anywhere), checked the way the
 * backend checks it, and sent as the JSON/Base64 body the API expects. The backend checks
 * everything again and its 400/413 messages are shown as they come.
 */

const TXT = /\.txt$/i
const CHUNK = 0x8000

/** A problem found in the browser, before anything was sent. */
export class UploadError extends Error {
  constructor(message) {
    super(message)
    this.name = 'UploadError'
  }
}

/** "300 bytes", "5.5 KiB", "256 KiB", "1 MiB". */
export function formatBytes(bytes) {
  if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes < 0) {
    return '—'
  }
  if (bytes < 1024) {
    return `${bytes} ${bytes === 1 ? 'byte' : 'bytes'}`
  }
  const [value, unit] = bytes < 1024 * 1024 ? [bytes / 1024, 'KiB'] : [bytes / (1024 * 1024), 'MiB']
  const rounded = Math.round(value * 10) / 10
  return `${Number.isInteger(rounded) ? rounded : rounded.toFixed(1)} ${unit}`
}

/**
 * Checks a chosen file before it is read: a .txt name, not empty, and not over the cap the
 * overview reports. Returns the problem as a sentence, or null when the file may be sent.
 *
 * @param {File|null} file
 * @param {number|null} maxBytes limits.uploadMaxBytes from the overview
 */
export function checkUpload(file, maxBytes) {
  if (!file) {
    return 'Choose a .txt file first.'
  }
  if (!TXT.test(file.name ?? '')) {
    return 'Only .txt files can be uploaded.'
  }
  if (file.size === 0) {
    return 'The file is empty.'
  }
  if (typeof maxBytes === 'number' && file.size > maxBytes) {
    return `The file is ${formatBytes(file.size)}, larger than the ${formatBytes(maxBytes)} limit.`
  }
  return null
}

function readBytes(file) {
  if (typeof file.arrayBuffer === 'function') {
    return file.arrayBuffer()
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.onerror = () => reject(reader.error)
    reader.readAsArrayBuffer(file)
  })
}

/** Standard Base64 of the bytes, built in chunks so a 1 MiB file does not overflow the call stack. */
export function toBase64(bytes) {
  let binary = ''
  for (let start = 0; start < bytes.length; start += CHUNK) {
    binary += String.fromCharCode.apply(null, bytes.subarray(start, start + CHUNK))
  }
  return btoa(binary)
}

/**
 * Reads a checked file and returns the upload body.
 *
 * @throws {UploadError} when the bytes are not valid UTF-8
 * @returns {Promise<{ fileName: string, contentType: string, contentBase64: string, bytes: number }>}
 */
export async function readUpload(file) {
  const bytes = new Uint8Array(await readBytes(file))
  try {
    new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    throw new UploadError('The file is not valid UTF-8 text.')
  }
  return {
    fileName: file.name,
    contentType: file.type || '',
    contentBase64: toBase64(bytes),
    bytes: bytes.length,
  }
}
