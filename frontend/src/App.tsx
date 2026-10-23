import { useCallback, useEffect, useState, type FormEvent } from 'react';

type Tab = 'shorten' | 'links';
type Link = { id: number; originalUrl: string; shortenedUrl: string };
type LinkListResponse = {
  links: Link[];
  cacheStatuses: CacheStatus[];
};
type CacheStatus = { id: number; active: boolean; expired: boolean; remainingMillis: number; observedAtMillis: number };

// Server observation time orders HTTP/socket snapshots; React never measures the TTL.
function mergeStatuses(previous: Record<number, CacheStatus>, incoming: CacheStatus[]) {
  const next = { ...previous };
  for (const status of incoming) {
    const old = previous[status.id];
    if (old && old.observedAtMillis > status.observedAtMillis) continue;
    next[status.id] = old?.active && status.active
      ? { ...status, remainingMillis: Math.min(old.remainingMillis, status.remainingMillis) }
      : status;
  }
  return next;
}

function initialTab(): Tab {
  try { return localStorage.getItem('shortener-tab') === 'links' ? 'links' : 'shorten'; }
  catch { return 'shorten'; }
}

function getErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Se produjo un error inesperado';
}

async function parseApiError(response: Response): Promise<Error> {
  try {
    const payload = await response.json() as { message?: string };
    return new Error(payload.message || `Error HTTP ${response.status}`);
  } catch { return new Error(`Error HTTP ${response.status}`); }
}

function Icon({ name }: { name: 'link' | 'arrow' | 'grid' | 'copy' | 'check' | 'bolt' | 'database' }) {
  const paths = {
    link: 'M10 13a5 5 0 0 0 7 .2l3-3a5 5 0 0 0-7-7l-1.7 1.7M14 11a5 5 0 0 0-7-.2l-3 3a5 5 0 0 0 7 7l1.7-1.7',
    arrow: 'M5 12h14m-6-6 6 6-6 6',
    grid: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
    copy: 'M9 9h12v12H9zM15 5V3H3v12h2',
    check: 'm5 12 4 4L19 6',
    bolt: 'm13 2-9 12h7l-1 8 10-13h-7z',
    database: 'M20 6c0 2-3.6 4-8 4S4 8 4 6s3.6-4 8-4 8 2 8 4ZM4 6v12c0 2 3.6 4 8 4s8-2 8-4V6M4 12c0 2 3.6 4 8 4s8-2 8-4',
  };
  return <svg className="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]} /></svg>;
}

export default function App() {
  const [tab, setTab] = useState<Tab>(initialTab);
  const [url, setUrl] = useState('');
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<Link | null>(null);
  const [links, setLinks] = useState<Link[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [cacheStatuses, setCacheStatuses] = useState<Record<number, CacheStatus>>({});
  const [cacheSynchronized, setCacheSynchronized] = useState(false);
  const [socketConnected, setSocketConnected] = useState(false);
  const [copied, setCopied] = useState('');

  const loadLinks = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const response = await fetch('/api/links', { cache: 'no-store' });
      if (!response.ok) throw await parseApiError(response);
      const result = await response.json() as LinkListResponse;
      setLinks(result.links);
      setCacheStatuses(previous => mergeStatuses(previous, result.cacheStatuses));
      setCacheSynchronized(true);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally { setLoading(false); }
  }, []);

  useEffect(() => {
    try { localStorage.setItem('shortener-tab', tab); } catch { /* navegador restringido */ }
    if (tab === 'links') void loadLinks();
  }, [tab, loadLinks]);

  useEffect(() => {
    // WebSocket persistente: el servidor lee PTTL de Redis incluso tras recargar.
    // Reconexión simple cuando se pierde el socket temporalmente.
    let disposed = false;
    let socket: WebSocket | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
    const connect = () => {
      if (disposed) return;
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      socket = new WebSocket(`${protocol}//${window.location.host}/ws/cache`);
      socket.onopen = () => setSocketConnected(true);
      socket.onmessage = (event: MessageEvent<string>) => {
        try {
          const statuses = JSON.parse(event.data) as CacheStatus[];
          if (!Array.isArray(statuses)) return;
          setCacheStatuses(previous => mergeStatuses(previous, statuses));
          setCacheSynchronized(true);
        } catch { /* mensaje desconocido */ }
      };
      socket.onclose = () => {
        setSocketConnected(false);
        setCacheSynchronized(false);
        if (!disposed) reconnectTimer = setTimeout(connect, 2000);
      };
      socket.onerror = () => { socket?.close(); };
    };
    connect();
    return () => {
      disposed = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      socket?.close();
    };
  }, []);

  const showLinks = () => {
    if (tab === 'links') void loadLinks(); // Pulsar otra vez = consultar de nuevo sin renovar el TTL.
    else setTab('links');
  };

  const showShorten = () => { setTab('shorten'); setError(''); };

  const shorten = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setCreating(true);
    setError('');
    setCreated(null);
    try {
      const response = await fetch('/api/links', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: url.trim() }),
      });
      if (!response.ok) throw await parseApiError(response);
      const result = await response.json() as Link;
      setCreated(result);

    } catch (err) { setError(getErrorMessage(err)); }
    finally { setCreating(false); }
  };

  const copyLink = async (value: string) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(value);
    } catch { setError('No se pudo copiar. Puedes seleccionar el enlace manualmente.'); }
  };

  return (
    <main className="app-shell">
      <div className="ambient" aria-hidden="true"><span /><span /><span /></div>
      <div className="masthead">
        <div className="wordmark"><span className="brand-mark"><Icon name="link" /></span> HEX<span className="wordmark-divider">/</span>LINK</div>
        <span className="masthead-caption">URL SHORTENER <span>//</span> HEXAGONAL ARCHITECTURE</span>
      </div>
      <header className="header">
        <div className="hero-copy">
          <p className="eyebrow"><span aria-hidden="true" /> PROGRAMACIÓN AVANZADA // SYSTEM INTERFACE</p>
          <h1>ACORTADOR<br /> DE ENLACES<span className="title-period" aria-hidden="true">.</span></h1>
          <p className="subtitle">Arquitectura Hexagonal · Spring Boot · MongoDB · Redis · React</p>
        </div>
        <div className="hero-schematic" aria-hidden="true">
          <span className="schematic-label">LINK ROUTING / CORE</span>
          <div className="link-orbit">
            <svg className="orbit-lines" viewBox="0 0 300 220" fill="none">
              <path className="orbit-outer" d="M150 15 233 63v94l-83 48-83-48V63Z" />
              <path className="orbit-inner" d="m150 35 66 38v74l-66 38-66-38V73Z" />
              <path d="M0 110h99m102 0h99M150 0v45m0 130v45M31 54h27l22 13m140 86 22 13h27" />
              <circle cx="67" cy="63" r="4" /><circle cx="233" cy="157" r="4" />
            </svg>
            <span className="orbit-core"><Icon name="link" /></span>
            <span className="orbit-tag orbit-tag-in">URL_IN</span><span className="orbit-tag orbit-tag-out">LINK_OUT</span>
          </div>
          <span className="schematic-caption">MENOS CARACTERES. MISMO DESTINO.</span>
        </div>
      </header>

      <section className="workspace" aria-label="Acortador de enlaces">
        <div className="toolbar">
          <div className="tabs" role="tablist" aria-label="Secciones">
            <button type="button" role="tab" aria-selected={tab === 'shorten'}
              className={`tab ${tab === 'shorten' ? 'active' : ''}`} onClick={showShorten}>
              <Icon name="link" /> Acortar enlace <span className="tab-index" aria-hidden="true">01</span>
            </button>
            <button type="button" role="tab" aria-selected={tab === 'links'}
              className={`tab ${tab === 'links' ? 'active' : ''}`} onClick={showLinks}>
              <Icon name="grid" /> Ver enlaces <span className="tab-index" aria-hidden="true">02</span>
            </button>
          </div>
          {tab === 'links' && (
            <div className="cache-metrics">
              <span className="cache-placeholder">{socketConnected ? 'Estado en tiempo real' : 'Reconectando estado de caché…'}</span>
              <span className={`connection-dot ${socketConnected ? 'online' : 'offline'}`} title={socketConnected ? 'WebSocket conectado' : 'WebSocket desconectado'} />
            </div>
          )}
        </div>

        {error && <div className="alert" role="alert">{error}</div>}

        {tab === 'shorten' ? (
          <div className="shorten-pane" role="tabpanel">
            <div className="section-title">
              <span className="section-code" aria-hidden="true">01 / LINK COMPOSER</span>
              <h2>Acortar un enlace</h2>
              <p>Pega una URL y obtén un enlace corto que redirige a la dirección original.</p>
            </div>
            <form className="url-form" onSubmit={shorten}>
              <label htmlFor="original-url">Enlace original <span>URL DE DESTINO</span></label>
              <div className="input-shell">
                <span className="input-icon"><Icon name="link" /></span>
                <input id="original-url" type="url" required value={url}
                  onChange={e => setUrl(e.target.value)} placeholder="https://www.youtube.com/watch?v=..."
                  autoFocus autoComplete="url" />
                <span className="input-protocol" aria-hidden="true">URL</span>
              </div>
              <div className="form-actions">
                <span className="form-hint">Un enlace más corto. Una conexión directa.</span>
                <button type="submit" className="primary-button" disabled={creating}>
                  {creating ? <span className="loading-spinner" aria-hidden="true" /> : <Icon name="bolt" />}
                  {creating ? 'Acortando...' : 'Acortar'}
                  <Icon name="arrow" />
                </button>
              </div>
            </form>
            {created && <div className="result" aria-live="polite">
              <p className="result-label"><Icon name="check" /> Enlace acortado correctamente</p>
              <div className="result-row">
                <a href={created.shortenedUrl} target="_blank" rel="noreferrer">{created.shortenedUrl}</a>
                <button type="button" className={`secondary-button ${copied === created.shortenedUrl ? 'copied' : ''}`} onClick={() => void copyLink(created.shortenedUrl)}>
                  <Icon name={copied === created.shortenedUrl ? 'check' : 'copy'} />
                  {copied === created.shortenedUrl ? 'Copiado' : 'Copiar'}
                </button>
              </div>
            </div>}
            <div className="system-features">
              <div><span className="feature-icon"><Icon name="link" /></span><span><strong>Conexión directa</strong><small>Al destino original</small></span></div>
              <div><span className="feature-icon feature-violet"><Icon name="database" /></span><span><strong>Persistencia MongoDB</strong><small>Tus enlaces permanecen</small></span></div>
              <div><span className="feature-icon feature-green"><Icon name="bolt" /></span><span><strong>Caché Redis</strong><small>Ventana única de 30 segundos</small></span></div>
            </div>
          </div>
        ) : (
          <div className="links-pane" role="tabpanel">
            <div className="section-title table-heading">
              <div><span className="section-code" aria-hidden="true">02 / LINK DIRECTORY</span><h2>Enlaces registrados</h2><p>Los enlaces originales se guardan de forma permanente en MongoDB.</p></div>
              <span className="source-indicator"><Icon name="database" /> Persistencia: MongoDB</span>
            </div>
            <div className="table-scroll">
              <table>
                <thead><tr><th scope="col">ID</th><th scope="col">Enlace original</th><th scope="col">Enlace acortado</th><th scope="col">Estado de caché</th></tr></thead>
                <tbody>
                  {links.map(link => {
                    const status = cacheStatuses[link.id];
                    const active = cacheSynchronized && status?.active && status.remainingMillis > 0;
                    return <tr key={link.id} className={active ? 'cache-active-row cached-row' : ''}>
                      <td className="id-cell">{link.id}</td>
                      <td><a href={link.originalUrl} target="_blank" rel="noreferrer">{link.originalUrl}</a></td>
                      <td><a href={link.shortenedUrl} target="_blank" rel="noreferrer">{link.shortenedUrl}</a></td>
                      <td className="cache-state-cell">
                        {active ? <span className="cache-badge cache-row-badge redis-active"><span className="redis-light" aria-hidden="true" /> Redis activo <strong>{Math.ceil(status.remainingMillis / 1000)} s</strong></span>
                          : status?.expired ? <span className="cache-badge expired">Expirado</span>
                          : <span className="cache-badge inactive">{cacheSynchronized ? 'Sin caché activo' : 'Estado no disponible'}</span>}
                      </td>
                    </tr>;
                  })}
                  {!loading && links.length === 0 && (
                    <tr><td colSpan={4} className="empty-cell">No hay enlaces. Ve a «Acortar enlace» para registrar el primero.</td></tr>
                  )}
                  {loading && <tr><td colSpan={4} className="empty-cell">Consultando enlaces...</td></tr>}
                </tbody>
              </table>
            </div>
            <p className="table-note">Pulsa «Ver enlaces» nuevamente para realizar otra consulta y comprobar el caché sin reiniciar el contador.</p>
          </div>
        )}
        <div className="workspace-rail" aria-hidden="true"><span>HEXAGONAL / URL GATEWAY</span><span className="rail-track" /><span>REST + WEBSOCKET</span></div>
      </section>
      <footer><span className="footer-mark" aria-hidden="true">HEX / LINK <span>© 2026</span></span><span>Cada enlace nuevo tiene una única ventana de caché de 30 segundos. Sus datos permanecen en MongoDB.</span><span className="footer-end" aria-hidden="true">END OF INTERFACE <span>↗</span></span></footer>
    </main>
  );
}
