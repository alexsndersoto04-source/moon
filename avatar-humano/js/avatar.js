import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { CONFIG } from './config.js';

export class Avatar3D {
  constructor(canvas, voice) {
    this.canvas = canvas;
    this.voice = voice;
    this.scene = null;
    this.camera = null;
    this.renderer = null;
    this.avatar = null;
    this.mixer = null;
    this.clock = new THREE.Clock();
    this.bones = {};
    this.morphTargets = [];
    this.state = 'idle'; // idle, listening, thinking, speaking
    this.time = 0;
    this.blinkTimer = 0;
    this.nextBlink = 2 + Math.random()*3;
    this.baseY = 0;
    this.init();
  }

  init() {
    // Scene
    this.scene = new THREE.Scene();
    this.scene.fog = new THREE.Fog(0x0a0a0f, 6, 12);

    // Camera
    this.camera = new THREE.PerspectiveCamera(42, this.canvas.clientWidth / this.canvas.clientHeight, 0.1, 100);
    this.camera.position.set(0, 1.55, 2.1);
    this.camera.lookAt(0, 1.45, 0);

    // Renderer
    this.renderer = new THREE.WebGLRenderer({ canvas: this.canvas, antialias: true, alpha: true });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.setSize(this.canvas.clientWidth, this.canvas.clientHeight, false);
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.renderer.shadowMap.enabled = true;
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.1;

    // Lights - estudio para hacerlo guapo
    const amb = new THREE.HemisphereLight(0xffffff, 0x222233, 1.0);
    this.scene.add(amb);
    const key = new THREE.DirectionalLight(0xfff4e6, 1.6);
    key.position.set(2, 3, 2); key.castShadow = true;
    this.scene.add(key);
    const fill = new THREE.DirectionalLight(0x7c3aed, 0.6);
    fill.position.set(-2, 1.5, 1.5);
    this.scene.add(fill);
    const rim = new THREE.DirectionalLight(0xec4899, 0.7);
    rim.position.set(0, 2, -2);
    this.scene.add(rim);
    const point = new THREE.PointLight(0xffffff, 0.8, 4);
    point.position.set(0, 1.8, 1.5);
    this.scene.add(point);

    // Ground subtle
    const groundGeo = new THREE.CircleGeometry(2.5, 64);
    const groundMat = new THREE.MeshStandardMaterial({ color: 0x15151f, roughness: 0.8, metalness: 0.1 });
    const ground = new THREE.Mesh(groundGeo, groundMat);
    ground.rotation.x = -Math.PI/2;
    ground.position.y = 0;
    ground.receiveShadow = true;
    this.scene.add(ground);

    // Load avatar
    this.loadAvatar();

    window.addEventListener('resize', () => this.onResize());
    this.animate();
  }

  async loadAvatar() {
    const loader = new GLTFLoader();
    let loaded = false;
    for (const url of CONFIG.avatar.models) {
      try {
        const gltf = await loader.loadAsync(url);
        this.setupModel(gltf.scene);
        loaded = true;
        console.log("Avatar cargado:", url);
        break;
      } catch (e) {
        console.warn("Fallo modelo", url, e);
      }
    }
    if (!loaded) this.createProceduralAvatar();
  }

  setupModel(scene) {
    this.avatar = scene;
    this.avatar.traverse(o => {
      if (o.isMesh) {
        o.castShadow = true;
        o.receiveShadow = true;
        o.frustumCulled = false;
        // Guardar morphs para lipsync/parpadeo
        if (o.morphTargetDictionary) {
          this.morphTargets.push(o);
        }
      }
      if (o.isBone) {
        this.bones[o.name] = o;
        // También guardar por nombre simplificado
        const n = o.name.toLowerCase();
        if (n.includes("head")) this.bones.Head = o;
        if (n.includes("neck")) this.bones.Neck = o;
        if (n.includes("spine")) this.bones.Spine = o;
        if (n.includes("leftarm") || n.includes("left_arm") || n.includes("arm_l")) this.bones.LeftArm = o;
        if (n.includes("rightarm") || n.includes("right_arm") || n.includes("arm_r")) this.bones.RightArm = o;
        if (n.includes("lefthand")) this.bones.LeftHand = o;
        if (n.includes("righthand")) this.bones.RightHand = o;
      }
    });

    // Normalizar escala y posición
    const box = new THREE.Box3().setFromObject(this.avatar);
    const center = box.getCenter(new THREE.Vector3());
    const size = box.getSize(new THREE.Vector3());
    const scale = 1.7 / size.y;
    this.avatar.scale.setScalar(scale);
    this.avatar.position.set(-center.x * scale, -box.min.y * scale, -center.z * scale);
    this.baseY = this.avatar.position.y;

    this.scene.add(this.avatar);

    // Animaciones si existen
    if (this.avatar.animations && this.avatar.animations.length) {
      this.mixer = new THREE.AnimationMixer(this.avatar);
    }
  }

  createProceduralAvatar() {
    // Avatar fallback: humano estilizado guapo, por si falla internet
    const group = new THREE.Group();
    const skinMat = new THREE.MeshStandardMaterial({ color: 0xffdbac, roughness: 0.6 });
    const hairMat = new THREE.MeshStandardMaterial({ color: 0x1a1a1a, roughness: 0.9 });
    const shirtMat = new THREE.MeshStandardMaterial({ color: 0x111827, roughness: 0.7 });
    const eyeMat = new THREE.MeshStandardMaterial({ color: 0x1f2937 });
    const eyeWhiteMat = new THREE.MeshStandardMaterial({ color: 0xffffff });

    // Torso
    const torso = new THREE.Mesh(new THREE.CapsuleGeometry(0.28, 0.6, 8, 16), shirtMat);
    torso.position.y = 1.1; torso.castShadow = true;
    group.add(torso);
    // Cabeza
    const head = new THREE.Mesh(new THREE.SphereGeometry(0.24, 24, 24), skinMat);
    head.position.y = 1.65; head.castShadow = true;
    group.add(head);
    this.bones.Head = head;
    // Pelo
    const hair = new THREE.Mesh(new THREE.SphereGeometry(0.25, 24, 12, 0, Math.PI*2, 0, Math.PI/2), hairMat);
    hair.position.y = 1.70;
    group.add(hair);
    // Ojos
    const eyeGeo = new THREE.SphereGeometry(0.03, 12, 12);
    const leftEye = new THREE.Mesh(eyeGeo, eyeMat); leftEye.position.set(-0.07, 1.65, 0.2); group.add(leftEye);
    const rightEye = new THREE.Mesh(eyeGeo, eyeMat); rightEye.position.set(0.07, 1.65, 0.2); group.add(rightEye);
    this.bones.LeftEye = leftEye; this.bones.RightEye = rightEye;
    // Boca placeholder para lipsync
    const mouth = new THREE.Mesh(new THREE.CapsuleGeometry(0.015, 0.06, 4, 8), new THREE.MeshStandardMaterial({color: 0x7c2d12}));
    mouth.rotation.z = Math.PI/2; mouth.position.set(0, 1.55, 0.22); group.add(mouth);
    this.bones.Mouth = mouth;

    // Brazos simples
    const armGeo = new THREE.CapsuleGeometry(0.07, 0.45, 6, 12);
    const lArm = new THREE.Mesh(armGeo, skinMat); lArm.position.set(-0.38, 1.05, 0); lArm.rotation.z = 0.15; group.add(lArm);
    const rArm = new THREE.Mesh(armGeo, skinMat); rArm.position.set(0.38, 1.05, 0); rArm.rotation.z = -0.15; group.add(rArm);
    this.bones.LeftArm = lArm; this.bones.RightArm = rArm;

    this.avatar = group;
    this.baseY = 0;
    this.scene.add(group);
  }

  setState(s) {
    this.state = s;
    // Pequeña animación de transición
    if (s === 'listening') {
      this.avatar && (this.avatar.position.z = 0.08);
    } else if (s === 'thinking') {
      // mirar arriba
    } else {
      if (this.avatar) this.avatar.position.z = 0;
    }
  }

  onResize() {
    const w = this.canvas.clientWidth, h = this.canvas.clientHeight;
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(w, h, false);
  }

  animate() {
    requestAnimationFrame(() => this.animate());
    const dt = this.clock.getDelta();
    const t = this.time += dt;

    if (this.avatar) {
      // Respiración
      const breath = Math.sin(t * 1.1) * 0.015;
      this.avatar.position.y = this.baseY + breath;

      // Sway sutil
      this.avatar.rotation.y = Math.sin(t * 0.3) * 0.03;
      
      // Head movimientos según estado
      if (this.bones.Head) {
        const head = this.bones.Head;
        if (this.state === 'idle') {
          head.rotation.y = Math.sin(t * 0.4) * 0.08;
          head.rotation.x = Math.sin(t * 0.6) * 0.03;
        } else if (this.state === 'listening') {
          head.rotation.y = Math.sin(t * 0.5) * 0.12;
          head.rotation.x = -0.08 + Math.sin(t*1.2)*0.02;
          // inclinación escuchando
        } else if (this.state === 'thinking') {
          head.rotation.y = Math.sin(t*0.3)*0.15;
          head.rotation.x = -0.12;
        } else if (this.state === 'speaking') {
          head.rotation.y = Math.sin(t*0.7)*0.1;
          head.rotation.x = Math.sin(t*0.9)*0.05;
          head.rotation.z = Math.sin(t*0.5)*0.03;
        }
      }

      // Parpadeo
      this.blinkTimer += dt;
      if (this.blinkTimer > this.nextBlink) {
        this.doBlink();
        this.blinkTimer = 0;
        this.nextBlink = 1.5 + Math.random()*3.5;
      }

      // Lipsync basado en volumen de voz
      const vol = this.voice ? this.voice.getVolume() : 0;
      this.updateLipsync(vol, dt, t);

      // Gestos de manos al hablar
      if (this.state === 'speaking') {
        if (this.bones.LeftArm) {
          this.bones.LeftArm.rotation.x = Math.sin(t*2.1)*0.25;
          this.bones.LeftArm.rotation.z = -0.15 + Math.sin(t*1.3)*0.2;
        }
        if (this.bones.RightArm) {
          this.bones.RightArm.rotation.x = Math.cos(t*1.8)*0.22;
          this.bones.RightArm.rotation.z = 0.15 + Math.cos(t*1.5)*0.18;
        }
      } else if (this.state === 'listening') {
        if (this.bones.LeftHand || this.bones.RightHand) {
          // mano al oído
        }
      } else {
        // idle: manos relajadas con leve movimiento
        if (this.bones.LeftArm) {
          this.bones.LeftArm.rotation.x *= 0.95;
          this.bones.LeftArm.rotation.z = -0.12 + Math.sin(t*0.5)*0.04;
        }
        if (this.bones.RightArm) {
          this.bones.RightArm.rotation.x *= 0.95;
          this.bones.RightArm.rotation.z = 0.12 + Math.cos(t*0.5)*0.04;
        }
      }
    }

    if (this.mixer) this.mixer.update(dt);

    // Leve movimiento de cámara vivo
    this.camera.position.x = Math.sin(t*0.15)*0.08;
    this.camera.lookAt(0 + Math.sin(t*0.12)*0.05, 1.45, 0);

    this.renderer.render(this.scene, this.camera);
  }

  doBlink() {
    // Si hay morph targets, usarlos
    let blinkIdx = -1;
    let targetMesh = null;
    for (const m of this.morphTargets) {
      if (m.morphTargetDictionary) {
        for (const k in m.morphTargetDictionary) {
          if (/blink|eye.*close|eyesclosed/i.test(k)) {
            blinkIdx = m.morphTargetDictionary[k];
            targetMesh = m;
            break;
          }
        }
        if (targetMesh) break;
      }
    }
    if (targetMesh && blinkIdx >= 0) {
      const start = targetMesh.morphTargetInfluences[blinkIdx] || 0;
      let p = 0;
      const anim = () => {
        p += 0.18;
        if (p < 1) {
          targetMesh.morphTargetInfluences[blinkIdx] = Math.sin(p * Math.PI);
          requestAnimationFrame(anim);
        } else {
          targetMesh.morphTargetInfluences[blinkIdx] = 0;
        }
      };
      anim();
    } else {
      // Fallback: escalar ojos
      const eyes = [this.bones.LeftEye, this.bones.RightEye].filter(Boolean);
      if (eyes.length) {
        eyes.forEach(e => e.scale.y = 0.1);
        setTimeout(()=> eyes.forEach(e=> e.scale.y=1), 120);
      }
    }
  }

  updateLipsync(volume, dt, t) {
    // Buscar morph de boca: mouthOpen, jawOpen, viseme
    let applied = false;
    for (const mesh of this.morphTargets) {
      if (!mesh.morphTargetDictionary) continue;
      for (const name in mesh.morphTargetDictionary) {
        if (/mouthopen|jawopen|mouth_open|jaw_open|viseme|aa/i.test(name)) {
          const idx = mesh.morphTargetDictionary[name];
          // volumen 0..1 mapea a apertura
          const target = volume * 0.9;
          const cur = mesh.morphTargetInfluences[idx] || 0;
          mesh.morphTargetInfluences[idx] = THREE.MathUtils.lerp(cur, target, 0.4);
          applied = true;
          break;
        }
      }
      if (applied) break;
    }
    // Si no hay morph, escalar boca procedural
    if (!applied && this.bones.Mouth) {
      const s = 1 + volume * 1.8;
      this.bones.Mouth.scale.y = s;
      this.bones.Mouth.scale.x = 1 + volume*0.3;
    }
    // También mover mandíbula si es hueso
    if (this.bones.Head && volume > 0.1) {
      // micro apertura cabeza
      this.bones.Head.rotation.x += volume * 0.04 * Math.sin(t*18);
    }
  }
}
