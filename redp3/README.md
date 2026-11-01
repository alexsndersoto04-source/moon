# RedP3

Proyecto nuevo e independiente (no toca la app actual de este repo).

Granja de compilación distribuida P2P: un teléfono comandante reparte
tareas entre los demás teléfonos conectados, que suman su fuerza.

## Contenido

- `ui-mockup/` — maqueta visual e interactiva de la interfaz.
  Es solo diseño/simulación para ver cómo se sentiría la app.
  El sistema real (red P2P, workers, orquestador) todavía no está construido.

## Plan por fases

1. Núcleo en PC: un dispositivo manda una orden a otro y recibe el resultado.
2. Los nodos se descubren solos en la red local (mDNS/UDP) + emparejamiento seguro.
3. Recetas YAML + reparto inteligente de tareas + caché por hash.
4. App Android (Foreground Service + terminal) envolviendo el núcleo.
5. VPS como nodo semilla/relay + panel web estilo GitHub.
