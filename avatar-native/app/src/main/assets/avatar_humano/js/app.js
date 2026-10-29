import { Avatar3D } from './avatar.js';
import { Brain } from './brain.js';
import { Voice } from './voice.js';
import { Vision } from './vision.js';

const canvas = document.getElementById('avatar-canvas');
const chatHistory = document.getElementById('chat-history');
const textInput = document.getElementById('text-input');
const btnSend = document.getElementById('btn-send');
const btnMic = document.getElementById('btn-mic');
const btnCamera = document.getElementById('btn-camera');
const btnSettings = document.getElementById('btn-settings');
const settingsModal = document.getElementById('settings-modal');
const closeSettings = document.getElementById('close-settings');
const saveSettings = document.getElementById('save-settings');
const testVoice = document.getElementById('test-voice');
const providerSel = document.getElementById('brain-provider');
const apiKeyInput = document.getElementById('api-key-input');
const apiKeyHint = document.getElementById('api-key-hint');
const personalitySel = document.getElementById('personality');
const voiceSelect = document.getElementById('voice-select');
const voiceSpeed = document.getElementById('voice-speed');
const speedVal = document.getElementById('speed-val');
const statusText = document.getElementById('status-text');
const statusDot = document.getElementById('status-dot');
const visionVideo = document.getElementById('vision-video');
const visionWrap = document.getElementById('vision-preview-wrap');
const closeVision = document.getElementById('close-vision');
const toastEl = document.getElementById('toast');

const brain = new Brain();
const voice = new Voice();
const vision = new Vision(visionVideo, visionWrap);
let avatar;

// Init avatar 3D
avatar = new Avatar3D(canvas, voice);

// Voice callbacks
voice.onState = (s) => {
  if (s === 'speaking') { setStatus('Hablando...', '#ec4899'); avatar.setState('speaking'); }
  else if (s === 'listening') { setStatus('Escuchándote...', '#f59e0b'); avatar.setState('listening'); }
  else { setStatus('Listo para ti', '#10b981'); avatar.setState('idle'); }
};
voice.onVoices = (voices) => {
  voiceSelect.innerHTML = '';
  voices.forEach(v => {
    const o = document.createElement('option');
    o.value = v.name; o.textContent = `${v.name} (${v.lang})`;
    voiceSelect.appendChild(o);
  });
  if (voice.selectedVoice) voiceSelect.value = voice.selectedVoice.name;
};
voice.onResult = (txt, err) => {
  if (err) { addMessage(err, 'bot'); showToast(err); return; }
  if (txt) handleUserInput(txt);
};

function setStatus(text, color) {
  statusText.textContent = text;
  statusDot.style.background = color;
  statusDot.style.boxShadow = `0 0 10px ${color}`;
}

function showToast(msg) {
  toastEl.textContent = msg;
  toastEl.classList.add('show');
  setTimeout(()=> toastEl.classList.remove('show'), 3000);
}

function addMessage(text, who='bot') {
  const div = document.createElement('div');
  div.className = `msg ${who}`;
  div.innerHTML = `
    <div class="msg-avatar">${who==='user' ? 'TÚ' : 'A'}</div>
    <div><div class="msg-bubble">${escapeHtml(text)}</div><div class="msg-time">${new Date().toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'})}</div></div>
  `;
  chatHistory.appendChild(div);
  chatHistory.scrollTop = chatHistory.scrollHeight;
  return div;
}
function escapeHtml(s){
  return s.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('\n','<br>');
}

async function handleUserInput(text) {
  if (!text.trim()) return;
  addMessage(text, 'user');
  textInput.value = '';

  // Estados visuales
  setStatus('Pensando...', '#7c3aed'); avatar.setState('thinking');
  addMessage('...', 'bot');
  const typingEl = chatHistory.lastElementChild;
  
  // Visión: si cámara activa, capturar frame
  const visionCtx = vision.getContextForBrain();
  
  // Cerebro
  const reply = await brain.think(text, visionCtx);
  
  // Quitar typing
  typingEl.remove();

  addMessage(reply, 'bot');
  setStatus('Hablando...', '#ec4899'); avatar.setState('speaking');
  await voice.speak(reply);
  setStatus('Listo para ti', '#10b981'); avatar.setState('idle');
}

// Eventos Input
btnSend.addEventListener('click', () => handleUserInput(textInput.value));
textInput.addEventListener('keydown', e => { if (e.key==='Enter') handleUserInput(textInput.value); });

// Mic - click to toggle
let micActive = false;
btnMic.addEventListener('click', () => {
  if (voice.isListening) { voice.stopListening(); btnMic.classList.remove('listening'); return; }
  btnMic.classList.add('listening');
  setStatus('Escuchándote...', '#f59e0b'); avatar.setState('listening');
  voice.startListening();
  // quitar animación cuando termina
  const check = setInterval(()=> {
    if (!voice.isListening) { btnMic.classList.remove('listening'); clearInterval(check); }
  }, 200);
});

// Mantener presionado en móvil también
btnMic.addEventListener('touchstart', (e)=>{ e.preventDefault(); btnMic.click(); }, {passive:false});

// Sugerencias
document.querySelectorAll('.sug').forEach(b=> b.addEventListener('click', ()=> handleUserInput(b.dataset.text)));

// Cámara
btnCamera.addEventListener('click', async () => {
  const ok = await vision.start();
  if (!ok) {
    showToast("No se pudo abrir la cámara. Revisa permisos.");
    addMessage("No pude abrir tu cámara 😔 Revisa que me diste permiso en el navegador (candadito arriba).", 'bot');
  } else {
    showToast("Cámara activa - Alex ya puede verte 👁️");
    addMessage("¡Ahora sí te veo! 👀 Holaa, te ves muy bien. Háblame y te describo si quieres.", 'bot');
  }
  btnCamera.style.background = vision.active ? '#10b981' : '';
  btnCamera.style.color = vision.active ? '#fff' : '';
});
closeVision.addEventListener('click', ()=> {
  vision.stop();
  btnCamera.style.background=''; btnCamera.style.color='';
});

// Settings modal
function openSettings(){
  const s = brain.settings;
  providerSel.value = s.provider;
  apiKeyInput.value = s.apiKey;
  personalitySel.value = s.personality;
  voiceSpeed.value = voice.speed;
  speedVal.textContent = voice.speed;
  updateKeyHint();
  settingsModal.classList.remove('hidden');
}
function closeModal(){ settingsModal.classList.add('hidden'); }
btnSettings.addEventListener('click', openSettings);
closeSettings.addEventListener('click', closeModal);
settingsModal.addEventListener('click', e=> { if(e.target===settingsModal) closeModal(); });

providerSel.addEventListener('change', updateKeyHint);
function updateKeyHint(){
  const v = providerSel.value;
  if (v==='mock') apiKeyHint.innerHTML = "Modo demo <b>no necesita clave</b>, ya funciona. Solo elige tu personalidad.";
  else if (v==='gemini') apiKeyHint.innerHTML = "Gratis en <b>aistudio.google.com</b> → Get API Key → Crear. Sin tarjeta. Límite generoso.";
  else if (v==='groq') apiKeyHint.innerHTML = "Gratis en <b>console.groq.com</b> → API Keys → Create.";
  else if (v==='openai') apiKeyHint.innerHTML = "De plataforma.openai.com. Tiene límites y costo.";
  else if (v==='openrouter') apiKeyHint.innerHTML = "Gratis en openrouter.ai, modelo llama-3.2-3b:free.";
}

saveSettings.addEventListener('click', ()=>{
  const s = {
    provider: providerSel.value,
    apiKey: apiKeyInput.value.trim(),
    personality: personalitySel.value
  };
  brain.saveSettings(s);
  if (voiceSelect.value) voice.setVoiceByName(voiceSelect.value);
  voice.setSpeed(parseFloat(voiceSpeed.value));
  closeModal();
  showToast(`¡Listo! Cerebro: ${s.provider} | Personalidad: ${s.personality}`);
  // Mensaje de prueba
  addMessage(`Perfecto, ya configuré mi cerebro en modo <b>${s.provider}</b> y personalidad <b>${s.personality}</b>. ${s.provider==='mock' ? 'Estoy en modo demo, ya fluido sin clave 😊' : 'Ya tengo tu clave guardada solo en tu móvil.'} Háblame y verás lo fluido que soy.`, 'bot');
});

voiceSelect.addEventListener('change', ()=> voice.setVoiceByName(voiceSelect.value));
voiceSpeed.addEventListener('input', ()=> speedVal.textContent = voiceSpeed.value);

testVoice.addEventListener('click', ()=>{
  const t = "Hola, soy Alex, tu avatar humano, super guapo y fluido. ¿Me escuchas bien? Puedo hablar, verte y gesticular como una persona real.";
  avatar.setState('speaking');
  voice.speak(t).then(()=> avatar.setState('idle'));
});

// Cargar ajustes guardados al inicio
(() => {
  const s = brain.settings;
  providerSel.value = s.provider;
  apiKeyInput.value = s.apiKey;
  personalitySel.value = s.personality;
  updateKeyHint();
})();

// Mensaje de bienvenida con voz al tocar pantalla (móvil necesita gesto)
let firstInteraction = true;
document.body.addEventListener('click', ()=> {
  if (firstInteraction) {
    firstInteraction = false;
    // Desbloquear audio en iOS/Android
    if (voice.synth) voice.synth.cancel();
  }
}, {once:true});

// Mostrar ayuda inicial en móvil
setTimeout(()=> {
  if (window.innerWidth < 900) showToast("Toca 🎤 para hablar • 👁️ para que te vea");
}, 1500);
