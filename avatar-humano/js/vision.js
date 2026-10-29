export class Vision {
  constructor(videoEl, wrapEl) {
    this.video = videoEl;
    this.wrap = wrapEl;
    this.stream = null;
    this.active = false;
    this.canvas = document.createElement('canvas');
  }

  async start() {
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({ 
        video: { facingMode: 'user', width: { ideal: 640 }, height: { ideal: 480 } }, 
        audio: false 
      });
      this.video.srcObject = this.stream;
      await this.video.play();
      this.active = true;
      this.wrap.classList.add('active');
      return true;
    } catch (e) {
      console.error("Camera error", e);
      return false;
    }
  }

  stop() {
    if (this.stream) {
      this.stream.getTracks().forEach(t => t.stop());
      this.stream = null;
    }
    this.active = false;
    this.wrap.classList.remove('active');
  }

  toggle() {
    if (this.active) this.stop();
    else this.start();
    return this.active;
  }

  // Captura un frame como base64 para enviar a IA con visión
  captureFrame() {
    if (!this.active || !this.video.videoWidth) return null;
    const w = 480;
    const h = Math.round((this.video.videoHeight / this.video.videoWidth) * w);
    this.canvas.width = w;
    this.canvas.height = h;
    const ctx = this.canvas.getContext('2d');
    // espejo para que se vea natural
    ctx.translate(w, 0); ctx.scale(-1, 1);
    ctx.drawImage(this.video, 0, 0, w, h);
    return this.canvas.toDataURL('image/jpeg', 0.6);
  }

  getContextForBrain() {
    if (!this.active) return { hasCamera: false, description: null, imageBase64: null };
    const frame = this.captureFrame();
    return {
      hasCamera: true,
      description: "Usuario con cámara activa, visible por cámara frontal",
      imageBase64: frame
    };
  }
}
