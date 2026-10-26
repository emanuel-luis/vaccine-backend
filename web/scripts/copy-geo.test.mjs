import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { gzipSync } from 'node:zlib'
import { describe, expect, it } from 'vitest'
import { copyGeo, sha256, verify } from './copy-geo.mjs'

function fixture(sha) {
  const dir = mkdtempSync(join(tmpdir(), 'geo-'))
  mkdirSync(join(dir, 'geo'))
  const gz = gzipSync('{"type":"FeatureCollection","features":[]}')
  writeFileSync(join(dir, 'geo', 'tiny.geojson.gz'), gz)
  const sources = [
    { path: 'geo/tiny.geojson.gz', sha256: sha ?? sha256(gz) },
    { path: 'x.csv.gz', sha256: 'ignored' },
  ]
  writeFileSync(join(dir, 'manifest.json'), JSON.stringify({ sources }))
  return dir
}

describe('copy-geo', () => {
  it('verify accepts a matching hash and rejects a different one', () => {
    const buf = Buffer.from('abc')
    expect(() => verify(buf, sha256(buf))).not.toThrow()
    expect(() => verify(buf, '0'.repeat(64), 'abc')).toThrow(/sha256 mismatch/)
  })

  it('unzips verified geo entries and ignores the others', () => {
    const out = mkdtempSync(join(tmpdir(), 'out-'))
    expect(copyGeo(fixture(), out)).toEqual(['tiny.geojson'])
    expect(readFileSync(join(out, 'tiny.geojson'), 'utf-8')).toContain('FeatureCollection')
  })

  it('fails on a manifest hash mismatch', () => {
    expect(() => copyGeo(fixture('f'.repeat(64)), mkdtempSync(join(tmpdir(), 'out-')))).toThrow(/mismatch/)
  })
})
