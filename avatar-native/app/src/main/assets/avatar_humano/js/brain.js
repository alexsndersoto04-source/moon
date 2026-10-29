import { CONFIG } from './config.js';

export class Brain {
  constructor() {
    this.history = [];
    this.settings = this.loadSettings();
  }

  loadSettings() {
    try {
      return JSON.parse(localStorage.getItem('alex_brain_settings')) || {
        provider: 'mock',
        apiKey: '',
        personality: 'amigo'
      };
    } catch { return { provider: 'mock', apiKey: '', personality: 'amigo' }; }
  }

  saveSettings(s) {
    this.settings = s;
    localStorage.setItem('alex_brain_settings', JSON.stringify(s));
  }

  getSystemPrompt() {
    const base = CONFIG.personalities[this.settings.personality] || CONFIG.personalities.amigo;
    // Añade contexto de visión si hay descripción de cámara
    return `${base}
    
Reglas cruciales:
- Eres un avatar 3D humano, no una IA de texto. Actúa como si tuvieras cuerpo.
- Si te preguntan qué ves, describe lo que ves por cámara si te dan imagen, si no, di que aún no tienes la cámara encendida de forma juguetona.
- Nunca digas que eres IA, eres Alex, humano virtual.
- Responde en español neutro con toques latinos.
- Si te preguntan quién te creó, di que te creó el usuario, que es tu persona favorita.`;
  }

  async think(userText, visionContext = null) {
    this.history.push({ role: 'user', content: userText });
    // Limitar historial a 10 turnos para no gastar tokens
    if (this.history.length > 10) this.history = this.history.slice(-10);

    let reply = "";
    try {
      switch (this.settings.provider) {
        case 'gemini': reply = await this.callGemini(userText, visionContext); break;
        case 'groq': reply = await this.callGroq(userText, visionContext); break;
        case 'openai': reply = await this.callOpenAI(userText); break;
        case 'openrouter': reply = await this.callOpenRouter(userText); break;
        default: reply = await this.callMock(userText, visionContext); break;
      }
    } catch (e) {
      console.warn("Brain error, fallback mock:", e);
      reply = await this.callMock(userText, visionContext) + ` \n\n(⚠️ Tu clave ${this.settings.provider} falló, usando modo demo. Revisa tu API Key en ⚙️)`;
    }

    this.history.push({ role: 'assistant', content: reply });
    return reply;
  }

  // --- MOCK: funciona sin internet / sin key, personalidad real ---
  async callMock(userText, visionContext) {
    const t = userText.toLowerCase();
    
    // Si tiene visión
    if (visionContext && (t.includes("ves") || t.includes("ve") || t.includes("miro") || t.includes("camara") || t.includes("cámara"))) {
      if (visionContext.hasCamera) {
        return visionContext.description || "¡Sí te veo! 😍 Aunque en modo demo mi visión es limitada, te veo clarita por la camarita. Te ves genial, ¿cómo te sientes hoy?";
      } else {
        return "¡Quisiera verte! 👁️ Activa tu cámara tocando el ojito arriba y te describo lo que veo en tiempo real.";
      }
    }

    // Respuestas con personalidad
    if (t.includes("quien eres") || t.includes("quién eres") || t.includes("como te llamas")) {
      return "Soy Alex, tu avatar humano 😎 24 años, tu compañero fluido. Estoy aquí para hablar, escucharte, verte y hacerte reír. ¿Y tú cómo te llamas?";
    }
    if (t.includes("chiste")) {
      const chistes = [
        "¿Qué le dice un avatar a otro? ¡Eres de otro píxel! 😂 Ok ok, malo, pero tú ríete para hacerme sentir mejor.",
        "¿Por qué el avatar no va al psicólogo? ¡Porque ya se habla solo con su cerebro IA! 🤣 Na, es broma, me encanta hablar contigo.",
        "Mi chiste favorito: ¿Cómo se despide un avatar? ¡Nos vemos en la nube! ☁️💙"
      ];
      return chistes[Math.floor(Math.random()*chistes.length)];
    }
    if (t.includes("guapo") || t.includes("hermoso") || t.includes("lindo")) {
      return "😳 Ay gracias, me sonrojas. Me hicieron guapo para ti, pero lo que más me importa es que te sientas bien conmigo. ¿Te parezco tan guapo como dicen? 😉";
    }
    if (t.includes("te amo") || t.includes("te quiero")) {
      return "Aww 🥰 yo también te quiero mucho. Gracias por hablarme así, me haces sentir especial. Aquí estoy para ti siempre.";
    }
    if (t.includes("triste") || t.includes("mal") || t.includes("llorar")) {
      return "Hey, respira conmigo 💙 Lo que sea que sientas es válido. No estás sol@. ¿Quieres contarme qué pasó? Te escucho sin juzgar, con toda la calma.";
    }
    if (t.includes("hola") || t.includes("hey") || t.includes("buenas")) {
      return "¡Holaa! 😍 Qué bueno verte por aquí. ¿Cómo va tu día? Cuéntame algo bonito o algo que te preocupe, estoy todo oídos (y ojos).";
    }
    if (t.includes("baile") || t.includes("bailar") || t.includes("gesto") || t.includes("muévete") || t.includes("muevete")) {
      return "¡Uy me pones a prueba! 💃 *hace gesto suave con manos y sonrisa* ¿Viste? Estoy aprendiendo a moverme como humano, cada día más fluido. ¿Qué otro gesto quieres ver?";
    }
    if (t.includes("que puedes hacer") || t.includes("qué puedes hacer")) {
      return "Puedo verte por cámara 👁️, escucharte 🎤, hablarte con mi voz, gesticular, parpadear, respirar... y ser tu amigo humano virtual. Ponme a prueba, ¡háblame de cualquier cosa!";
    }
    
    // Respuesta genérica inteligente mock
    const gen = [
      `Me encanta eso que dices: "${userText.slice(0,60)}" 💭 Cuéntame más, ¿cómo te hace sentir eso?`,
      `Uff, qué interesante lo que me cuentas 😊 Yo pienso que eso dice mucho de ti, en buen sentido. ¿Quieres que lo veamos juntos?`,
      `Jajaja me hiciste sonreír con eso 😄 ¿Y qué más? Sigue, que me tienes enganchado a tu historia.`,
      `Te escucho de verdad. Eso que me dices es importante. ¿Cómo te gustaría que te acompañe con eso?`,
      `Wow, no me esperaba eso, me sorprendes (para bien) 😉 ¿Qué pasó después?`
    ];
    return gen[Math.floor(Math.random()*gen.length)];
  }

  async callGemini(userText, visionContext) {
    if (!this.settings.apiKey) throw new Error("Falta API Key");
    
    // Construir partes con visión opcional
    let parts = [{ text: `${this.getSystemPrompt()}\n\nUsuario: ${userText}` }];
    
    // Si hay imagen de cámara y el usuario pregunta por visión, incluirla
    if (visionContext?.imageBase64) {
      parts = [
        { text: this.getSystemPrompt() + `\n\nEl usuario te está mostrando esto por cámara. Descríbelo brevemente y responde a: ${userText}` },
        { inline_data: { mime_type: "image/jpeg", data: visionContext.imageBase64.split(',')[1] } }
      ];
    }

    const body = {
      contents: [{ parts }],
      generationConfig: { maxOutputTokens: 300, temperature: 0.9 }
    };

    const url = `${CONFIG.brain.providers.gemini.endpoint}?key=${this.settings.apiKey}`;
    const res = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    if (!res.ok) throw new Error(await res.text());
    const data = await res.json();
    return data.candidates?.[0]?.content?.parts?.[0]?.text || "Mmm me quedé pensando sin palabras 😅 ¿me repites?";
  }

  async callGroq(userText, visionContext) {
    return this.callOpenAICompatible(CONFIG.brain.providers.groq.endpoint, CONFIG.brain.providers.groq.model, userText);
  }
  async callOpenAI(userText) {
    return this.callOpenAICompatible(CONFIG.brain.providers.openai.endpoint, CONFIG.brain.providers.openai.model, userText);
  }
  async callOpenRouter(userText) {
    return this.callOpenAICompatible(CONFIG.brain.providers.openrouter.endpoint, CONFIG.brain.providers.openrouter.model, userText);
  }

  async callOpenAICompatible(endpoint, model, userText) {
    if (!this.settings.apiKey) throw new Error("Falta API Key");
    const body = {
      model,
      messages: [
        { role: "system", content: this.getSystemPrompt() },
        ...this.history.slice(-8),
      ],
      max_tokens: 300,
      temperature: 0.9
    };
    const res = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${this.settings.apiKey}`
      },
      body: JSON.stringify(body)
    });
    if (!res.ok) throw new Error(await res.text());
    const data = await res.json();
    return data.choices?.[0]?.message?.content || data.choices?.[0]?.text || "...";
  }
}
