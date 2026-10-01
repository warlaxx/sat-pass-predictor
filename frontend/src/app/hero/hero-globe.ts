import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterNextRender, effect, inject, input, signal, viewChild } from '@angular/core';
import { THREE_LOADER } from '../globe/three-loader';

/** Structural aliases only, never a value import of `'three'`: see `three-loader.ts`. */
type ThreeNS = typeof import('three');
type Vector3 = InstanceType<ThreeNS['Vector3']>;
type Group = InstanceType<ThreeNS['Group']>;
type Mesh = InstanceType<ThreeNS['Mesh']>;
type Line = InstanceType<ThreeNS['Line']>;
type Texture = InstanceType<ThreeNS['Texture']>;
type PerspectiveCamera = InstanceType<ThreeNS['PerspectiveCamera']>;
type WebGLRenderer = InstanceType<ThreeNS['WebGLRenderer']>;
type Scene = InstanceType<ThreeNS['Scene']>;

/** The orbit drawn in signal red: the satellite of the query, at its real altitude and inclination. */
export interface PrimaryOrbit {
  readonly inclinationDeg: number;
  readonly altitudeKm: number;
}

interface Satellite {
  radius: number;
  readonly period: number;
  readonly phase: number;
  readonly plane: Group;
  readonly orbit: Line;
  readonly body: Mesh;
  readonly trail?: { line: Line; positions: Float32Array; colours: Float32Array };
}

const D2R = Math.PI / 180;
const EARTH_RADIUS_KM = 6371;
const COASTLINE_URL = 'coastline-110m.json';
const TRAIL_POINTS = 90;
/** The camera of the reference design, and its distance from the centre. */
const CAMERA_START = [0.6, 0.9, 4.6] as const;
const CAMERA_DISTANCE = Math.hypot(...CAMERA_START);
/** Wider than this, the globe sits right of centre, beside the text. */
const WIDE_ASPECT = 1.15;

/**
 * Decorative orbits around the query's own: a sun-synchronous weather satellite, a
 * Russian imager and Hubble, at rough altitudes and inclinations and a sped-up clock.
 */
const COMPANIONS = [
  { radius: 1.13, inclination: 99.2, raan: 110, period: 30, colour: 0xffffff, size: 0.014 },
  { radius: 1.12, inclination: 98.6, raan: 200, period: 29, colour: 0xf4ad3c, size: 0.014 },
  { radius: 1.085, inclination: 28.5, raan: 300, period: 24, colour: 0x5fc4e8, size: 0.013 },
] as const;

/** Display radius of an orbit: true to scale in low orbit, capped so a GEO stays in frame. */
export function orbitRadius(altitudeKm: number): number {
  return 1 + Math.min(0.6, Math.max(0.04, altitudeKm / EARTH_RADIUS_KM));
}

function lonLat(three: ThreeNS, lat: number, lon: number, radius: number): Vector3 {
  const phi = (90 - lat) * D2R;
  const theta = (lon + 180) * D2R;
  return new three.Vector3(
    -radius * Math.sin(phi) * Math.cos(theta), radius * Math.cos(phi), radius * Math.sin(phi) * Math.sin(theta),
  );
}

function glowTexture(three: ThreeNS, hex: number): Texture {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = 64;
  const context = canvas.getContext('2d')!;
  const colour = new three.Color(hex);
  const gradient = context.createRadialGradient(32, 32, 0, 32, 32, 32);
  gradient.addColorStop(0, `rgba(${(colour.r * 255) | 0},${(colour.g * 255) | 0},${(colour.b * 255) | 0},0.9)`);
  gradient.addColorStop(1, 'rgba(0,0,0,0)');
  context.fillStyle = gradient;
  context.fillRect(0, 0, 64, 64);
  return new three.CanvasTexture(canvas);
}

const WORLD_VERTEX = `varying vec3 vN; varying vec3 vW;
  void main() { vN = normalize(mat3(modelMatrix) * normal); vW = (modelMatrix * vec4(position, 1.)).xyz;
    gl_Position = projectionMatrix * viewMatrix * vec4(vW, 1.); }`;

/** Night side, day side, a faint red line along the terminator, and an accent rim. */
const OCEAN_FRAGMENT = `uniform vec3 sun; varying vec3 vN; varying vec3 vW;
  void main() { float d = dot(vN, sun); float day = smoothstep(-0.15, 0.35, d);
    vec3 c = mix(vec3(0.012, 0.014, 0.022), vec3(0.045, 0.055, 0.10), day);
    c += vec3(0.99, 0.24, 0.13) * exp(-pow(d * 9., 2.)) * 0.10;
    vec3 v = normalize(cameraPosition - vW); float rim = pow(1. - max(dot(vN, v), 0.), 3.);
    c += vec3(0.59, 0.64, 1.0) * rim * 0.35 * (0.35 + day);
    gl_FragColor = vec4(c, 1.); }`;

const ATMOSPHERE_FRAGMENT = `varying vec3 vN; varying vec3 vW;
  void main() { vec3 v = normalize(cameraPosition - vW); float i = pow(1. - abs(dot(vN, v)), 5.);
    gl_FragColor = vec4(vec3(0.45, 0.52, 1.0) * i * 1.4, i); }`;

/** Stars of three sizes and five colour temperatures; the larger ones twinkle and wear spikes. */
const STAR_VERTEX = `attribute float size; attribute float phase; attribute vec3 color;
  uniform float time; uniform float pr; varying vec3 vC; varying float vA;
  void main() { vC = color; vA = 0.55 + 0.45 * sin(time * (0.6 + fract(phase) * 1.8) + phase);
    vA = mix(1.0, vA, step(1.5, size)); gl_PointSize = size * pr;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`;

const STAR_FRAGMENT = `varying vec3 vC; varying float vA;
  void main() { vec2 q = gl_PointCoord - 0.5; float d = length(q); float core = smoothstep(0.5, 0.0, d);
    float spike = max(0.0, 1.0 - abs(q.x) * 18.0) * smoothstep(0.5, 0.0, abs(q.y))
                + max(0.0, 1.0 - abs(q.y) * 18.0) * smoothstep(0.5, 0.0, abs(q.x));
    float a = (pow(core, 2.2) + spike * 0.35) * vA; if (a < 0.01) discard; gl_FragColor = vec4(vC * a, a); }`;

/**
 * The globe behind the home hero: Earth with its terminator and coastlines, the observer
 * of the query and its visibility circle, a few satellites on their orbits, deep space.
 *
 * It illustrates; it does not predict. The red orbit takes the inclination and altitude
 * of the queried satellite once a prediction has come back, but its position runs on a
 * sped-up clock - the pass globe further down is the one drawn from the real track.
 *
 * three.js comes from the same lazy loader as that globe. Until it arrives, or if WebGL
 * is missing, the hero is black space and its text reads the same.
 */
@Component({
  selector: 'app-hero-globe',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<canvas #canvas aria-hidden="true"></canvas>`,
  styles: `
    :host { background: var(--bg); display: block; }
    /* pan-y, as on the pass globe: a vertical swipe over it must still scroll the page. */
    canvas { cursor: grab; display: block; height: 100%; opacity: 0; touch-action: pan-y; transition: opacity 1.2s ease; width: 100%; }
    canvas.ready { opacity: 1; }
    canvas.dragging { cursor: grabbing; }
  `,
})
export class HeroGlobe {
  readonly latitude = input.required<number>();
  readonly longitude = input.required<number>();
  /** Angular radius of the ground circle the satellite is above the threshold from. */
  readonly visibilityDeg = input(12.6);
  readonly primary = input<PrimaryOrbit>();

  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly ready = signal(false);

  private three?: ThreeNS;
  private renderer?: WebGLRenderer;
  private camera?: PerspectiveCamera;
  private scene?: Scene;
  private spin?: Group;
  private observer?: { dot: Mesh; rings: Mesh[]; circle?: Line };
  private satellites: Satellite[] = [];
  private stars?: { points: InstanceType<ThreeNS['Points']>; time: { value: number } };
  private reduceMotion = false;
  private visible = true;
  private elapsed = 0;
  private lastFrame = 0;

  // Camera orbit around the centre, with inertia: what OrbitControls would do, which the
  // UMD build of three.js does not ship.
  private azimuth = Math.atan2(CAMERA_START[0], CAMERA_START[2]);
  private polar = Math.acos(CAMERA_START[1] / CAMERA_DISTANCE);
  private distance = CAMERA_DISTANCE;
  /** Rotation the drag asked for and the camera has not made yet. */
  private pending = { azimuth: 0, polar: 0 };
  private drag?: { x: number; y: number };

  private cleanup: (() => void)[] = [];

  constructor() {
    const loadThree = inject(THREE_LOADER);
    afterNextRender(async () => {
      try {
        this.three = await loadThree();
        this.init(this.three);
        this.ready.set(true);
        void this.loadCoastline(this.three);
      } catch {
        // No three.js or no WebGL: the hero keeps its black background.
      }
    });

    effect(() => {
      const latitude = this.latitude(), longitude = this.longitude(), radius = this.visibilityDeg();
      if (this.ready()) this.placeObserver(latitude, longitude, radius);
    });
    effect(() => {
      const primary = this.primary();
      if (this.ready() && primary) {
        const satellite = this.satellites[0];
        satellite.radius = orbitRadius(primary.altitudeKm);
        satellite.orbit.scale.setScalar(satellite.radius);
        satellite.plane.rotation.x = primary.inclinationDeg * D2R;
      }
    });

    inject(DestroyRef).onDestroy(() => this.dispose());
  }

  private init(three: ThreeNS): void {
    const canvas = this.canvas().nativeElement;
    const renderer = new three.WebGLRenderer({ canvas, antialias: true, alpha: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    this.renderer = renderer;
    this.reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

    const scene = new three.Scene();
    this.scene = scene;
    this.camera = new three.PerspectiveCamera(30, 1, 0.1, 100);

    const earth = new three.Group();
    earth.rotation.z = 23.4 * D2R;
    scene.add(earth);
    const spin = new three.Group();
    earth.add(spin);
    this.spin = spin;

    spin.add(new three.Mesh(new three.SphereGeometry(1, 96, 64), new three.ShaderMaterial({
      uniforms: { sun: { value: new three.Vector3(1, 0.25, 0.6).normalize() } },
      vertexShader: WORLD_VERTEX,
      fragmentShader: OCEAN_FRAGMENT,
    })));
    earth.add(new three.Mesh(new three.SphereGeometry(1.18, 64, 48), new three.ShaderMaterial({
      side: three.BackSide, transparent: true, depthWrite: false, blending: three.AdditiveBlending,
      vertexShader: WORLD_VERTEX,
      fragmentShader: ATMOSPHERE_FRAGMENT,
    })));

    const graticule = new three.LineBasicMaterial({ color: 0x34363d, transparent: true, opacity: 0.55 });
    for (let lat = -60; lat <= 60; lat += 30) {
      const points = [];
      for (let lon = -180; lon <= 180; lon += 3) points.push(lonLat(three, lat, lon, 1.001));
      spin.add(new three.Line(new three.BufferGeometry().setFromPoints(points), graticule));
    }
    for (let lon = -180; lon < 180; lon += 30) {
      const points = [];
      for (let lat = -90; lat <= 90; lat += 3) points.push(lonLat(three, lat, lon, 1.001));
      spin.add(new three.Line(new three.BufferGeometry().setFromPoints(points), graticule));
    }

    const observerColour = 0x57d3a8;
    const dot = new three.Mesh(new three.SphereGeometry(0.014, 16, 12), new three.MeshBasicMaterial({ color: observerColour }));
    const ringGeometry = new three.RingGeometry(0.02, 0.026, 48);
    const rings = [0, 1].map(() => new three.Mesh(ringGeometry, new three.MeshBasicMaterial({
      color: observerColour, transparent: true, side: three.DoubleSide, depthWrite: false,
    })));
    spin.add(dot, ...rings);
    this.observer = { dot, rings };

    this.satellites = [
      this.addSatellite(three, earth, { radius: orbitRadius(420), inclination: 51.6, raan: 20, period: 22, colour: 0xfc3d21, size: 0.022 }, true),
      ...COMPANIONS.map(companion => this.addSatellite(three, earth, companion, false)),
    ];
    this.addStars(three, scene, renderer.getPixelRatio());

    this.attachPointer(canvas);
    const resizer = new ResizeObserver(() => this.resize());
    resizer.observe(this.host);
    const watcher = new IntersectionObserver(entries => { this.visible = entries.some(entry => entry.isIntersecting); });
    watcher.observe(this.host);
    this.cleanup.push(() => resizer.disconnect(), () => watcher.disconnect());
    this.resize();

    this.lastFrame = performance.now();
    renderer.setAnimationLoop(now => this.frame(now));
    canvas.classList.add('ready');
  }

  private addSatellite(
    three: ThreeNS, earth: Group,
    spec: { radius: number; inclination: number; raan: number; period: number; colour: number; size: number },
    primary: boolean,
  ): Satellite {
    const plane = new three.Group();
    plane.rotation.y = spec.raan * D2R;
    plane.rotation.x = spec.inclination * D2R;
    earth.add(plane);

    const circle = [];
    for (let i = 0; i <= 256; i++) circle.push(new three.Vector3(Math.cos(i / 128 * Math.PI), 0, Math.sin(i / 128 * Math.PI)));
    const orbit = new three.Line(new three.BufferGeometry().setFromPoints(circle), new three.LineBasicMaterial({
      color: spec.colour, transparent: true, opacity: primary ? 0.22 : 0.12,
    }));
    orbit.scale.setScalar(spec.radius);
    plane.add(orbit);

    const body = new three.Mesh(new three.SphereGeometry(spec.size, 16, 12), new three.MeshBasicMaterial({ color: spec.colour }));
    const glow = new three.Sprite(new three.SpriteMaterial({
      map: glowTexture(three, spec.colour), transparent: true, depthWrite: false, blending: three.AdditiveBlending,
    }));
    glow.scale.setScalar(spec.size * 9);
    body.add(glow);
    plane.add(body);

    let trail: Satellite['trail'];
    if (primary) {
      const positions = new Float32Array(TRAIL_POINTS * 3);
      const colours = new Float32Array(TRAIL_POINTS * 3);
      const geometry = new three.BufferGeometry();
      geometry.setAttribute('position', new three.BufferAttribute(positions, 3));
      geometry.setAttribute('color', new three.BufferAttribute(colours, 3));
      const line = new three.Line(geometry, new three.LineBasicMaterial({
        vertexColors: true, transparent: true, blending: three.AdditiveBlending,
      }));
      plane.add(line);
      trail = { line, positions, colours };
    }
    return { radius: spec.radius, period: spec.period, phase: Math.random() * Math.PI * 2, plane, orbit, body, trail };
  }

  private addStars(three: ThreeNS, scene: Scene, pixelRatio: number): void {
    const count = 9000;
    const positions = new Float32Array(count * 3);
    const sizes = new Float32Array(count);
    const colours = new Float32Array(count * 3);
    const phases = new Float32Array(count);
    const band = new three.Vector3(0.35, 1, -0.25).normalize();
    const temperatures = [[0.7, 0.8, 1], [1, 1, 1], [1, 0.93, 0.8], [1, 0.8, 0.62], [0.8, 0.86, 1]];
    const v = new three.Vector3();
    for (let i = 0; i < count; i++) {
      const u = Math.random() * 2 - 1, t = Math.random() * Math.PI * 2, s = Math.sqrt(1 - u * u);
      v.set(s * Math.cos(t), u, s * Math.sin(t));
      // Nearly half the stars are pressed into one band: the Milky Way.
      if (i < count * 0.45) v.addScaledVector(band, -v.dot(band) * (0.85 + Math.random() * 0.15)).normalize();
      v.multiplyScalar(30 + Math.random() * 30);
      positions.set([v.x, v.y, v.z], i * 3);
      const r = Math.random();
      sizes[i] = r > 0.995 ? 5 + Math.random() * 3 : r > 0.96 ? 2.6 + Math.random() * 1.6 : 0.8 + Math.random() * 1.4;
      colours.set(temperatures[(Math.random() * temperatures.length) | 0], i * 3);
      phases[i] = Math.random() * 100;
    }
    const geometry = new three.BufferGeometry();
    geometry.setAttribute('position', new three.BufferAttribute(positions, 3));
    geometry.setAttribute('size', new three.BufferAttribute(sizes, 1));
    geometry.setAttribute('color', new three.BufferAttribute(colours, 3));
    geometry.setAttribute('phase', new three.BufferAttribute(phases, 1));
    const time = { value: 0 };
    const points = new three.Points(geometry, new three.ShaderMaterial({
      uniforms: { time, pr: { value: pixelRatio } },
      transparent: true, depthWrite: false, blending: three.AdditiveBlending,
      vertexShader: STAR_VERTEX, fragmentShader: STAR_FRAGMENT,
    }));
    scene.add(points);
    this.stars = { points, time };

    const nebula = (hex: number, position: [number, number, number], scale: number, opacity: number): void => {
      const sprite = new three.Sprite(new three.SpriteMaterial({
        map: glowTexture(three, hex), transparent: true, opacity, depthWrite: false, blending: three.AdditiveBlending,
      }));
      sprite.position.set(...position);
      sprite.scale.setScalar(scale);
      scene.add(sprite);
    };
    nebula(0x5a67b4, [-14, 8, -30], 34, 0.16);
    nebula(0xfc3d21, [18, -10, -34], 26, 0.07);
    nebula(0x5fc4e8, [6, 16, -40], 30, 0.08);
  }

  private async loadCoastline(three: ThreeNS): Promise<void> {
    const rings = await fetch(COASTLINE_URL).then(response => response.json() as Promise<[number, number][][]>);
    if (!this.spin) return;
    const material = new three.LineBasicMaterial({ color: 0x96a4ff, transparent: true, opacity: 0.9 });
    const segments: Vector3[] = [];
    for (const ring of rings) {
      for (let i = 1; i < ring.length; i++) {
        segments.push(lonLat(three, ring[i - 1][1], ring[i - 1][0], 1.002), lonLat(three, ring[i][1], ring[i][0], 1.002));
      }
    }
    this.spin.add(new three.LineSegments(new three.BufferGeometry().setFromPoints(segments), material));
  }

  private placeObserver(latitude: number, longitude: number, visibilityDeg: number): void {
    const three = this.three, observer = this.observer, spin = this.spin;
    if (!three || !observer || !spin || !Number.isFinite(latitude) || !Number.isFinite(longitude)) return;
    const normal = lonLat(three, latitude, longitude, 1);
    observer.dot.position.copy(normal).multiplyScalar(1.004);
    const facing = new three.Quaternion().setFromUnitVectors(new three.Vector3(0, 0, 1), normal);
    for (const ring of observer.rings) {
      ring.position.copy(observer.dot.position);
      ring.quaternion.copy(facing);
    }

    if (observer.circle) {
      spin.remove(observer.circle);
      observer.circle.geometry.dispose();
    }
    const helper = Math.abs(normal.y) > 0.99 ? new three.Vector3(1, 0, 0) : new three.Vector3(0, 1, 0);
    const t1 = helper.cross(normal).normalize();
    const t2 = normal.clone().cross(t1);
    const angle = Math.max(0.5, visibilityDeg) * D2R;
    const points = [];
    for (let i = 0; i <= 128; i++) {
      const u = i / 128 * Math.PI * 2;
      points.push(normal.clone().multiplyScalar(Math.cos(angle))
        .addScaledVector(t1, Math.sin(angle) * Math.cos(u))
        .addScaledVector(t2, Math.sin(angle) * Math.sin(u))
        .multiplyScalar(1.003));
    }
    const circle = new three.Line(new three.BufferGeometry().setFromPoints(points), new three.LineDashedMaterial({
      color: 0x57d3a8, dashSize: 0.02, gapSize: 0.014, transparent: true, opacity: 0.8,
    }));
    circle.computeLineDistances();
    spin.add(circle);
    observer.circle = circle;
  }

  private attachPointer(canvas: HTMLCanvasElement): void {
    const down = (event: PointerEvent): void => {
      this.drag = { x: event.clientX, y: event.clientY };
      canvas.classList.add('dragging');
      canvas.setPointerCapture?.(event.pointerId);
    };
    const move = (event: PointerEvent): void => {
      if (!this.drag) return;
      // Half a turn per frame height dragged, as OrbitControls at rotateSpeed 0.5.
      const speed = Math.PI / Math.max(1, this.host.clientHeight);
      this.pending.azimuth -= (event.clientX - this.drag.x) * speed;
      this.pending.polar -= (event.clientY - this.drag.y) * speed;
      this.drag = { x: event.clientX, y: event.clientY };
    };
    const up = (): void => {
      this.drag = undefined;
      canvas.classList.remove('dragging');
    };
    canvas.addEventListener('pointerdown', down);
    canvas.addEventListener('pointermove', move);
    canvas.addEventListener('pointerup', up);
    canvas.addEventListener('pointercancel', up);
  }

  private resize(): void {
    const renderer = this.renderer, camera = this.camera;
    if (!renderer || !camera) return;
    const width = Math.max(1, this.host.clientWidth), height = Math.max(1, this.host.clientHeight);
    renderer.setSize(width, height, false);
    const aspect = width / height;
    camera.aspect = aspect;
    camera.fov = aspect > WIDE_ASPECT ? 30 : 36;
    if (aspect > WIDE_ASPECT) camera.setViewOffset(width, height, -width * 0.2, 0, width, height);
    else camera.clearViewOffset();
    // A tall, narrow frame backs the camera off until the globe and its orbits fit across.
    this.distance = Math.max(CAMERA_DISTANCE, 1.45 / (Math.tan(camera.fov * D2R / 2) * aspect));
    camera.updateProjectionMatrix();
  }

  private frame(now: number): void {
    const renderer = this.renderer, camera = this.camera, scene = this.scene;
    if (!renderer || !camera || !scene || !this.spin) return;
    const delta = Math.min(0.1, (now - this.lastFrame) / 1000);
    this.lastFrame = now;
    if (!this.visible || document.hidden) return;
    if (!this.reduceMotion) this.elapsed += delta;
    const t = this.elapsed;

    this.spin.rotation.y = -0.6 + t * 0.045;
    for (const satellite of this.satellites) {
      const u = satellite.phase + t * (Math.PI * 2 / satellite.period);
      satellite.body.position.set(Math.cos(u) * satellite.radius, 0, Math.sin(u) * satellite.radius);
      const trail = satellite.trail;
      if (!trail) continue;
      for (let i = 0; i < TRAIL_POINTS; i++) {
        const back = u - i * 0.012, fade = 1 - i / TRAIL_POINTS;
        trail.positions.set([Math.cos(back) * satellite.radius, 0, Math.sin(back) * satellite.radius], i * 3);
        trail.colours.set([0.99 * fade, 0.24 * fade, 0.13 * fade], i * 3);
      }
      trail.line.geometry.attributes['position'].needsUpdate = true;
      trail.line.geometry.attributes['color'].needsUpdate = true;
    }
    if (this.stars) {
      this.stars.time.value = t;
      this.stars.points.rotation.y = t * 0.004;
    }
    this.observer?.rings.forEach((ring, i) => {
      const f = this.reduceMotion ? 0.35 + i * 0.3 : (t * 0.6 + i * 0.5) % 1;
      ring.scale.setScalar(1 + f * 3);
      (ring.material as InstanceType<ThreeNS['MeshBasicMaterial']>).opacity = 1 - f;
    });

    // Damping as in OrbitControls: each frame makes a fraction of the rotation still owed,
    // so a drag eases in and a fling glides to rest, and the total is what was dragged.
    const step = { azimuth: this.pending.azimuth * 0.08, polar: this.pending.polar * 0.08 };
    this.pending.azimuth -= step.azimuth;
    this.pending.polar -= step.polar;
    this.azimuth += step.azimuth;
    this.polar = Math.min(Math.PI - 0.35, Math.max(0.35, this.polar + step.polar));
    camera.position.set(
      this.distance * Math.sin(this.polar) * Math.sin(this.azimuth),
      this.distance * Math.cos(this.polar),
      this.distance * Math.sin(this.polar) * Math.cos(this.azimuth),
    );
    camera.lookAt(0, 0, 0);
    renderer.render(scene, camera);
  }

  private dispose(): void {
    for (const release of this.cleanup) release();
    this.renderer?.setAnimationLoop(null);
    this.scene?.traverse(object => {
      const node = object as Partial<Mesh>;
      node.geometry?.dispose();
      const materials = node.material ? ([] as unknown[]).concat(node.material) : [];
      for (const material of materials as { dispose(): void; map?: Texture | null }[]) {
        material.map?.dispose();
        material.dispose();
      }
    });
    // Browsers keep a handful of WebGL contexts: a page left and reopened must give its own back.
    this.renderer?.dispose();
    this.renderer?.forceContextLoss();
  }
}
