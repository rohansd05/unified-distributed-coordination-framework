import { describe, expect, it } from 'vitest'
import { checkUpload, formatBytes, readUpload, toBase64, UploadError } from './uploadFile'

const file = (content, name = 'notes.txt', type = 'text/plain') => new File([content], name, { type })

/** Reference Base64, byte by byte (no Node Buffer: the code under test runs in a browser). */
function referenceBase64(bytes) {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary)
}
const utf8 = (text) => new TextEncoder().encode(text)

describe('checkUpload (before anything is read or sent)', () => {
  it('accepts a .txt file within the cap, at exactly the cap too', () => {
    expect(checkUpload(file('hello'), 1024)).toBeNull()
    expect(checkUpload(file('12345'), 5)).toBeNull()
    expect(checkUpload(file('x', 'NOTES.TXT'), 5)).toBeNull()
  })

  it('refuses a file over the cap the overview reports, naming both sizes', () => {
    expect(checkUpload(file(new Uint8Array(2048)), 1024)).toBe('The file is 2 KiB, larger than the 1 KiB limit.')
    expect(checkUpload(file('123456'), 5)).toBe('The file is 6 bytes, larger than the 5 bytes limit.')
  })

  it('refuses no file, a name not ending in .txt, and an empty file', () => {
    expect(checkUpload(null, 10)).toBe('Choose a .txt file first.')
    expect(checkUpload(file('a,b', 'data.csv'), 10)).toBe('Only .txt files can be uploaded.')
    expect(checkUpload(file(''), 10)).toBe('The file is empty.')
  })

  it('does not invent a cap when the overview has none', () => {
    expect(checkUpload(file(new Uint8Array(5000)), null)).toBeNull()
  })
})

describe('readUpload', () => {
  it('returns the exact Base64 of the bytes, the name, the browser type and the size', async () => {
    const upload = await readUpload(file('naïve café\n'))
    expect(upload).toEqual({
      fileName: 'notes.txt',
      contentType: 'text/plain',
      contentBase64: referenceBase64(utf8('naïve café\n')),
      bytes: utf8('naïve café\n').length,
    })
  })

  it('sends an empty content type when the browser gives none (the backend accepts that)', async () => {
    expect((await readUpload(file('x', 'a.txt', ''))).contentType).toBe('')
  })

  it('refuses bytes that are not UTF-8 with a clear message', async () => {
    await expect(readUpload(file(new Uint8Array([104, 105, 255])))).rejects.toThrow(new UploadError('The file is not valid UTF-8 text.'))
  })

  it('encodes a large file in chunks without overflowing the stack', () => {
    const bytes = new Uint8Array(300000).map((_, i) => i % 256)
    expect(toBase64(bytes)).toBe(referenceBase64(bytes))
  })
})

describe('formatBytes', () => {
  it('uses bytes, KiB and MiB, and "—" for nothing', () => {
    expect(formatBytes(1)).toBe('1 byte')
    expect(formatBytes(1048576)).toBe('1 MiB')
    expect(formatBytes(262144)).toBe('256 KiB')
    expect(formatBytes(5603)).toBe('5.5 KiB')
    expect(formatBytes(null)).toBe('—')
  })
})
