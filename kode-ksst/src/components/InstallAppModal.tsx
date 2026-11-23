import React, { useEffect, useState } from 'react';
import { Download, ExternalLink, Smartphone, X, CheckCircle2 } from 'lucide-react';
import { useIdeStore } from '../store/useIdeStore';

export const InstallAppModal: React.FC = () => {
  const { showInstallModal, setShowInstallModal, addToast } = useIdeStore();
  const [deferredPrompt, setDeferredPrompt] = useState<any>(null);
  const [installed, setInstalled] = useState(false);

  useEffect(() => {
    const handler = (e: Event) => {
      e.preventDefault();
      setDeferredPrompt(e);
    };
    window.addEventListener('beforeinstallprompt', handler);
    window.addEventListener('appinstalled', () => setInstalled(true));
    return () => window.removeEventListener('beforeinstallprompt', handler);
  }, []);

  if (!showInstallModal) return null;

  const triggerNativeInstall = async () => {
    if (!deferredPrompt) return;
    deferredPrompt.prompt();
    const res = await deferredPrompt.userChoice;
    if (res.outcome === 'accepted') {
      setInstalled(true);
      addToast('KODE KSST instalada en tu pantalla de inicio', 'success');
    }
    setDeferredPrompt(null);
  };

  const renderDeployUrl =
    'https://render.com/deploy?repo=https://github.com/alexsndersoto04-source/moon/tree/arena/6325a5b4-moon';

  return (
    <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="w-full max-w-md max-h-[90vh] overflow-y-auto bg-[#0b1019] border border-slate-700/80 rounded-2xl shadow-2xl">
        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-800 bg-[#090d14]">
          <div className="flex items-center gap-2.5">
            <img src="/icon-192.png" alt="KODE KSST" className="w-8 h-8 rounded-lg" />
            <div>
              <h3 className="text-sm font-semibold text-slate-100">Usar KODE KSST como App Móvil</h3>
              <p className="text-[11px] text-slate-400">Sin terminal ni PC — directo en tu teléfono</p>
            </div>
          </div>
          <button onClick={() => setShowInstallModal(false)} className="p-1.5 rounded-lg hover:bg-slate-800 text-slate-400">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-5 space-y-4 text-xs text-slate-300">
          {installed && (
            <div className="p-3 rounded-lg bg-emerald-500/15 border border-emerald-500/30 text-emerald-300 flex items-center gap-2">
              <CheckCircle2 className="w-4 h-4 shrink-0" />
              <span>¡KODE KSST ya está instalada en tu dispositivo!</span>
            </div>
          )}

          {deferredPrompt && (
            <button
              onClick={triggerNativeInstall}
              className="w-full flex items-center justify-center gap-2 py-2.5 rounded-xl bg-gradient-to-r from-sky-500 to-violet-600 text-white font-semibold text-xs shadow-lg"
            >
              <Download className="w-4 h-4" />
              <span>Instalar KODE KSST App en este teléfono ahora</span>
            </button>
          )}

          <div className="p-3.5 rounded-xl bg-slate-900/80 border border-slate-800 space-y-2">
            <div className="font-semibold text-sky-300 flex items-center gap-1.5">
              <Smartphone className="w-4 h-4" />
              <span>1. Tener tu App permanente con 1 clic (Sin PC)</span>
            </div>
            <p className="text-slate-400 leading-relaxed">
              Como un archivo suelto en el teléfono no incluye compiladores de C++, Python ni Node, puedes activar tu propio servidor App gratuito en la nube directo desde el navegador de tu celular:
            </p>
            <a
              href={renderDeployUrl}
              target="_blank"
              rel="noreferrer"
              className="flex items-center justify-center gap-1.5 w-full py-2 rounded-lg bg-violet-600 hover:bg-violet-500 text-white font-semibold transition"
            >
              <ExternalLink className="w-3.5 h-3.5" />
              <span>Crear mi App en Render (1 clic)</span>
            </a>
          </div>

          <div className="p-3.5 rounded-xl bg-slate-900/80 border border-slate-800 space-y-2">
            <div className="font-semibold text-emerald-300">2. Instalarla con icono en tu pantalla de inicio (PWA)</div>
            <ul className="space-y-1.5 text-slate-400 list-disc pl-4">
              <li>
                <strong className="text-slate-200">Android (Chrome):</strong> Abre tu URL en Chrome → Menú <span className="text-slate-200">⋮</span> → <strong className="text-slate-200">Instalar aplicación</strong> / <strong className="text-slate-200">Añadir a pantalla de inicio</strong>.
              </li>
              <li>
                <strong className="text-slate-200">iPhone (Safari):</strong> Abre tu URL en Safari → botón <strong className="text-slate-200">Compartir</strong> → <strong className="text-slate-200">Añadir a pantalla de inicio</strong>.
              </li>
            </ul>
            <p className="text-[11px] text-slate-500">
              Se abrirá en pantalla completa con su propio icono <strong>KODE KSST</strong>, sin barras de navegador y sin tocar ninguna terminal.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};
