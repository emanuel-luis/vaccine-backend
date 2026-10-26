// Copies the vendored IBGE geometries to public/geo/*.geojson (gitignored) after checking each
// gzip against the sha256 in data/sources/manifest.json. Fails loudly on any mismatch.
import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { basename, dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { gunzipSync } from 'node:zlib'

export function sha256(buf) {
  return createHash('sha256').update(buf).digest('hex')
}

/** Throws unless buf hashes to `expected`. */
export function verify(buf, expected, name = 'file') {
  const actual = sha256(buf)
  if (actual !== expected) throw new Error(`${name}: sha256 mismatch (manifest ${expected}, file ${actual})`)
}

/** Verifies and unzips every geo/ entry of the manifest into outDir. Returns the written names. */
export function copyGeo(sourcesDir, outDir) {
  const manifest = JSON.parse(readFileSync(join(sourcesDir, 'manifest.json'), 'utf-8'))
  const entries = manifest.sources.filter((s) => s.path.startsWith('geo/'))
  if (entries.length === 0) throw new Error('manifest has no geo/ entries')
  mkdirSync(outDir, { recursive: true })
  return entries.map((e) => {
    const gz = readFileSync(join(sourcesDir, e.path))
    verify(gz, e.sha256, e.path)
    const name = basename(e.path).replace(/\.gz$/, '')
    writeFileSync(join(outDir, name), gunzipSync(gz))
    return name
  })
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const here = dirname(fileURLToPath(import.meta.url))
  try {
    const names = copyGeo(join(here, '../../data/sources'), join(here, '../public/geo'))
    console.log(`copy-geo: ${names.length} geometries written to public/geo`)
  } catch (err) {
    console.error(`copy-geo FAILED: ${err.message}`)
    process.exit(1)
  }
}
