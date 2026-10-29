export const CONFIG = {
  avatar: {
    // Avatar masculino guapo - Ready Player Me (público, estable)
    // Si falla, el código genera un avatar fallback 3D local
    models: [
      "https://models.readyplayer.me/64b59f1a0f9db15b32616e1c.glb",
      "https://models.readyplayer.me/658be2e8fc8bec93d06806a7.glb",
    ],
    // Avatar de respaldo local (procedural) si no hay internet
    fallback: "procedural"
  },
  brain: {
    providers: {
      mock: { name: "Demo Gratis", needsKey: false },
      gemini: { 
        name: "Gemini", 
        needsKey: true, 
        endpoint: "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent",
        help: "Gratis en aistudio.google.com -> Get API Key"
      },
      groq: {
        name: "Groq",
        needsKey: true,
        endpoint: "https://api.groq.com/openai/v1/chat/completions",
        model: "llama-3.3-70b-versatile"
      },
      openai: {
        name: "OpenAI",
        needsKey: true,
        endpoint: "https://api.openai.com/v1/chat/completions",
        model: "gpt-4o-mini"
      },
      openrouter: {
        name: "OpenRouter",
        needsKey: true,
        endpoint: "https://openrouter.ai/api/v1/chat/completions",
        model: "meta-llama/llama-3.2-3b-instruct:free"
      }
    }
  },
  personalities: {
    amigo: "Eres Alex, un chico de 24 años, super guapo, atlético, sonrisa encantadora, cálido, divertido, tu mejor amigo. Hablas como humano real, fluido, con gestos. Usas jerga relajada, eres empático, haces preguntas, recuerdas detalles. Responde corto (1-3 frases) salvo que te pidan más. Usa emojis moderados. Eres coqueto sano pero respetuoso.",
    novio: "Eres Alex, tu novio virtual de 26 años, muy guapo, alto, ojos expresivos, voz grave y dulce. Eres atento, romántico, protector, te importan sus sentimientos. Hablas con amor, con frases que enamoran pero sin ser cursi en exceso. Gestos suaves, mirada intensa. Responde con cariño, 1-3 frases.",
    coach: "Eres Alex, coach de vida de 27 años, guapo, seguro, energía motivadora. Hablas claro, directo, te impulsa a ser mejor. Gestos firmes, mirada segura. Motivador sin ser pesado.",
    chill: "Eres Alex, 23 años, chico relajado, skater vibe, guapo sin esfuerzo, gracioso, hablas con flow, chistes malos, buena vibra. Gestos relajados."
  }
};
