# RedP3

Proyecto nuevo e independiente (no toca la app actual de este repo).

Granja de compilación distribuida P2P: un teléfono comandante reparte
tareas entre los demás teléfonos conectados, que suman su fuerza.

## Contenido

- `web/` — la interfaz de la plataforma, construida con el sistema de
  diseño oficial de GitHub (Primer CSS + Octicons): Inicio, Repositorios,
  Actions, Runners, Artefactos y Consola.
  `web/index.html` es el archivo final (autocontenido); `web/_src.html`
  es su fuente editable (con marcadores para inyectar Primer y los iconos).
  Por ahora corre con datos de ejemplo en el navegador; el núcleo real
  (Rust) se conectará a estas mismas pantallas, no se rehace la interfaz.

## Plan por fases

1. Núcleo en PC: un dispositivo manda una orden a otro y recibe el resultado.
2. Los nodos se descubren solos en la red local (mDNS/UDP) + emparejamiento seguro.
3. Recetas YAML + reparto inteligente de tareas + caché por hash.
4. App Android (Foreground Service + terminal) envolviendo el núcleo.
5. VPS como nodo semilla/relay + panel web estilo GitHub.
