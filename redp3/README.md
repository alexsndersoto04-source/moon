# RedP3

Proyecto nuevo e independiente (no toca la app actual de este repo).

Granja de compilación distribuida P2P: un teléfono comandante reparte
tareas entre los demás teléfonos conectados, que suman su fuerza.

## Contenido

- `web/` — la interfaz de la plataforma: app móvil primero, tema claro,
  sin bordes duros, iconos SVG. Panel, Repositorios, Pipelines, Nodos,
  Artefactos y Consola.
  Por ahora corre con datos de ejemplo en el navegador; el núcleo real
  (Rust) se conectará a estas mismas pantallas, no se rehace la interfaz.

## Plan por fases

1. Núcleo en PC: un dispositivo manda una orden a otro y recibe el resultado.
2. Los nodos se descubren solos en la red local (mDNS/UDP) + emparejamiento seguro.
3. Recetas YAML + reparto inteligente de tareas + caché por hash.
4. App Android (Foreground Service + terminal) envolviendo el núcleo.
5. VPS como nodo semilla/relay + panel web estilo GitHub.
