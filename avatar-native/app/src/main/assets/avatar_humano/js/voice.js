export class Voice {
  constructor() {
    this.synth = window.speechSynthesis;
    this.voices = [];
    this.selectedVoice = null;
    this.isListening = false;
    this.recognition = null;
    this.onResult = null; // callback texto reconocido
    this.onState = null;  // callback estado
    this.analyser = null;
    this.audioContext = null;
    this.volume = 0;
    this.isSpeaking = false;
    this.speed = 1.0;
    this.loadVoices();
  }

  loadVoices() {
    const update = () => {
      this.voices = this.synth.getVoices();
      // Preferir voz masculina español
      const esVoices = this.voices.filter(v => v.lang.startsWith('es'));
      if (esVoices.length) {
        // Buscar voz masculina: Google español, Jorge, etc.
        this.selectedVoice = esVoices.find(v => /jorge|diego|carlos|google.*es/i.test(v.name)) || esVoices[0];
      } else {
        this.selectedVoice = this.voices[0];
      }
      if (this.onVoices) this.onVoices(this.voices);
    };
    update();
    if (this.synth.onvoiceschanged !== undefined) this.synth.onvoiceschanged = update;
    setTimeout(update, 500);
  }

  getVoices() { return this.voices; }
  setVoiceByName(name) {
    const v = this.voices.find(x => x.name === name);
    if (v) this.selectedVoice = v;
  }
  setSpeed(v){ this.speed = v; }

  speak(text) {
    this.synth.cancel();
    return new Promise(resolve => {
      const utter = new SpeechSynthesisUtterance(text);
      if (this.selectedVoice) utter.voice = this.selectedVoice;
      utter.rate = this.speed;
      utter.pitch = 1.0;
      utter.volume = 1.0;

      // Analizador para lipsync (usando oscillator dummy + volumen estimado)
      this.isSpeaking = true;
      this.volume = 0.5;
      
      // Estimador de volumen por texto (para que boca se mueva aunque no haya audio analyser real)
      let idx = 0;
      const tick = setInterval(() => {
        if (!this.isSpeaking) { clearInterval(tick); return; }
        // Simular volumen según carácter (vocales = boca más abierta)
        const c = text[idx % text.length]?.toLowerCase() || 'a';
        const isVowel = /[aeiouáéíóú]/.test(c);
        this.volume = isVowel ? 0.7 + Math.random()*0.3 : 0.2 + Math.random()*0.4;
        idx++;
      }, 80);

      utter.onstart = () => { this.isSpeaking = true; if(this.onState) this.onState('speaking'); };
      utter.onend = () => {
        this.isSpeaking = false; this.volume = 0;
        clearInterval(tick);
        if(this.onState) this.onState('idle');
        resolve();
      };
      utter.onerror = () => {
        this.isSpeaking = false; this.volume=0; clearInterval(tick); resolve();
      };
      this.synth.speak(utter);
    });
  }

  stop() {
    this.synth.cancel();
    this.isSpeaking = false;
    this.volume = 0;
  }

  // Speech Recognition (gratis, usa micrófono del teléfono)
  startListening() {
    if (this.isListening) return;
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) {
      if (this.onResult) this.onResult(null, "Tu navegador no soporta reconocimiento de voz. Usa Chrome en Android. Mientras, puedes escribirle 😊");
      return;
    }
    this.recognition = new SR();
    this.recognition.lang = 'es-ES';
    this.recognition.interimResults = false;
    this.recognition.continuous = false;
    this.recognition.maxAlternatives = 1;

    this.isListening = true;
    if (this.onState) this.onState('listening');

    this.recognition.onresult = (e) => {
      const txt = e.results[0][0].transcript;
      this.isListening = false;
      if (this.onResult) this.onResult(txt);
      if (this.onState) this.onState('idle');
    };
    this.recognition.onerror = (e) => {
      this.isListening = false;
      if (this.onState) this.onState('idle');
      let msg = "No te escuché bien, intenta de nuevo 🎤";
      if (e.error === 'not-allowed') msg = "Permiso de micrófono denegado. Actívalo en ajustes del navegador.";
      if (this.onResult) this.onResult(null, msg);
    };
    this.recognition.onend = () => {
      this.isListening = false;
      if (this.onState) this.onState('idle');
    };
    try { this.recognition.start(); } catch {}
  }

  stopListening() {
    if (this.recognition) try { this.recognition.stop(); } catch {}
    this.isListening = false;
  }

  getVolume() { return this.volume; }
}
