import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterNextRender, effect, inject, input, signal, viewChild } from '@angular/core';
import { ObserverDto, PassDto } from '../api/passes.model';
import { PassClock } from '../pass-viewer/pass-clock';
import {
  D2R, EARTH_RADIUS_KM, LatLon, destinationPoint, isDaylit, sampleSubPointAt, subsolarPoint, toUnitVector, visibilityRadiusDeg,
} from './globe-geometry';
import { THREE_LOADER } from './three-loader';

/**
 * Structural aliases for the runtime module, never a value import of `'three'` — see
 * `three-loader.ts`. `isolatedModules` would otherwise let a per-file transpiler bundle
 * a package that is not installed.
 */
type ThreeNS = typeof import('three');
type Vector3 = InstanceType<ThreeNS['Vector3']>;
type Scene = InstanceType<ThreeNS['Scene']>;
type PerspectiveCamera = InstanceType<ThreeNS['PerspectiveCamera']>;
type WebGLRenderer = InstanceType<ThreeNS['WebGLRenderer']>;
type Mesh = InstanceType<ThreeNS['Mesh']>;
type Line = InstanceType<ThreeNS['Line']>;
type LineSegments = InstanceType<ThreeNS['LineSegments']>;
type DirectionalLight = InstanceType<ThreeNS['DirectionalLight']>;

/** [longitudeDeg, latitudeDeg] pairs, one closed ring per landmass. Natural Earth 110 m. */
type CoastlineRings = readonly (readonly [number, number])[][];

const COASTLINE_URL = 'coastline-110m.json';
const SURFACE_RADIUS = 1;
const GROUND_TRACK_RADIUS = 1.006;
const FOOTPRINT_RADIUS = 1.008;
const MARKER_RADIUS = 1.01;

function toVector3(three: ThreeNS, point: LatLon, radius: number): Vector3 {
  const { x, y, z } = toUnitVector(point, radius);
  return new three.Vector3(x, y, z);
}

function emptyLine(three: ThreeNS, color: number, opacity: number): Line {
  return new three.Line(new three.BufferGeometry(), new three.LineBasicMaterial({ color, transparent: true, opacity }));
}

function emptyLineSegments(three: ThreeNS, color: number, opacity: number): LineSegments {
  return new three.LineSegments(new three.BufferGeometry(), new three.LineBasicMaterial({ color, transparent: true, opacity }));
}

function setPoints(three: ThreeNS, line: Line | LineSegments, points: Vector3[]): void {
  line.geometry.dispose();
  line.geometry = new three.BufferGeometry().setFromPoints(points);
}

function buildCoastline(three: ThreeNS, rings: CoastlineRings): LineSegments {
  const points: Vector3[] = [];
  for (const ring of rings) {
    for (let i = 0; i < ring.length - 1; i++) {
      points.push(toVector3(three, { longitudeDeg: ring[i][0], latitudeDeg: ring[i][1] }, 1.003));
      points.push(toVector3(three, { longitudeDeg: ring[i + 1][0], latitudeDeg: ring[i + 1][1] }, 1.003));
    }
  }
  return new three.LineSegments(
    new three.BufferGeometry().setFromPoints(points),
    new three.LineBasicMaterial({ color: 0x7d93c6, transparent: true, opacity: 0.85 }),
  );
}

function buildGraticule(three: ThreeNS): LineSegments {
  const points: Vector3[] = [];
  for (let lat = -60; lat <= 60; lat += 30) {
    for (let lon = -180; lon < 180; lon += 5) {
      points.push(toVector3(three, { latitudeDeg: lat, longitudeDeg: lon }, 1.001));
      points.push(toVector3(three, { latitudeDeg: lat, longitudeDeg: lon + 5 }, 1.001));
    }
  }
  for (let lon = -180; lon < 180; lon += 30) {
    for (let lat = -90; lat < 90; lat += 5) {
      points.push(toVector3(three, { latitudeDeg: lat, longitudeDeg: lon }, 1.001));
      points.push(toVector3(three, { latitudeDeg: lat + 5, longitudeDeg: lon }, 1.001));
    }
  }
  return new three.LineSegments(
    new three.BufferGeometry().setFromPoints(points),
    new three.LineBasicMaterial({ color: 0x2c3b5e, transparent: true, opacity: 0.5 }),
  );
}

/**
 * Renders what `/api/passes` returns — no propagation here. Shares `PassClock` with
 * `PassViewer`: one instant drives the sky chart and this globe together.
 */
@Component({
  selector: 'app-globe',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './globe.html',
  styleUrl: './globe.scss',
})
export class Globe {
  readonly pass = input.required<PassDto>();
  readonly observer = input.required<ObserverDto>();
  readonly threshold = input.required<number>();

  protected readonly ready = signal(false);
  protected readonly failed = signal(false);

  private readonly canvasRef = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private readonly loadThree = inject(THREE_LOADER);
  private readonly clock = inject(PassClock);

  private three?: ThreeNS;
  private renderer?: WebGLRenderer;
  private scene?: Scene;
  private camera?: PerspectiveCamera;
  private sunLight?: DirectionalLight;
  private observerMarker?: Mesh;
  private satelliteMarker?: Mesh;
  private groundTrackDay?: LineSegments;
  private groundTrackNight?: LineSegments;
  private footprint?: Line;
  private sightLine?: Line;
  private resizeObserver?: ResizeObserver;

  // Pointer-drag orbit. Not signals: driven by native pointer events for immediate
  // response, outside the change-detection graph, exactly like the reference mockup.
  private yaw = 0;
  private pitch = 0.35;
  private readonly distance = 3.05;
  private dragging = false;
  private lastPointer = { x: 0, y: 0 };

  constructor() {
    afterNextRender(() => void this.bootstrap());

    effect(() => {
      if (!this.ready()) return;
      this.buildTrack(this.pass(), this.observer());
    });

    effect(() => {
      if (!this.ready()) return;
      this.clock.instant();
      this.renderFrame();
    });

    inject(DestroyRef).onDestroy(() => this.dispose());
  }

  private async bootstrap(): Promise<void> {
    try {
      const [three, coastline] = await Promise.all([
        this.loadThree(),
        fetch(COASTLINE_URL).then(response => response.json() as Promise<CoastlineRings>),
      ]);
      this.three = three;
      this.initScene(three, coastline);
      this.ready.set(true);
    } catch {
      this.failed.set(true);
    }
  }

  private initScene(three: ThreeNS, coastline: CoastlineRings): void {
    const canvas = this.canvasRef().nativeElement;
    const renderer = new three.WebGLRenderer({ canvas, antialias: true, alpha: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));

    const scene = new three.Scene();
    const camera = new three.PerspectiveCamera(36, 1, 0.1, 100);

    const earth = new three.Mesh(
      new three.SphereGeometry(SURFACE_RADIUS, 64, 48),
      new three.MeshPhongMaterial({ color: 0x14294d, emissive: 0x05080f, specular: 0x16233d, shininess: 8 }),
    );
    scene.add(earth);
    scene.add(buildCoastline(three, coastline));
    scene.add(buildGraticule(three));
    // A thin translucent shell reads as atmospheric haze at the limb.
    scene.add(new three.Mesh(
      new three.SphereGeometry(1.09, 48, 32),
      new three.MeshBasicMaterial({ color: 0x4b6cb0, transparent: true, opacity: 0.1, side: three.BackSide }),
    ));

    scene.add(new three.AmbientLight(0x2a3a5c, 1));
    const sunLight = new three.DirectionalLight(0xfff2d8, 1.25);
    scene.add(sunLight);

    // Colour matches the --observer design token; three.js materials take numeric
    // colours, not CSS custom properties, so the two are kept in step by comment.
    const observerMarker = new three.Mesh(new three.SphereGeometry(0.018, 16, 12), new three.MeshBasicMaterial({ color: 0x57d3a8 }));
    scene.add(observerMarker);
    const satelliteMarker = new three.Mesh(new three.SphereGeometry(0.022, 16, 12), new three.MeshBasicMaterial({ color: 0xffffff }));
    scene.add(satelliteMarker);

    const groundTrackDay = emptyLineSegments(three, 0xeceff8, 0.9);
    const groundTrackNight = emptyLineSegments(three, 0x46527a, 0.9);
    const footprint = emptyLine(three, 0x96a4ff, 0.65);
    const sightLine = emptyLine(three, 0xf4ad3c, 0.5);
    scene.add(groundTrackDay); scene.add(groundTrackNight); scene.add(footprint); scene.add(sightLine);

    this.renderer = renderer;
    this.scene = scene;
    this.camera = camera;
    this.sunLight = sunLight;
    this.observerMarker = observerMarker;
    this.satelliteMarker = satelliteMarker;
    this.groundTrackDay = groundTrackDay;
    this.groundTrackNight = groundTrackNight;
    this.footprint = footprint;
    this.sightLine = sightLine;

    this.attachPointerControls(canvas);
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(canvas);
    this.resize();
  }

  /**
   * Rebuilds the geometry that only changes when a different pass is selected: the two
   * ground-track segments and the Sun direction lighting the terminator.
   *
   * The split is the ground's own day/night line, not the satellite's — `illuminated`
   * stays `false` until milestone 10, and this view does not anticipate it. The Sun's
   * position is fixed at culmination for the whole pass: a pass lasts a few minutes, over
   * which the true position moves by a fraction of a degree — not worth recomputing every
   * frame for a shadow line already this simplified.
   */
  private buildTrack(pass: PassDto, observer: ObserverDto): void {
    const three = this.three!;
    const track = pass.track;
    const sun = subsolarPoint(Date.parse(pass.culmination.instant));
    this.sunLight!.position.copy(toVector3(three, sun, 6));

    const dayPoints: Vector3[] = [];
    const nightPoints: Vector3[] = [];
    for (let i = 0; i < track.length - 1; i++) {
      const bucket = isDaylit(track[i].subPoint, sun) ? dayPoints : nightPoints;
      bucket.push(toVector3(three, track[i].subPoint, GROUND_TRACK_RADIUS), toVector3(three, track[i + 1].subPoint, GROUND_TRACK_RADIUS));
    }
    setPoints(three, this.groundTrackDay!, dayPoints);
    setPoints(three, this.groundTrackNight!, nightPoints);

    const middle = track[Math.floor(track.length / 2)]?.subPoint ?? observer;
    this.yaw = middle.longitudeDeg * D2R;
    this.pitch = Math.max(-0.7, Math.min(0.9, middle.latitudeDeg * D2R * 0.85));

    this.renderFrame();
  }

  private renderFrame(): void {
    if (!this.three || !this.renderer || !this.scene || !this.camera) return;
    const three = this.three;
    const observer = this.observer();
    const current = sampleSubPointAt(this.pass().track, this.clock.instant());

    this.observerMarker!.position.copy(toVector3(three, observer, MARKER_RADIUS));

    if (current) {
      const satelliteRadius = 1 + current.altitudeKm / EARTH_RADIUS_KM;
      const satellitePosition = toVector3(three, current, satelliteRadius);
      this.satelliteMarker!.position.copy(satellitePosition);
      setPoints(three, this.sightLine!, [toVector3(three, observer, MARKER_RADIUS), satellitePosition]);

      const angularRadius = visibilityRadiusDeg(current.altitudeKm, this.threshold());
      const ring: Vector3[] = [];
      for (let bearing = 0; bearing <= 360; bearing += 5) {
        ring.push(toVector3(three, destinationPoint(current, bearing, angularRadius), FOOTPRINT_RADIUS));
      }
      setPoints(three, this.footprint!, ring);
    }

    this.camera.position.set(
      this.distance * Math.cos(this.pitch) * Math.sin(this.yaw),
      this.distance * Math.sin(this.pitch),
      this.distance * Math.cos(this.pitch) * Math.cos(this.yaw),
    );
    this.camera.lookAt(0, 0, 0);
    this.renderer.render(this.scene, this.camera);
  }

  private attachPointerControls(canvas: HTMLCanvasElement): void {
    const down = (event: PointerEvent): void => {
      this.dragging = true;
      this.lastPointer = { x: event.clientX, y: event.clientY };
      canvas.classList.add('dragging');
      canvas.setPointerCapture(event.pointerId);
    };
    const move = (event: PointerEvent): void => {
      if (!this.dragging) return;
      this.yaw -= (event.clientX - this.lastPointer.x) * 0.006;
      this.pitch = Math.max(-1.2, Math.min(1.2, this.pitch + (event.clientY - this.lastPointer.y) * 0.006));
      this.lastPointer = { x: event.clientX, y: event.clientY };
      this.renderFrame();
    };
    const up = (): void => { this.dragging = false; canvas.classList.remove('dragging'); };
    canvas.addEventListener('pointerdown', down);
    canvas.addEventListener('pointermove', move);
    canvas.addEventListener('pointerup', up);
    canvas.addEventListener('pointercancel', up);
  }

  private resize(): void {
    if (!this.renderer || !this.camera) return;
    const canvas = this.canvasRef().nativeElement;
    const width = canvas.clientWidth || 1;
    const height = canvas.clientHeight || 1;
    this.renderer.setSize(width, height, false);
    this.camera.aspect = width / height;
    this.camera.updateProjectionMatrix();
    this.renderFrame();
  }

  private dispose(): void {
    this.resizeObserver?.disconnect();
    this.renderer?.dispose();
  }
}
