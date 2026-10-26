import { Map as MapLibreMap, type MapLayerMouseEvent } from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { useEffect, useRef, useState } from 'react'
import { type AreaState, bbox, OUTSIDE_COLOR } from './scale'

export interface Geo {
  type: 'FeatureCollection'
  features: { type: 'Feature'; properties: { codarea: string }; geometry: { coordinates: never } | null }[]
}

export interface MapCanvasProps {
  geo: Geo
  states: Record<string, AreaState>
  selectedCode?: string
  label: string
  onPick: (code: string) => void
  onHover: (code: string | null) => void
}

const SOURCE = 'areas'

/** 8x8 diagonal hatch, drawn over suppressed areas. */
function hatchImage() {
  const data = new Uint8Array(8 * 8 * 4)
  for (let y = 0; y < 8; y++)
    for (let x = 0; x < 8; x++) if ((x + y) % 8 < 2) data.set([59, 70, 88, 255], (y * 8 + x) * 4)
  return { width: 8, height: 8, data }
}

/** MapLibre canvas: GeoJSON only, no basemap tiles. Loaded lazily by ChoroplethMap. */
export default function MapCanvas({ geo, states, selectedCode, label, onPick, onHover }: MapCanvasProps) {
  const box = useRef<HTMLDivElement>(null)
  const mapRef = useRef<MapLibreMap | null>(null)
  const cb = useRef({ onPick, onHover })
  const [ready, setReady] = useState(false)

  useEffect(() => {
    cb.current = { onPick, onHover }
  })

  useEffect(() => {
    if (!box.current) return
    const map = new MapLibreMap({
      container: box.current,
      style: { version: 8, sources: {}, layers: [] },
      attributionControl: false,
      dragRotate: false,
      pitchWithRotate: false,
      renderWorldCopies: false,
    })
    map.touchZoomRotate.disableRotation()
    map.on('load', () => {
      map.addImage('hatch', hatchImage())
      map.addSource(SOURCE, { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, promoteId: 'codarea' })
      map.addLayer({
        id: 'fill',
        type: 'fill',
        source: SOURCE,
        paint: { 'fill-color': ['coalesce', ['feature-state', 'color'], OUTSIDE_COLOR] },
      })
      map.addLayer({
        id: 'hatch',
        type: 'fill',
        source: SOURCE,
        paint: {
          'fill-pattern': 'hatch',
          'fill-opacity': ['case', ['boolean', ['feature-state', 'suppressed'], false], 1, 0],
        },
      })
      map.addLayer({
        id: 'outline',
        type: 'line',
        source: SOURCE,
        paint: {
          'line-color': '#5b6678',
          'line-width': ['case', ['boolean', ['feature-state', 'selected'], false], 3, 0.6],
        },
      })
      map.on('click', 'fill', (e: MapLayerMouseEvent) => {
        const id = e.features?.[0]?.id
        if (id != null) cb.current.onPick(String(id))
      })
      map.on('mousemove', 'fill', (e: MapLayerMouseEvent) => {
        map.getCanvas().style.cursor = 'pointer'
        const id = e.features?.[0]?.id
        cb.current.onHover(id == null ? null : String(id))
      })
      map.on('mouseleave', 'fill', () => {
        map.getCanvas().style.cursor = ''
        cb.current.onHover(null)
      })
      setReady(true)
    })
    mapRef.current = map
    return () => {
      map.remove()
      mapRef.current = null
      setReady(false)
    }
  }, [])

  useEffect(() => {
    const map = mapRef.current
    if (!ready || !map) return
    const source = map.getSource(SOURCE) as unknown as { setData: (d: unknown) => void }
    source.setData(geo)
    const [w, s, e, n] = bbox(geo)
    if (Number.isFinite(w))
      map.fitBounds(
        [
          [w, s],
          [e, n],
        ],
        { padding: 16, animate: false },
      )
  }, [ready, geo])

  useEffect(() => {
    const map = mapRef.current
    if (!ready || !map) return
    map.removeFeatureState({ source: SOURCE })
    for (const [code, st] of Object.entries(states))
      map.setFeatureState({ source: SOURCE, id: code }, { color: st.color, suppressed: st.suppressed })
    if (selectedCode) map.setFeatureState({ source: SOURCE, id: selectedCode }, { selected: true })
  }, [ready, geo, states, selectedCode])

  const reset = () => {
    const [w, s, e, n] = bbox(geo)
    if (Number.isFinite(w))
      mapRef.current?.fitBounds([
        [w, s],
        [e, n],
      ], { padding: 16 })
  }

  return (
    <div className="map-frame">
      <div ref={box} className="map-canvas" role="region" aria-label={label} />
      <div className="map-controls">
        <button type="button" className="btn" aria-label="Aproximar" onClick={() => mapRef.current?.zoomIn()}>
          +
        </button>
        <button type="button" className="btn" aria-label="Afastar" onClick={() => mapRef.current?.zoomOut()}>
          −
        </button>
        <button type="button" className="btn" aria-label="Reenquadrar o mapa" onClick={reset}>
          ⌂
        </button>
      </div>
    </div>
  )
}
