# Strata Engineering — Plataforma Empresarial de 150 Agentes Especialistas

Proyecto independiente (completamente separado de cualquier otro proyecto del repositorio) que ofrece una plataforma web empresarial responsiva para programar, analizar, verificar, depurar, corregir errores y ejecutar proyectos complejos a gran escala con **150 agentes especialistas a tiempo completo**.

## Cómo instalar y abrir en Android (Termux)

Ejecuta estos comandos en Termux:

```bash
pkg update -y && pkg install -y git nodejs-lts
git clone -b arena/c29a89cb-moon --single-branch https://github.com/alexsndersoto04-source/moon.git
cd moon/strata-platform
chmod +x start.sh
./start.sh
```

Luego abre Chrome (o cualquier navegador de tu teléfono) y entra a:

- **`http://127.0.0.1:5000`**

Para volver a iniciarla más adelante (sin volver a clonar ni instalar):

```bash
cd ~/moon/strata-platform && ./start.sh
```

## Cómo abrir en Linux / macOS / servidor

```bash
cd strata-platform
npm install --omit=optional
npm start
```

Abre **`http://127.0.0.1:5000`** (puerto configurable con `PORT=5000`).

## Pruebas Independientes

```bash
cd strata-platform
npm test
```
