const canvas = document.getElementById('scene');
const ctx = canvas.getContext('2d');
const stats = document.getElementById('stats');

let w = (canvas.width = window.innerWidth);
let h = (canvas.height = window.innerHeight);
const pointer = { x: w / 2, y: h / 2, active: false };

window.addEventListener('resize', () => {
  w = canvas.width = window.innerWidth;
  h = canvas.height = window.innerHeight;
});

window.addEventListener('mousemove', (e) => {
  pointer.x = e.clientX;
  pointer.y = e.clientY;
  pointer.active = true;
});

window.addEventListener('click', () => {
  for (const p of particles) {
    const dx = p.x - pointer.x;
    const dy = p.y - pointer.y;
    const dist = Math.hypot(dx, dy) + 1;
    if (dist < 220) {
      p.vx += (dx / dist) * 9;
      p.vy += (dy / dist) * 9;
    }
  }
});

const particles = Array.from({ length: 160 }, (_, i) => ({
  x: Math.random() * w,
  y: Math.random() * h,
  vx: (Math.random() - 0.5) * 1.5,
  vy: (Math.random() - 0.5) * 1.5,
  r: 1.5 + (i % 3),
  hue: 185 + (i % 70),
}));

let frame = 0;
function loop() {
  ctx.fillStyle = 'rgba(3, 7, 18, 0.22)';
  ctx.fillRect(0, 0, w, h);

  for (let i = 0; i < particles.length; i++) {
    const p = particles[i];
    if (pointer.active) {
      const dx = pointer.x - p.x;
      const dy = pointer.y - p.y;
      const dist = Math.hypot(dx, dy) + 12;
      if (dist < 280) {
        p.vx += (dx / dist) * 0.28;
        p.vy += (dy / dist) * 0.28;
      }
    }
    p.vx *= 0.985;
    p.vy *= 0.985;
    p.x = (p.x + p.vx + w) % w;
    p.y = (p.y + p.vy + h) % h;

    ctx.beginPath();
    ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
    ctx.fillStyle = `hsl(${p.hue}, 90%, 65%)`;
    ctx.fill();
  }

  frame++;
  if (frame % 15 === 0) {
    stats.textContent = `Particles: ${particles.length} · Frame: ${frame}`;
  }
  requestAnimationFrame(loop);
}

loop();
